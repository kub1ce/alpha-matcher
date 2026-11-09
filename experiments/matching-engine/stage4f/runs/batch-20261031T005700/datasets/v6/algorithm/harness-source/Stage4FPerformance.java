package ru.urfu.alphamatcher.experiment;

import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;
import ru.urfu.alphamatcher.experiment.synthetic.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;

/** Bounded full JDBC comparison; not the historical pure CPU benchmark. */
public class Stage4FPerformance {
    static Path root,out;
    static long gcCount(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionCount())).sum();}
    static long gcMs(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
    static long heap(){return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    static long cpu(){return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();}
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]);V4Harness.root=root;
        Profile profile=Profile.valueOf(args[1]);out=root.resolve("performance/"+profile+"/"+args[2]);Files.createDirectories(out.getParent());Files.createDirectory(out);
        if(NativeRuntime457.mask()!=15 || NativeRuntime457.threadMask()!=15)throw new IllegalStateException("Wrong CPU budget");
        var config=Config.standard(20261029,1200,2400,10000,profile);var data=new SyntheticGenerator().generate(config);
        String dataHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var writer=ExperimentDatabase.connect()){
            V4Harness.ownServer(writer);new SyntheticLoader().replace(writer,data);writer.commit();
            try(var s=writer.createStatement()){for(String table:List.of("client","contract","contract_party","operation","operation_party"))s.execute("ANALYZE TABLE "+table);}
        }
        var before=V4Harness.snapshot();V4Harness.write(out.resolve("database-before.json"),before);
        var reference=new LinkedHashMap<Long,Decision>();
        try(var session=MatchingEngine.openReadOnlySession()){for(var p:data.payments())reference.put(p.id(),session.match(p.id()));}
        V4Harness.write(out.resolve("dataset.json"),Map.of("config",config.toString(),"sha256",dataHash,"operations",data.payments().size(),"referenceDecisions",reference.size(),"java",System.getProperty("java.runtime.version")));
        var truth=new HashMap<Long,Truth>();for(var t:data.truth())truth.put(t.id(),Stage3QualityMain.referenceV3(t));
        var ids=data.payments().stream().map(Payment::id).toList();
        for(int warm=1;warm<=2;warm++)pass("warmup-"+warm,ids.subList(0,1000),reference,truth);
        for(int repeat=1;repeat<=3;repeat++)pass("measured-"+repeat,ids,reference,truth);
        var after=V4Harness.snapshot();V4Harness.write(out.resolve("database-after.json"),after);
        if(!before.equals(after))throw new IllegalStateException("Matching changed DB");
        V4Harness.write(out.resolve("COMPLETE.json"),Map.of("datasetSHA256",dataHash,"sameDatabase",true,"workers",4,"mask",15));
    }
    static void pass(String name,List<Long> ids,Map<Long,Decision> reference,Map<Long,Truth> truth)throws Exception{
        var serverBefore=NativeProcess4F.server();long gc=gcCount(),gct=gcMs(),cpu=cpu();long[] ns=new long[ids.size()],sums=new long[3],peak={heap()},candidates={0};int[] n={0},diff={0};var q=new QualityReport();
        try(var rows=Files.newBufferedWriter(out.resolve(name+".jsonl"),StandardOpenOption.CREATE_NEW)){
            var summary=new ParallelMatcher().run(ids,ParallelMatcher.Config.standard(4),new ParallelMatcher.Cancellation(),v->{
                try{
                    if(v.state()!=ParallelMatcher.State.SUCCESS)throw new IllegalStateException("Technical failure "+v);
                    var t=v.result();var d=t.decision();if(!reference.get(v.id()).equals(d))diff[0]++;
                    q.accept(truth.get(v.id()),d);ns[n[0]++]=t.totalNanos();sums[0]+=t.parsingNanos();sums[1]+=t.sqlNanos();sums[2]+=t.rulesNanos();
                    candidates[0]+=d.candidateIds().size();peak[0]=Math.max(peak[0],heap());
                    rows.write(V4Harness.json(Map.of("id",v.id(),"decision",d,"totalNs",t.totalNanos(),"parsingNs",t.parsingNanos(),"jdbcNs",t.sqlNanos(),"rulesNs",t.rulesNanos())));rows.newLine();
                }catch(Exception e){throw new IllegalStateException(e);}
            });
            Arrays.sort(ns);var result=new LinkedHashMap<String,Object>();result.put("summary",summary);result.put("elapsedNs",summary.totalNanos());
            result.put("throughput",ids.size()*1e9/summary.totalNanos());result.put("p50Ns",ns[(int)Math.ceil(ns.length*.5)-1]);result.put("p95Ns",ns[(int)Math.ceil(ns.length*.95)-1]);result.put("p99Ns",ns[(int)Math.ceil(ns.length*.99)-1]);
            result.put("parsingSumNs",sums[0]);result.put("jdbcSumNs",sums[1]);result.put("rulesSumNs",sums[2]);result.put("javaCpuNs",cpu()-cpu);result.put("sampledHeapPeakBytes",peak[0]);
            var serverAfter=NativeProcess4F.server();result.put("mysqlCpuNs",serverAfter.get("cpuNs")-serverBefore.get("cpuNs"));result.put("mysqlMemoryBefore",serverBefore);result.put("mysqlMemoryAfter",serverAfter);result.put("gcCount",gcCount()-gc);result.put("gcMillis",gcMs()-gct);result.put("candidateCount",candidates[0]);result.put("fullDecisionDifferences",diff[0]);result.put("quality",q.counts());
            result.put("timingBoundary","Full batch includes streaming verification/JSON output for both versions; per-operation latency excludes output, summed phase time includes concurrency");
            V4Harness.write(out.resolve(name+".metrics.json"),result);
            if(diff[0]!=0 || summary.errors()!=0 || summary.succeeded()!=ids.size() || q.counts().get("wrongSafe")!=0)throw new IllegalStateException("Correctness failure retained in metrics");
            System.out.println(name+" "+ids.size()+" elapsed="+summary.totalNanos()/1e9+"s throughput="+ids.size()*1e9/summary.totalNanos());
        }
    }
}
