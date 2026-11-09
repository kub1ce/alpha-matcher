package ru.urfu.alphamatcher.experiment;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;

/** Research-only adapter: measured core is unchanged PurposeParser + MatchingEngine.evaluate.
 * Candidate discovery is deliberately excluded, using immutable, checksum-verified real-SQL fixtures.
 */
public final class CpuFixtureAdapter implements AutoCloseable {
 private final Dataset data;
 private final Operation[] operations;
 private final Candidate[] catalog;
 private final List<Candidate>[] fixtures;
 private final Path reference;
 private final PurposeParser parser=new PurposeParser();
 private final ExecutorService pool=Executors.newFixedThreadPool(4);
 private final com.sun.management.ThreadMXBean mx=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
 private final String datasetSha;
 private record Sample(Decision decision,long total,long parsing,long lookup,long rules,long cpu,long allocated){}
 private static final class View extends AbstractList<Candidate> implements RandomAccess {
  final Candidate[] catalog;final int[] indices;
  View(Candidate[] catalog,int[] indices){this.catalog=catalog;this.indices=indices;}
  public int size(){return indices.length;}public Candidate get(int index){return catalog[indices[index]];}
 }
 @SuppressWarnings("unchecked")
 public CpuFixtureAdapter(String referencePath)throws Exception {
  reference=Path.of(referencePath);StreamingReference.verify(reference);
  if(!mx.isThreadCpuTimeSupported()||!mx.isThreadAllocatedMemorySupported())throw new IllegalStateException("CPU/allocation counters unavailable");
  mx.setThreadCpuTimeEnabled(true);mx.setThreadAllocatedMemoryEnabled(true);
  data=new SyntheticGenerator().generate(Config.standard(20261029,12000,24000,100000,Profile.MIXED));
  var digest=MessageDigest.getInstance("SHA-256");
  for(var list:List.of(data.clients(),data.contracts(),data.payments(),data.truth()))for(Object item:list){digest.update(item.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)'\n');}
  datasetSha=HexFormat.of().formatHex(digest.digest());
  catalog=new Candidate[data.contracts().size()];
  for(var c:data.contracts()){
   var parties=new LinkedHashMap<String,Party>();parties.put("PAYER",party(c.payer(),null,false));parties.put("RECIPIENT",party(c.recipient(),null,false));
   if(c.beneficiary()!=null)parties.put("BENEFICIARY",party(c.beneficiary(),null,false));
   catalog[(int)(c.id()-SyntheticGenerator.FIRST_ID)]=new Candidate(c.id(),c.number(),parties);
  }
  operations=new Operation[data.payments().size()];fixtures=(List<Candidate>[])new List<?>[operations.length];
  try(var reader=new StreamingReference.Reader(reference)){
   if(reader.count!=100000)throw new IllegalStateException("Historical fixture is not100k");
   for(int i=0;i<operations.length;i++){
    var p=data.payments().get(i);operations[i]=new Operation(p.id(),p.purpose(),p.status(),true,p.linked(),Map.of("PAYER",party(p.payer(),p.payerInn(),true),"RECIPIENT",party(p.recipient(),p.recipientInn(),true)));
    var entry=reader.next(p.id());if(!entry.truth().equals(Stage3QualityMain.referenceV3(data.truth().get(i))))throw new IllegalStateException("Dataset/oracle mismatch "+p.id());
    String d=entry.decision();String marker=", candidateIds=[";int start=d.indexOf(marker),end=d.indexOf("], features=",start);
    if(start<0||end<0)throw new IllegalStateException("Unknown historical decision format");
    String text=d.substring(start+marker.length(),end);String[] ids=text.isEmpty()?new String[0]:text.split(", ");int[] indices=new int[ids.length];long previous=Long.MIN_VALUE;
    for(int j=0;j<ids.length;j++){long id=Long.parseLong(ids[j]);int index=Math.toIntExact(id-SyntheticGenerator.FIRST_ID);if(index<0||index>=catalog.length||id<=previous)throw new IllegalStateException("Invalid candidate fixture");indices[j]=index;previous=id;}
    fixtures[i]=new View(catalog,indices);
   }
   reader.finish(100000);
  }
 }
 private Party party(Long id,String inn,boolean operation){
  Client c=id==null?null:data.clients().get(Math.toIntExact(id-SyntheticGenerator.FIRST_ID));
  return new Party(id,inn,c==null?null:c.inn(),true,c==null||!operation?null:c.name(),c==null?null:c.name(),c==null?null:c.last(),c==null?null:c.first(),c==null?null:c.middle());
 }
 public String datasetSha(){return datasetSha;}
 private Sample compute(int index){
  long thread=Thread.currentThread().threadId(),allocated=mx.getThreadAllocatedBytes(thread),cpu=mx.getCurrentThreadCpuTime(),start=System.nanoTime();
  Operation op=operations[index];var features=parser.parse(op.purpose());long parsed=System.nanoTime();
  List<Candidate> candidates=features.issues().stream().anyMatch(s->s.contains("TIME_LIMIT")||s.contains("MATCH_LIMIT")||s.startsWith("SIZE_LIMIT:"))?List.of():fixtures[index];long looked=System.nanoTime();
  var decision=MatchingEngine.evaluate(op,features,candidates);long finish=System.nanoTime();
  return new Sample(decision,finish-start,parsed-start,looked-parsed,finish-looked,mx.getCurrentThreadCpuTime()-cpu,mx.getThreadAllocatedBytes(thread)-allocated);
 }
 public Properties serial(String directory)throws Exception{
  Path out=Path.of(directory);Files.createDirectories(out);var quality=new QualityReport();
  try(var reader=new StreamingReference.Reader(reference);var rows=Files.newBufferedWriter(out.resolve("serial-hashes.tsv"),StandardOpenOption.CREATE_NEW)){
   rows.write("id\tsha256\n");
   for(int i=0;i<100000;i++){
    Sample sample=compute(i);var entry=reader.next(operations[i].id());String value=sample.decision.toString();
    if(!value.equals(entry.decision())){Files.writeString(out.resolve("mismatch-"+operations[i].id()+".txt"),entry.decision()+"\n"+value);throw new IllegalStateException("Full serial Decision differs from actual SQL reference "+operations[i].id());}
    if(!quality.accept(entry.truth(),sample.decision).isEmpty())throw new IllegalStateException("Independent serial oracle violation "+operations[i].id());
    rows.write(operations[i].id()+"\t"+SqlProfileMain.hash(value)+"\n");
   }
   reader.finish(100000);
  }
  var p=properties(quality);p.setProperty("datasetSha256",datasetSha);p.setProperty("referenceSha256",StreamingReference.sha(reference));save(out.resolve("serial.properties"),p);return p;
 }
 public Properties run(String directory,int count)throws Exception{
  if(count!=10000&&count!=100000)throw new IllegalArgumentException("Warmup10k or full100k only");
  Path out=Path.of(directory);Files.createDirectories(out);var quality=new QualityReport();long[] latency=new long[count];
  long elapsed=0,parse=0,lookup=0,rules=0,cpu=0,allocated=0,heapMax=0;int delivered=0;
  long phaseStart=System.nanoTime(),processCpu=((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
  try(var reader=new StreamingReference.Reader(reference);var rows=Files.newBufferedWriter(out.resolve("outcomes.tsv"),StandardOpenOption.CREATE_NEW)){
   rows.write("id\tstatus\tsha256\tfullSerialEquality\n");
   for(int offset=0;offset<count;offset+=256){
    int from=offset,size=Math.min(256,count-offset);var expected=new ArrayList<StreamingReference.Entry>(size);
    for(int j=0;j<size;j++)expected.add(reader.next(operations[offset+j].id())); // Disk IO outside compute window.
    Sample[] samples=new Sample[size];List<Future<?>> futures=new ArrayList<>();long begin=System.nanoTime();
    try{
     for(int worker=0;worker<4;worker++){int w=worker;futures.add(pool.submit(()->{for(int j=w;j<size;j+=4)samples[j]=compute(from+j);}));}
     for(var f:futures)f.get(60,TimeUnit.SECONDS);
    }catch(Throwable failure){for(var f:futures)f.cancel(true);throw failure;}
    elapsed+=System.nanoTime()-begin;
    heapMax=Math.max(heapMax,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    for(int j=0;j<size;j++){
     Sample a=samples[j];var e=expected.get(j);String value=a.decision.toString();
     if(!value.equals(e.decision()))throw new IllegalStateException("Parallel/serial mismatch "+a.decision.operationId());
     if(!quality.accept(e.truth(),a.decision).isEmpty())throw new IllegalStateException("Independent oracle violation "+a.decision.operationId());
     latency[delivered++]=a.total;parse+=a.parsing;lookup+=a.lookup;rules+=a.rules;cpu+=a.cpu;allocated+=a.allocated;
     rows.write(a.decision.operationId()+"\t"+a.decision.status()+"\t"+SqlProfileMain.hash(value)+"\ttrue\n");
    }
   }
   reader.finish(count);
  }catch(Throwable failure){var p=new Properties();p.setProperty("status","FAIL");p.setProperty("delivered",Integer.toString(delivered));p.setProperty("computeWindowNs",Long.toString(elapsed));p.setProperty("failure",failure.toString());save(out.resolve("summary.properties"),p);throw failure;}
  long phase=System.nanoTime()-phaseStart;processCpu=((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime()-processCpu;
  if(delivered!=count)throw new IllegalStateException("Incomplete batch");Arrays.sort(latency);
  var p=properties(quality);p.setProperty("N",Integer.toString(count));p.setProperty("workers","4");p.setProperty("datasetSha256",datasetSha);
  p.setProperty("computeWindowNs",Long.toString(elapsed));p.setProperty("phaseWallIncludingVerificationNs",Long.toString(phase));p.setProperty("throughput",Double.toString(count*1e9/elapsed));
  p.setProperty("parsingNs",Long.toString(parse));p.setProperty("fixtureLookupNs",Long.toString(lookup));p.setProperty("rulesNs",Long.toString(rules));p.setProperty("workerCpuNs",Long.toString(cpu));p.setProperty("processCpuIncludingVerificationNs",Long.toString(processCpu));p.setProperty("allocatedComputeBytes",Long.toString(allocated));p.setProperty("maxSampledHeapBytes",Long.toString(heapMax));
  for(double q:new double[]{.5,.95,.99})p.setProperty("p"+(int)(q*100)+"Ns",Long.toString(latency[(int)Math.ceil(q*count)-1]));
  p.setProperty("status","PASS");save(out.resolve("summary.properties"),p);return p;
 }
 private static Properties properties(QualityReport q){var p=new Properties();q.counts().forEach((k,v)->p.setProperty(k,v.toString()));q.extraction().forEach((k,v)->{p.setProperty(k+".tp",Long.toString(v.truePositive));p.setProperty(k+".fp",Long.toString(v.falsePositive));p.setProperty(k+".fn",Long.toString(v.falseNegative));});return p;}
 private static void save(Path p,Properties v)throws Exception{try(var w=Files.newBufferedWriter(p,StandardOpenOption.CREATE_NEW)){v.store(w,"CPU-only research; candidate discovery and verification excluded from primary compute window");}}
 @Override public void close()throws Exception{pool.shutdownNow();if(!pool.awaitTermination(30,TimeUnit.SECONDS))throw new IllegalStateException("CPU worker leak");}
}
