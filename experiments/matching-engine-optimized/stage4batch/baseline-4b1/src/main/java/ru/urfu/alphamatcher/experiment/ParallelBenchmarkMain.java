package ru.urfu.alphamatcher.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;
import static ru.urfu.alphamatcher.experiment.SqlProfileMain.*;

/** Only 1k/10k on the shared two-CPU cpuset. Never reads v3 or runs a 100k batch. */
public final class ParallelBenchmarkMain {
    private ParallelBenchmarkMain() { }
    private static final Path OUT=Path.of("/results");
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        try { run(); }
        catch(Throwable failure) { Files.writeString(OUT.resolve("FAILED.txt"),failure.toString());throw failure; }
    }
    private static void run() throws Exception {
        Properties env=new Properties();env.setProperty("java",System.getProperty("java.runtime.version"));env.setProperty("os",System.getProperty("os.name"));
        env.setProperty("jvmArgs",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
        env.setProperty("availableProcessors",Integer.toString(Runtime.getRuntime().availableProcessors()));
        String cpus=Files.readString(Path.of("/sys/fs/cgroup/cpuset.cpus.effective")).trim();env.setProperty("cpuset",cpus);
        save(OUT.resolve("java-environment.properties"),env);Files.writeString(OUT.resolve("java-startup-cgroup.txt"),snapshot());
        if(!List.of("0-1","0,1").contains(cpus)||Runtime.getRuntime().availableProcessors()!=2)
            throw new IllegalStateException("Common 0,1 cpuset / JVM 2 CPU not effective");
        Properties source=new Properties();
        try(var paths=Files.walk(Path.of("src/main/java"))) {for(Path file:paths.filter(p->p.toString().endsWith(".java")).sorted().toList())source.setProperty(file.toString(),hash(Files.readString(file)));}
        save(OUT.resolve("sources.properties"),source);
        try(var loader=ExperimentDatabase.connect()) {
            Properties server=new Properties();server.setProperty("mysql",loader.getMetaData().getDatabaseProductVersion());server.setProperty("driver",loader.getMetaData().getDriverVersion());
            dump(loader,OUT.resolve("mysql-variables.tsv"),"SELECT @@version,@@transaction_isolation,@@innodb_buffer_pool_size,@@max_connections,@@performance_schema,@@innodb_page_size");save(OUT.resolve("database.properties"),server);
            for(int n:List.of(1000,10000)) {
                long scenarioStart=System.nanoTime();Path dir=OUT.resolve("mixed-"+n);Files.createDirectories(dir);
                var config=Config.standard(20261029,n==1000?120:1200,n==1000?240:2400,n,Profile.MIXED);
                Properties setup=new Properties();setup.setProperty("config",config.toString());
                long start=System.nanoTime();var data=new SyntheticGenerator().generate(config);setup.setProperty("generationNs",Long.toString(System.nanoTime()-start));
                start=System.nanoTime();new SyntheticLoader().replace(loader,data);loader.commit();setup.setProperty("loadAndCommitNs",Long.toString(System.nanoTime()-start));
                try(var s=loader.createStatement()) {for(String table:List.of("client","contract","contract_party","operation","operation_party"))s.execute("ANALYZE TABLE "+table);}
                dump(loader,dir.resolve("table-sizes.tsv"),"SELECT TABLE_NAME,TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE TABLE_SCHEMA='matching_engine_exp' ORDER BY TABLE_NAME");
                var ids=data.truth().stream().map(Truth::id).toList();var truth=new HashMap<Long,Truth>();data.truth().forEach(t->truth.put(t.id(),Stage3QualityMain.referenceV3(t)));
                var baseline=new HashMap<Long,Decision>();start=System.nanoTime();
                try(var session=MatchingEngine.openReadOnlySession()) {for(long id:ids)baseline.put(id,session.match(id));}
                setup.setProperty("serialReferenceNs",Long.toString(System.nanoTime()-start));
                try(var writer=Files.newBufferedWriter(dir.resolve("serial-reference.tsv"))) {
                    row(writer,"id","sha256","fullDecisionBase64");
                    for(long id:ids){Decision decision=baseline.get(id);row(writer,id,hash(decision.toString()),Base64.getEncoder().encodeToString(decision.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
                }
                for(int workers:List.of(1,2,4,8,16)) {
                    for(int warm=1;warm<=2;warm++)pass(dir,"m"+n+"-w"+workers+"-warmup"+warm,workers,ids.subList(0,Math.min(1000,n)),baseline,truth,true);
                    for(int repeat=1;repeat<=2;repeat++)pass(dir,"m"+n+"-w"+workers+"-repeat"+repeat,workers,ids,baseline,truth,false);
                }
                setup.setProperty("wholeScenarioNs",Long.toString(System.nanoTime()-scenarioStart));save(dir.resolve("setup.properties"),setup);
            }
            var settings=Config.standard(20261029,120,240,1,Profile.MIXED);
            new SyntheticLoader().replace(loader,new Dataset(settings,List.of(),List.of(),List.of(),List.of()));loader.commit();
        }
        Files.writeString(OUT.resolve("COMPLETED.txt"),"1k/10k only; v3 unopened; no 100k claim\n");
    }
    private static void pass(Path dir,String phase,int workers,List<Long> ids,HashMap<Long,Decision> baseline,HashMap<Long,Truth> truth,boolean warmup) throws Exception {
        Path folder=dir.resolve(phase);Files.createDirectories(folder);
        signal("start",phase);
        Files.writeString(folder.resolve("java-before.txt"),snapshot());
        var quality=new QualityReport();var latencies=new ArrayList<Long>();
        long[] phases=new long[3];var differences=new AtomicInteger();var terminal=new AtomicInteger();
        Properties values=new Properties();values.setProperty("warmup",Boolean.toString(warmup));values.setProperty("N",Integer.toString(ids.size()));values.setProperty("workers",Integer.toString(workers));
        var config=new ParallelMatcher.Config(workers,16,Duration.ofMinutes(5),Duration.ofSeconds(20),true);
        try(var rows=Files.newBufferedWriter(folder.resolve("outcomes.tsv"));var mismatches=Files.newBufferedWriter(folder.resolve("mismatches.tsv"))) {
            row(rows,"id","technicalState","attempted","equalFullDecision","sha256","totalNs","diagnostic");row(mismatches,"id","issues","fullDecisionBase64");
            var summary=new ParallelMatcher().run(ids,config,new ParallelMatcher.Cancellation(),o->{
                try {
                    boolean equal=false;String sha="";List<String> issues=List.of();
                    if(o.state()==ParallelMatcher.State.SUCCESS) {
                        var timed=o.result();var decision=timed.decision();equal=baseline.get(o.id()).equals(decision);
                        if(!equal)differences.incrementAndGet();
                        issues=quality.accept(truth.get(o.id()),decision);sha=hash(decision.toString());latencies.add(timed.totalNanos());
                        phases[0]+=timed.parsingNanos();phases[1]+=timed.sqlNanos();phases[2]+=timed.rulesNanos();
                        if(!equal||!issues.isEmpty())row(mismatches,o.id(),issues,Base64.getEncoder().encodeToString(decision.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    } else terminal.incrementAndGet();
                    row(rows,o.id(),o.state(),o.attempted(),equal,sha,o.elapsedNanos(),o.diagnostic());
                } catch(Exception error) {throw new IllegalStateException("Result sink failed",error);}
            });
            values.setProperty("summary",summary.toString());values.setProperty("processingNs",Long.toString(summary.processingNanos()));values.setProperty("fullBatchNs",Long.toString(summary.totalNanos()));
            values.setProperty("fullBatchOpsPerSec",Double.toString(ids.size()*1e9/summary.totalNanos()));values.setProperty("processingOpsPerSec",Double.toString(ids.size()*1e9/summary.processingNanos()));
            values.setProperty("sessionsOpened",Integer.toString(summary.sessionsOpened()));values.setProperty("peakSessions",Integer.toString(summary.peakSessions()));values.setProperty("setupSumNs",Long.toString(summary.setupNanos()));
            values.setProperty("selects",Long.toString(summary.selects()));values.setProperty("sqlCountsComplete",Boolean.toString(summary.sqlCountsComplete()));
            values.setProperty("errors",Integer.toString(summary.errors()));values.setProperty("cancelled",Integer.toString(summary.cancelled()));values.setProperty("timedOut",Integer.toString(summary.timedOut()));
            values.setProperty("p50Ns",Long.toString(percentile(latencies,.5)));values.setProperty("p95Ns",Long.toString(percentile(latencies,.95)));values.setProperty("p99Ns",Long.toString(percentile(latencies,.99)));
            values.setProperty("parsingSumNs",Long.toString(phases[0]));values.setProperty("sqlJdbcSumNs",Long.toString(phases[1]));values.setProperty("rulesSumNs",Long.toString(phases[2]));
            values.setProperty("fullDecisionDifferences",Integer.toString(differences.get()));quality.counts().forEach((k,v)->values.setProperty("quality."+k,v.toString()));
            boolean pass=terminal.get()==0&&differences.get()==0&&quality.counts().get("wrongSafe")==0&&quality.counts().get("statusMismatch")==0&&quality.counts().get("missedTargets")==0&&summary.sqlCountsComplete();
            values.setProperty("status",pass?"PASS":"FAIL");
            Files.writeString(folder.resolve("java-after.txt"),snapshot());save(folder.resolve("summary.properties"),values);signal("end",phase);
            System.out.println(phase+": "+values.getProperty("fullBatchOpsPerSec")+" ops/s; differences="+differences+"; SELECT="+summary.selects());
            if(!pass)throw new IllegalStateException("Quality/protocol failure: "+phase);
        } catch(Throwable failure) {values.setProperty("failure",failure.toString());save(folder.resolve("summary.properties"),values);throw failure;}
    }
    private static void signal(String type,String phase) throws Exception {
        Path request=OUT.resolve(type+"-"+phase+".request"),ack=OUT.resolve(type+"-"+phase+".ack");Files.createFile(request);
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(!Files.exists(ack)){if(System.nanoTime()>deadline)throw new IllegalStateException("Host cgroup collector did not acknowledge "+request);Thread.sleep(20);}
    }
    private static String snapshot() throws Exception {
        StringBuilder text=new StringBuilder();
        for(String item:List.of("cpu.stat","cpu.max","cpuset.cpus.effective","memory.current","memory.peak","memory.max","memory.events")) {
            Path path=Path.of("/sys/fs/cgroup",item);text.append(item).append('=').append(Files.isReadable(path)?Files.readString(path).trim().replace('\n',';'):"UNAVAILABLE").append('\n');
        }
        for(String line:Files.readAllLines(Path.of("/proc/self/status")))if(line.startsWith("Cpus_allowed"))text.append(line).append('\n');
        return text.toString();
    }
}
