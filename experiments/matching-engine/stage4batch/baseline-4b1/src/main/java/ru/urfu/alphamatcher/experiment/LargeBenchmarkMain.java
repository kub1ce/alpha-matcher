package ru.urfu.alphamatcher.experiment;

import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;
import static ru.urfu.alphamatcher.experiment.SqlProfileMain.*;

/** Dedicated, disk-verified 100k experiment. Business rules and the legacy 10k runner are unchanged. */
public final class LargeBenchmarkMain {
    private static final Path OUT=Path.of("/results");
    private static final long SEED=20261029;
    private LargeBenchmarkMain() { }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        try {run();Files.writeString(OUT.resolve("COMPLETED.txt"),"100k experiment and first v3 evaluation completed\n");}
        catch(Throwable e){Files.writeString(OUT.resolve("FAILED.txt"),e.toString());e.printStackTrace();throw e;}
    }
    private static void run() throws Exception {
        String cpus=Files.readString(Path.of("/sys/fs/cgroup/cpuset.cpus.effective")).trim();
        if(!List.of("0-1","0,1").contains(cpus)||Runtime.getRuntime().availableProcessors()!=2)throw new IllegalStateException("Shared two logical CPU budget not effective");
        var env=new Properties();env.setProperty("java",System.getProperty("java.runtime.version"));env.setProperty("jvmArgs",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
        env.setProperty("availableProcessors",Integer.toString(Runtime.getRuntime().availableProcessors()));env.setProperty("cpuset",cpus);save(OUT.resolve("java-environment.properties"),env);
        freeze(OUT.resolve("sources-before-load.properties"));Files.writeString(OUT.resolve("java-startup-cgroup.txt"),snapshot());
        try(var c=ExperimentDatabase.connect()) {
            dump(c,OUT.resolve("mysql-variables.tsv"),"SELECT @@version,@@transaction_isolation,@@innodb_buffer_pool_size,@@max_connections,@@performance_schema,@@innodb_page_size,@@foreign_key_checks");
            scenario(c,"preflight-1k",1000,120,240,Profile.MIXED,false);
            scenario(c,"preflight-10k",10000,1200,2400,Profile.MIXED,false);
            scenario(c,"mixed-100k",100000,12000,24000,Profile.MIXED,true);
            Files.writeString(OUT.resolve("MAIN_100K_COMPLETED.txt"),"Every main W4/W8 repeat verified against full serial Decision and independent business oracle\n");
            scenario(c,"simple-10k",10000,1200,2400,Profile.SIMPLE,false);
            scenario(c,"stress-10k",10000,1200,2400,Profile.STRESS,false);
            scenario(c,"mixed-10k-large-catalog",10000,12000,24000,Profile.MIXED,false);
            scenario(c,"stress-10k-large-catalog",10000,12000,24000,Profile.STRESS,false);
            clearGenerated(c);
            freeze(OUT.resolve("sources-before-v3.properties"));
            var initial=new Properties();try(var reader=Files.newBufferedReader(OUT.resolve("sources-before-load.properties"))){initial.load(reader);}
            var current=new Properties();try(var reader=Files.newBufferedReader(OUT.resolve("sources-before-v3.properties"))){current.load(reader);}
            if(!initial.equals(current))throw new IllegalStateException("Sources changed during measurement; do not open v3");
            openV3(c);c.rollback();
        }
    }
    private static void scenario(Connection c,String label,int n,int clients,int contracts,Profile profile,boolean main) throws Exception {
        guardResources();long whole=System.nanoTime();Path folder=OUT.resolve(label);Files.createDirectories(folder);var setup=new Properties();
        try {
            List<Long> ids=prepare(c,folder,n,clients,contracts,profile,setup);
            int[] workers=main?new int[]{4,8}:new int[]{4};
            for(int w:workers) {
                pass(c,folder,label+"-w"+w+"-warmup",ids.subList(0,Math.min(10000,n)),w,true);
                for(int repeat=1;repeat<=2;repeat++)pass(c,folder,label+"-w"+w+"-repeat"+repeat,ids,w,false);
            }
        } catch(Throwable e){setup.setProperty("failure",e.toString());throw e;}
        finally{setup.setProperty("wholeScenarioNs",Long.toString(System.nanoTime()-whole));save(folder.resolve("setup.properties"),setup);}
    }
    private static List<Long> prepare(Connection c,Path folder,int n,int clients,int contracts,Profile profile,Properties setup) throws Exception {
        var config=Config.standard(SEED,clients,contracts,n,profile);setup.setProperty("config",config.toString());
        System.out.println("PREPARE "+folder.getFileName()+" "+config);long start=System.nanoTime();var data=new SyntheticGenerator().generate(config);
        setup.setProperty("generationNs",Long.toString(System.nanoTime()-start));
        var distribution=new TreeMap<String,Long>();data.truth().forEach(t->distribution.merge(t.scenario().name(),1L,Long::sum));distribution.forEach((k,v)->setup.setProperty("scenario."+k,v.toString()));
        start=System.nanoTime();new SyntheticLoader().replace(c,data);c.commit();setup.setProperty("loadCommitNs",Long.toString(System.nanoTime()-start));
        start=System.nanoTime();try(var s=c.createStatement()){for(String t:List.of("client","contract","contract_party","operation","operation_party"))s.execute("ANALYZE TABLE "+t);}
        setup.setProperty("analyzeNs",Long.toString(System.nanoTime()-start));
        dump(c,folder.resolve("table-sizes.tsv"),"SELECT TABLE_NAME,TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE TABLE_SCHEMA='matching_engine_exp' ORDER BY TABLE_NAME");
        dump(c,folder.resolve("indexes.tsv"),"SELECT TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,CARDINALITY FROM information_schema.statistics WHERE TABLE_SCHEMA='matching_engine_exp' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX");
        for(String table:List.of("client","contract","operation")){long actual=scalar(c,"SELECT COUNT(*) FROM "+table+" WHERE id>=1000000 AND id<10000000");setup.setProperty("actual."+table,Long.toString(actual));if(actual!=(table.equals("client")?clients:table.equals("contract")?contracts:n))throw new IllegalStateException("Incomplete load "+table);}
        dump(c,folder.resolve("completeness.tsv"),"SELECT COUNT(*),SUM(payment_purpose IS NULL),SUM(CHAR_LENGTH(payment_purpose)>4000),MAX(CHAR_LENGTH(payment_purpose)),AVG(CHAR_LENGTH(payment_purpose)) FROM operation WHERE id>=1000000 AND id<10000000");
        dump(c,folder.resolve("parties.tsv"),"SELECT 'operation_party',COUNT(*),SUM(client_id IS NULL),SUM(inn IS NULL) FROM operation_party WHERE operation_id>=1000000 AND operation_id<10000000 UNION ALL SELECT 'contract_party',COUNT(*),SUM(client_id IS NULL),SUM(inn IS NULL) FROM contract_party WHERE contract_id>=1000000 AND contract_id<10000000");
        c.rollback();guardResources();
        Path reference=folder.resolve("reference.bin");var ids=data.truth().stream().map(Truth::id).toList();start=System.nanoTime();
        var quality=new QualityReport();
        try(var writer=new StreamingReference.Writer(reference,n);var session=MatchingEngine.openReadOnlySession();var errors=Files.newBufferedWriter(folder.resolve("serial-oracle-errors.tsv"))) {
            row(errors,"id","issues");int done=0;
            for(Truth legacy:data.truth()) {
                Truth truth=Stage3QualityMain.referenceV3(legacy);var decision=session.match(truth.id());
                var issues=quality.accept(truth,decision);if(!issues.isEmpty())row(errors,truth.id(),issues);
                writer.add(truth,decision.toString());if(++done%10000==0){guardResources();System.out.println(folder.getFileName()+" reference "+done+"/"+n);}
            }
            writer.publish();
        }
        setup.setProperty("serialReferenceNs",Long.toString(System.nanoTime()-start));setup.setProperty("referenceBytes",Long.toString(Files.size(reference)));
        quality.counts().forEach((k,v)->setup.setProperty("serialQuality."+k,v.toString()));
        save(folder.resolve("setup.properties"),setup);Files.writeString(folder.resolve("java-after-prepare.txt"),snapshot());
        if(quality.counts().get("wrongSafe")>0||quality.counts().get("statusMismatch")>0||quality.counts().get("missedTargets")>0)throw new IllegalStateException("Independent oracle failure before benchmark");
        return ids;
    }
    private static void pass(Connection observer,Path folder,String phase,List<Long> ids,int workers,boolean warm) throws Exception {
        guardResources();Path out=folder.resolve(phase);Files.createDirectories(out);signal("start",phase);Files.writeString(out.resolve("java-before.txt"),snapshot());
        var p=new Properties();p.setProperty("N",Integer.toString(ids.size()));p.setProperty("workers",Integer.toString(workers));p.setProperty("warmup",Boolean.toString(warm));
        boolean success=false;
        try(var sink=new StreamedVerification(folder.resolve("reference.bin"),out,ids.size(),LargeBenchmarkMain::guardResources)) {
            try {
                var config=new ParallelMatcher.Config(workers,16,Duration.ofMinutes(20),Duration.ofSeconds(20),true);
                var s=new ParallelMatcher().run(ids,config,new ParallelMatcher.Cancellation(),sink);
                p.setProperty("summary",s.toString());p.setProperty("fullBatchNs",Long.toString(s.totalNanos()));p.setProperty("processingNs",Long.toString(s.processingNanos()));
                p.setProperty("fullBatchOpsPerSec",Double.toString(ids.size()*1e9/s.totalNanos()));p.setProperty("selects",Long.toString(s.selects()));
                p.setProperty("sessionsOpened",Integer.toString(s.sessionsOpened()));p.setProperty("peakSessions",Integer.toString(s.peakSessions()));p.setProperty("sqlCountsComplete",Boolean.toString(s.sqlCountsComplete()));
                success=sink.finish(ids.size())&&s.delivered()==ids.size()&&s.sqlCountsComplete();
                noSessionsOrTransactions(observer,out);p.setProperty("cleanup","PASS");
                if(!success)throw new IllegalStateException("Quality, ID or protocol mismatch "+phase);
            } catch(Throwable e){p.setProperty("failure",e.toString());throw e;}
            finally{p.putAll(sink.properties());p.setProperty("status",success?"PASS":"FAIL");save(out.resolve("summary.properties"),p);}
        } finally {Files.writeString(out.resolve("java-after.txt"),snapshot());signal("end",phase);}
        System.out.println(phase+": "+p.getProperty("fullBatchOpsPerSec")+" ops/s; lastId="+p.getProperty("lastId")+" all verified");
    }
    private static void noSessionsOrTransactions(Connection c,Path out) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();long sessions;
        do {sessions=scalar(c,"SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_USER='matcher' AND PROCESSLIST_ID<>CONNECTION_ID()");if(sessions==0)break;Thread.sleep(20);}while(System.nanoTime()<until);
        long transactions=scalar(c,"SELECT COUNT(*) FROM performance_schema.events_transactions_current e JOIN performance_schema.threads t ON t.THREAD_ID=e.THREAD_ID WHERE e.STATE='ACTIVE' AND t.PROCESSLIST_USER='matcher' AND t.PROCESSLIST_ID<>CONNECTION_ID()");
        Files.writeString(out.resolve("cleanup.txt"),"otherMatcherSessions="+sessions+"\nactiveWorkerTransactions="+transactions+"\n");c.rollback();
        if(sessions!=0||transactions!=0||Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("matching-batch-")))throw new IllegalStateException("Leaked session/transaction/worker");
    }
    private static void openV3(Connection c) throws Exception {
        Path corpora=Path.of("stage3/corpora"),out=OUT.resolve("v3-first");Files.createDirectories(out);
        for(String line:Files.readAllLines(corpora.resolve("v3-SHA256SUMS"))) {
            String[] parts=line.trim().split("\\s+",2);if(parts.length!=2)throw new IllegalStateException("Invalid freeze ledger");
            if(!StreamingReference.sha(corpora.resolve(parts[1])).equalsIgnoreCase(parts[0]))throw new IllegalStateException("Frozen v3 input changed: "+parts[1]);
        }
        Files.copy(corpora.resolve("v3-SHA256SUMS"),out.resolve("INPUT-SHA256SUMS"));
        Files.writeString(out.resolve("FIRST_OPEN.txt"),"First semantic opening: "+Instant.now()+"\nSources frozen in sources-before-v3.properties; v3 becomes known after this run.\n",StandardOpenOption.CREATE_NEW);
        var cases=Stage3Corpus.read(corpora.resolve("final-v3.tsv"));if(cases.size()!=64)throw new IllegalStateException("Expected 64 frozen cases");
        Stage3Corpus.load(c,corpora.resolve("world-v3.sql"),cases);
        try(var s=c.createStatement()){String sql=Files.readString(corpora.resolve("external-v3.sql")).replaceAll("(?m)^--[^\\r\\n]*","");for(String statement:sql.split(";"))if(!statement.isBlank())s.execute(statement);}
        // First immutable result is collected once, within the same disposable transaction as the world.
        var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());var q=new QualityReport();var p=new Properties();var matrix=new TreeMap<String,Long>();var reasons=new TreeMap<String,Long>();
        try(var rows=Files.newBufferedWriter(out.resolve("first-decisions.tsv"));var errors=Files.newBufferedWriter(out.resolve("first-errors.tsv"))) {
            row(rows,"id","label","expected","actual","issues","sha256","fullDecisionBase64","truth");row(errors,"id","label","issues","expectedReason","actualReason","checks");
            for(var value:cases){var d=engine.match(c,value.id());var issues=q.accept(value.truth(),d);String reason=Stage3QualityMain.reviewReason(d);
                matrix.merge(value.truth().expectedStatus()+" -> "+d.status(),1L,Long::sum);if(d.status()==MatchingModel.Status.REVIEW)reasons.merge(reason,1L,Long::sum);
                row(rows,value.id(),value.label(),value.truth().expectedStatus(),d.status(),issues,hash(d.toString()),Base64.getEncoder().encodeToString(d.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),value.truth());
                if(!issues.isEmpty())row(errors,value.id(),value.label(),issues,value.expectedReason(),reason,d.checks());
            }
        }
        q.counts().forEach((k,v)->p.setProperty(k,v.toString()));q.extraction().forEach((k,v)->{p.setProperty(k+".tp",Long.toString(v.truePositive));p.setProperty(k+".fp",Long.toString(v.falsePositive));p.setProperty(k+".fn",Long.toString(v.falseNegative));});
        p.setProperty("safePrecision",Double.toString(q.safePrecision()));long safe=q.counts().get("correctSafe"),miss=q.counts().get("expectedSafeMissed");p.setProperty("safeRecall",Double.toString((double)safe/(safe+miss)));
        matrix.forEach((k,v)->p.setProperty("matrix."+k,v.toString()));reasons.forEach((k,v)->p.setProperty("review."+k,v.toString()));save(out.resolve("first-summary.properties"),p);
        Files.writeString(out.resolve("first-decisions.tsv.sha256"),StreamingReference.sha(out.resolve("first-decisions.tsv")));c.rollback();System.out.println("V3 FIRST RESULT: "+q.counts());
    }
    static void guardResources(){
        Runtime r=Runtime.getRuntime();long available=r.maxMemory()-r.totalMemory()+r.freeMemory();
        if(available<32L*1024*1024)throw new IllegalStateException("OOM-risk guard: less than 32MiB heap headroom");
        try{if(Files.getFileStore(OUT).getUsableSpace()<1024L*1024*1024)throw new IllegalStateException("Disk-risk guard: less than 1GiB free");}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private static void clearGenerated(Connection c) throws Exception {new SyntheticLoader().replace(c,new Dataset(Config.standard(SEED,12,16,1,Profile.MIXED),List.of(),List.of(),List.of(),List.of()));c.commit();}
    private static long scalar(Connection c,String sql) throws Exception {try(var s=c.createStatement();var rs=s.executeQuery(sql)){rs.next();return rs.getLong(1);}}
    private static void freeze(Path out) throws Exception {var p=new Properties();try(var paths=Files.walk(Path.of("src/main/java"))){for(Path file:paths.filter(f->f.toString().endsWith(".java")).sorted().toList())p.setProperty(file.toString(),StreamingReference.sha(file));}save(out,p);}
    private static void signal(String kind,String phase) throws Exception {Path req=OUT.resolve(kind+"-"+phase+".request"),ack=OUT.resolve(kind+"-"+phase+".ack");Files.createFile(req);long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(!Files.exists(ack)){if(System.nanoTime()>end)throw new IllegalStateException("Collector missing "+phase);Thread.sleep(20);}}
    private static String snapshot() throws Exception {var b=new StringBuilder();for(String file:List.of("cpu.stat","cpu.max","cpu.pressure","cpuset.cpus.effective","memory.current","memory.peak","memory.max","memory.events")){Path p=Path.of("/sys/fs/cgroup",file);b.append(file).append('=').append(Files.readString(p).trim().replace('\n',';')).append('\n');}b.append("heapUsed=").append(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).append('\n');return b.toString();}
}
