package ru.urfu.alphamatcher.experiment;

import java.nio.file.*;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.security.MessageDigest;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;
import static ru.urfu.alphamatcher.experiment.SqlProfileMain.*;

/** Identical harness compiled against archived and current code. Known v1/v2/v3 only, never reads v4. */
public final class Stage4QualityMain {
    static final Path ROOT=Path.of("/results");
    public static void main(String[] args) throws Exception {
        if(args.length!=1||!List.of("before","after").contains(args[0]))throw new IllegalArgumentException("before | after");
        Path out=ROOT.resolve(args[0]);Files.createDirectories(out);
        try {
            if(!Files.readString(Path.of("/sys/fs/cgroup/cpuset.cpus.effective")).trim().equals("0-1")||Runtime.getRuntime().availableProcessors()!=2)throw new IllegalStateException("Shared cpuset not effective");
            var env=new Properties();env.setProperty("java",System.getProperty("java.runtime.version"));env.setProperty("processors",Integer.toString(Runtime.getRuntime().availableProcessors()));env.setProperty("heapMax",Long.toString(Runtime.getRuntime().maxMemory()));env.setProperty("args",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString());save(out.resolve("jvm.properties"),env);
            try(var c=ExperimentDatabase.connect()) {
                boolean probe=Boolean.getBoolean("stage4.probe");
                if(!probe)quality(c,out);
                for(int n:probe?new int[]{10000}:new int[]{1000,10000})for(Profile profile:probe?new Profile[]{Profile.MIXED}:Profile.values()) {
                    Path folder=out.resolve(profile.name().toLowerCase(Locale.ROOT)+"-"+n);Files.createDirectories(folder);
                    var data=new SyntheticGenerator().generate(Config.standard(20261029,n*12/100,n*24/100,n,profile));
                    var digest=MessageDigest.getInstance("SHA-256");
                    for(var list:List.of(data.clients(),data.contracts(),data.payments(),data.truth()))for(Object item:list){digest.update(item.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)'\n');}
                    Files.writeString(folder.resolve("dataset.sha256"),HexFormat.of().formatHex(digest.digest()));
                    new SyntheticLoader().replace(c,data);c.commit();
                    try(var s=c.createStatement()){for(String table:List.of("client","contract","contract_party","operation","operation_party"))s.execute("ANALYZE TABLE "+table);}c.rollback();
                    var ids=data.truth().stream().map(Truth::id).toList();var truth=new HashMap<Long,Truth>();data.truth().forEach(t->truth.put(t.id(),Stage3QualityMain.referenceV3(t)));
                    var serial=new HashMap<Long,Decision>();
                    try(var session=MatchingEngine.openReadOnlySession()){for(long id:ids)serial.put(id,session.match(id));}
                    try(var writer=Files.newBufferedWriter(folder.resolve("serial.tsv"))){row(writer,"id","sha256","fullDecisionBase64");for(long id:ids)row(writer,id,hash(serial.get(id).toString()),encode(serial.get(id)));}
                    String prefix=args[0]+"-"+folder.getFileName();
                    pass(c,folder,prefix+"-warmup",ids.subList(0,Math.min(n,1000)),serial,truth,true);
                    for(int repeat=1;repeat<=2;repeat++)pass(c,folder,prefix+"-repeat"+repeat,ids,serial,truth,false);
                }
                new SyntheticLoader().replace(c,new Dataset(Config.standard(20261029,12,16,1,Profile.MIXED),List.of(),List.of(),List.of(),List.of()));c.commit();
            }
            Files.writeString(out.resolve("COMPLETED.txt"),"Known corpora and bounded 1k/10k W4 matrix complete; v4 untouched\n");
        }catch(Throwable e){Files.writeString(out.resolve("FAILED.txt"),e.toString());throw e;}
    }
    static void quality(Connection c,Path out) throws Exception {
        for(String name:List.of("development-v1","final-v1","final-v2","final-v3")) {
            String version=name.substring(name.length()-2);var cases=Stage3Corpus.read(Path.of("stage3/corpora/"+name+".tsv"));Path dir=out.resolve(name);Files.createDirectories(dir);
            try {
                Stage3Corpus.load(c,Path.of("stage3/corpora/world-"+version+".sql"),cases);
                if(!version.equals("v1"))try(var s=c.createStatement()){String text=Files.readString(Path.of("stage3/corpora/external-"+version+".sql")).replaceAll("(?m)^--[^\\r\\n]*","");for(String sql:text.split(";"))if(!sql.isBlank())s.execute(sql);}
                var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());var q=new QualityReport();var matrix=new TreeMap<String,Long>();var reasons=new TreeMap<String,Long>();int rowsWithIssues=0;
                try(var rows=Files.newBufferedWriter(dir.resolve("decisions.tsv"));var errors=Files.newBufferedWriter(dir.resolve("errors.tsv"))) {
                    row(rows,"id","label","expected","actual","issues","sha256","fullDecisionBase64","truth");row(errors,"id","label","issues");
                    for(var value:cases){var d=engine.match(c,value.id());var issues=q.accept(value.truth(),d);matrix.merge(value.truth().expectedStatus()+"->"+d.status(),1L,Long::sum);if(d.status()==MatchingModel.Status.REVIEW)reasons.merge(Stage3QualityMain.reviewReason(d),1L,Long::sum);
                        row(rows,value.id(),value.label(),value.truth().expectedStatus(),d.status(),issues,hash(d.toString()),encode(d),value.truth());if(!issues.isEmpty()){rowsWithIssues++;row(errors,value.id(),value.label(),issues);}}
                }
                var p=qualityProperties(q);p.setProperty("rowsWithIssues",Integer.toString(rowsWithIssues));matrix.forEach((k,v)->p.setProperty("matrix."+k,v.toString()));reasons.forEach((k,v)->p.setProperty("review."+k,v.toString()));save(dir.resolve("summary.properties"),p);
                if(q.counts().get("wrongSafe")>0)throw new IllegalStateException("Wrong SAFE on known corpus "+name);
                if(out.getFileName().toString().equals("after")) noNewKnownCorpusViolations(ROOT.resolve("before").resolve(name).resolve("decisions.tsv"),dir.resolve("decisions.tsv"));
                System.out.println(out.getFileName()+" "+name+": "+q.counts());
            }finally{c.rollback();}
        }
    }
    static void noNewKnownCorpusViolations(Path before,Path after) throws Exception {
        var old=new HashMap<String,Set<String>>();
        for(String line:Files.readAllLines(before).subList(1,Files.readAllLines(before).size())){String[] p=line.split("\t",-1);if(old.put(p[0],issues(p[4]))!=null)throw new IllegalStateException("Duplicate reference ID");}
        var current=Files.readAllLines(after);
        if(current.size()!=old.size()+1)throw new IllegalStateException("Known corpus count changed");
        for(String line:current.subList(1,current.size())){String[] p=line.split("\t",-1);if(!old.containsKey(p[0])||!old.get(p[0]).containsAll(issues(p[4])))throw new IllegalStateException("New oracle violation on ID "+p[0]+": "+p[4]);}
    }
    static Set<String> issues(String value){return value.equals("[]")?Set.of():Set.copyOf(Arrays.asList(value.substring(1,value.length()-1).split(", ")));}
    static void pass(Connection c,Path folder,String phase,List<Long> ids,Map<Long,Decision> serial,Map<Long,Truth> truth,boolean warm) throws Exception {
        Path dir=folder.resolve(phase);Files.createDirectories(dir);signal("start",phase);Files.writeString(dir.resolve("java-before.txt"),snapshot());
        var q=new QualityReport();long[] ns=new long[ids.size()],sums=new long[3];int[] count={0},diff={0};var p=new Properties();p.setProperty("N",Integer.toString(ids.size()));p.setProperty("workers","4");p.setProperty("warmup",Boolean.toString(warm));
        try(var rows=Files.newBufferedWriter(dir.resolve("outcomes.tsv"))) {
            row(rows,"id","technical","sha256","equalFullDecision");
            try {
                var s=new ParallelMatcher().run(ids,new ParallelMatcher.Config(4,16,Duration.ofMinutes(5),Duration.ofSeconds(20),true),new ParallelMatcher.Cancellation(),o->{
                    try {if(o.state()!=ParallelMatcher.State.SUCCESS)throw new IllegalStateException("Technical failure "+o.id()+" "+o.state());var d=o.result();boolean equal=serial.get(o.id()).equals(d.decision());if(!equal)diff[0]++;q.accept(truth.get(o.id()),d.decision());ns[count[0]++]=d.totalNanos();sums[0]+=d.parsingNanos();sums[1]+=d.sqlNanos();sums[2]+=d.rulesNanos();row(rows,o.id(),o.state(),hash(d.decision().toString()),equal);}catch(Exception e){throw new IllegalStateException(e);}});
                p.setProperty("summary",s.toString());p.setProperty("fullBatchNs",Long.toString(s.totalNanos()));p.setProperty("processingNs",Long.toString(s.processingNanos()));p.setProperty("opsPerSecond",Double.toString(ids.size()*1e9/s.totalNanos()));p.setProperty("selects",Long.toString(s.selects()));p.setProperty("sessionsOpened",Integer.toString(s.sessionsOpened()));p.setProperty("peakSessions",Integer.toString(s.peakSessions()));
                if(diff[0]!=0||count[0]!=ids.size()||s.errors()!=0||s.cancelled()!=0||s.timedOut()!=0||!s.sqlCountsComplete()||q.counts().get("wrongSafe")!=0||q.counts().get("statusMismatch")!=0||q.counts().get("missedTargets")!=0)throw new IllegalStateException("Correctness failure "+phase);
                cleanup(c);p.setProperty("status","PASS");
            }catch(Throwable e){p.setProperty("status","FAIL");p.setProperty("failure",e.toString());throw e;}
            finally {
                p.putAll(qualityProperties(q));p.setProperty("delivered",Integer.toString(count[0]));p.setProperty("fullDecisionDifferences",Integer.toString(diff[0]));Arrays.sort(ns,0,count[0]);
                for(double f:new double[]{.5,.95,.99})p.setProperty("p"+(int)(f*100)+"Ns",Long.toString(count[0]==0?0:ns[(int)Math.ceil(f*count[0])-1]));
                for(int i=0;i<3;i++)p.setProperty(new String[]{"parsingSumNs","sqlJdbcSumNs","rulesSumNs"}[i],Long.toString(sums[i]));save(dir.resolve("summary.properties"),p);
            }
        }finally{Files.writeString(dir.resolve("java-after.txt"),snapshot());signal("end",phase);}
        System.out.println(phase+": "+p.getProperty("opsPerSecond")+" ops/s");
    }
    static Properties qualityProperties(QualityReport q) {var p=new Properties();q.counts().forEach((k,v)->p.setProperty(k,v.toString()));p.setProperty("safePrecision",Double.toString(q.safePrecision()));long safe=q.counts().get("correctSafe"),miss=q.counts().get("expectedSafeMissed");p.setProperty("safeRecall",safe+miss==0?"NaN":Double.toString((double)safe/(safe+miss)));q.extraction().forEach((k,v)->{p.setProperty(k+".tp",Long.toString(v.truePositive));p.setProperty(k+".fp",Long.toString(v.falsePositive));p.setProperty(k+".fn",Long.toString(v.falseNegative));});return p;}
    static void cleanup(Connection c) throws Exception {long end=System.nanoTime()+Duration.ofSeconds(5).toNanos(),n;do{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_USER='matcher' AND PROCESSLIST_ID<>CONNECTION_ID()")){r.next();n=r.getLong(1);}if(n==0)break;Thread.sleep(20);}while(System.nanoTime()<end);c.rollback();if(n!=0||Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("matching-batch-")))throw new IllegalStateException("Leaked worker/session");}
    static String encode(Decision d){return Base64.getEncoder().encodeToString(d.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static String snapshot() throws Exception {StringBuilder s=new StringBuilder("processors="+Runtime.getRuntime().availableProcessors()+"\n");for(String item:List.of("cpu.stat","cpu.max","cpu.pressure","cpuset.cpus.effective","memory.current","memory.peak","memory.max","memory.events"))s.append(item).append('=').append(Files.readString(Path.of("/sys/fs/cgroup",item)).trim().replace('\n',';')).append('\n');return s.toString();}
    static void signal(String kind,String phase) throws Exception {Path req=ROOT.resolve(kind+"-"+phase+".request"),ack=ROOT.resolve(kind+"-"+phase+".ack");Files.createFile(req);long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(!Files.exists(ack)){if(System.nanoTime()>end)throw new IllegalStateException("Collector did not acknowledge "+phase);Thread.sleep(20);}}
}
