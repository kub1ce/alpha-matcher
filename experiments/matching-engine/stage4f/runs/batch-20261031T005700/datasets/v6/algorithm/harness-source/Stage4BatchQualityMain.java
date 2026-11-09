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
public final class Stage4BatchQualityMain {
    static final Path ROOT=Path.of(System.getProperty("batch.output"));
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !List.of("before","after").contains(args[0]))throw new IllegalArgumentException("before | after");
        Path out=ROOT.resolve(args[0]);Files.createDirectories(out);
        try(var c=ExperimentDatabase.connect()) {quality(c,out);}
        Files.writeString(out.resolve("COMPLETED.txt"),"Known corpus quality completed\n");
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
