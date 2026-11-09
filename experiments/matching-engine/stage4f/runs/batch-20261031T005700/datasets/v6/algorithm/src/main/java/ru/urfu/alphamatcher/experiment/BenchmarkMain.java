package ru.urfu.alphamatcher.experiment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;

/** Single worker / one persistent JDBC connection. Not a write-path or CPU-scaling benchmark. */
public final class BenchmarkMain {
    private BenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Передайте путь config/*.properties внутри модуля");
        Path configPath=Path.of(args[0]).toAbsolutePath().normalize();
        Path root=configPath.getParent().getParent();
        if(!root.getFileName().toString().equals("matching-engine") || !configPath.getParent().getFileName().toString().equals("config"))
            throw new IllegalArgumentException("Конфигурация должна находиться в experiments/matching-engine/config");
        Properties settings=new Properties();
        try(var reader=Files.newBufferedReader(configPath)) { settings.load(reader); }
        Config defaults=Config.standard(Long.parseLong(settings.getProperty("seed")),Integer.parseInt(settings.getProperty("clients")),
                Integer.parseInt(settings.getProperty("contracts")),Integer.parseInt(settings.getProperty("operations")),Profile.valueOf(settings.getProperty("profile")));
        var weights=new java.util.EnumMap<Scenario,Integer>(Scenario.class); weights.putAll(defaults.weights());
        for(Scenario scenario:Scenario.values()) if(settings.containsKey("weight."+scenario))
            weights.put(scenario,Integer.parseInt(settings.getProperty("weight."+scenario)));
        Config config=new Config(defaults.seed(),defaults.clients(),defaults.contracts(),defaults.operations(),defaults.profile(),weights);
        int warmups=Integer.parseInt(settings.getProperty("warmups","1")), repeats=Integer.parseInt(settings.getProperty("repeats","3"));
        if(warmups<0 || repeats<1) throw new IllegalArgumentException("Нужны warmups>=0 и repeats>=1");
        Path output=root.resolve("runs").resolve(configPath.getFileName().toString().replace(".properties","")+"-"+System.currentTimeMillis());
        Files.createDirectories(output);
        Files.copy(configPath,output.resolve("config.properties"));
        long generationStart=System.nanoTime();
        Dataset dataset=new SyntheticGenerator().generate(config);
        long generationNanos=System.nanoTime()-generationStart;
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(var value:dataset.clients()) hash.update(value.toString().getBytes(StandardCharsets.UTF_8));
        for(var value:dataset.contracts()) hash.update(value.toString().getBytes(StandardCharsets.UTF_8));
        for(var value:dataset.payments()) hash.update(value.toString().getBytes(StandardCharsets.UTF_8));
        for(var value:dataset.truth()) hash.update(value.toString().getBytes(StandardCharsets.UTF_8));
        Properties environment=new Properties();
        environment.setProperty("dataset.sha256",HexFormat.of().formatHex(hash.digest()));
        environment.setProperty("generation.nanos",Long.toString(generationNanos));
        environment.setProperty("workers","1"); environment.setProperty("connection.pool.size","0 (no pool, one reused connection)");
        environment.setProperty("jvm",System.getProperty("java.vm.name")+" "+System.getProperty("java.runtime.version"));
        environment.setProperty("jvm.args",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
        environment.setProperty("os",System.getProperty("os.name")+" "+System.getProperty("os.arch")+" "+System.getProperty("os.version"));
        environment.setProperty("jvm.availableProcessors",Integer.toString(Runtime.getRuntime().availableProcessors()));
        environment.setProperty("seed",Long.toString(config.seed())); environment.setProperty("weights",config.weights().toString());
        environment.setProperty("transaction","one REPEATABLE_READ read-only snapshot per pass; no writes");
        try(var connection=ExperimentDatabase.connect()) {
            long loadStart=System.nanoTime();
            new SyntheticLoader().replace(connection,dataset);
            connection.commit();
            environment.setProperty("load.nanos",Long.toString(System.nanoTime()-loadStart));
            environment.setProperty("mysql",connection.getMetaData().getDatabaseProductVersion());
            environment.setProperty("jdbc",connection.getMetaData().getDriverVersion());
            try(var statement=connection.createStatement()) {
                statement.execute("ANALYZE TABLE client, contract, contract_party, operation, operation_party");
                try(var rs=statement.executeQuery("SELECT SUM(data_length+index_length) FROM information_schema.tables WHERE table_schema='matching_engine_exp'")) {
                    rs.next(); environment.setProperty("db.allocatedBytes",rs.getString(1));
                }
                for(String table:List.of("client","contract","operation")) {
                    try(var rs=statement.executeQuery("SELECT COUNT(*) FROM "+table)) { rs.next(); environment.setProperty("db."+table+".rows",rs.getString(1)); }
                }
                try(var rs=statement.executeQuery("SELECT @@innodb_buffer_pool_size,@@character_set_database,@@collation_database")) {
                    rs.next(); environment.setProperty("mysql.bufferPoolBytes",rs.getString(1)); environment.setProperty("mysql.charset",rs.getString(2)); environment.setProperty("mysql.collation",rs.getString(3));
                }
            }
            connection.rollback();
        }
        try(var writer=Files.newBufferedWriter(output.resolve("environment.properties"))) { environment.store(writer,"Measured environment; no connection credentials"); }
        try(var writer=Files.newBufferedWriter(output.resolve("oracle.csv"))) {
            row(writer,"seed","id","scenario","expectedStatus","safeContract","knownTarget","inns","invalidInns","names","numbers","organizations","thirdParty");
            for(var truth:dataset.truth()) row(writer,config.seed(),truth.id(),truth.scenario(),truth.expectedStatus(),truth.safeContract(),truth.knownTarget(),truth.inns(),truth.invalidInns(),truth.names(),truth.numbers(),truth.organizations(),truth.thirdParty());
        }
        var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
        try(var connection=ExperimentDatabase.connect();
            var summary=Files.newBufferedWriter(output.resolve("measurements.csv"));
            var timings=Files.newBufferedWriter(output.resolve("timings.csv"));
            var decisions=Files.newBufferedWriter(output.resolve("decisions.csv"));
            var mismatches=Files.newBufferedWriter(output.resolve("mismatches.csv"))) {
            connection.setReadOnly(true);
            var counter=new QueryCounter(); Connection measured=counter.wrap(connection);
            row(summary,"pass","kind","operations","totalNs","opsPerSecond","p50Ns","p95Ns","p99Ns","parsingNs","sqlNs","rulesNs","queries");
            row(timings,"seed","pass","id","totalNs","parsingNs","sqlNs","rulesNs");
            row(decisions,"seed","id","scenario","expectedStatus","actualStatus","expectedSafe","actualSafe","knownTarget","candidates","failedChecks");
            row(mismatches,"seed","pass","id","scenario","errors","expectedStatus","actualStatus","expectedSafe","actualSafe","knownTarget","candidates","expectedFeatures","actualFeatures");
            for(int pass=-warmups;pass<repeats;pass++) {
                boolean warmup=pass<0;
                long beforeQueries=counter.queries(), start=System.nanoTime();
                List<MatchingEngine.TimedDecision> results=new ArrayList<>(dataset.payments().size());
                for(var payment:dataset.payments()) results.add(engine.measure(measured,payment.id()));
                long elapsed=System.nanoTime()-start, queries=counter.queries()-beforeQueries;
                connection.rollback();
                long parsing=0,sql=0,rules=0; long[] latencies=new long[results.size()];
                for(int i=0;i<results.size();i++) {
                    var result=results.get(i); latencies[i]=result.totalNanos(); parsing+=result.parsingNanos(); sql+=result.sqlNanos(); rules+=result.rulesNanos();
                    row(timings,config.seed(),pass,dataset.payments().get(i).id(),result.totalNanos(),result.parsingNanos(),result.sqlNanos(),result.rulesNanos());
                }
                java.util.Arrays.sort(latencies);
                row(summary,pass,warmup?"warmup":"measured",results.size(),elapsed,results.size()*1e9/elapsed,
                        percentile(latencies,0.50),percentile(latencies,0.95),percentile(latencies,0.99),parsing,sql,rules,queries);
                var quality=new QualityReport();
                for(int i=0;i<results.size();i++) {
                    var truth=dataset.truth().get(i); var actual=results.get(i).decision();
                    var errors=quality.accept(truth,actual);
                    if(pass==0) row(decisions,config.seed(),truth.id(),truth.scenario(),truth.expectedStatus(),actual.status(),truth.safeContract(),actual.proposedContractId(),truth.knownTarget(),actual.candidateIds(),actual.checks().stream().filter(check->!check.passed()).toList());
                    if(!errors.isEmpty()) row(mismatches,config.seed(),pass,truth.id(),truth.scenario(),errors,truth.expectedStatus(),actual.status(),truth.safeContract(),actual.proposedContractId(),truth.knownTarget(),actual.candidateIds(),truth,actual.features());
                }
                writeQuality(output.resolve("quality-"+pass+".properties"),quality);
                new PrintWriter(System.out,true,StandardCharsets.UTF_8).printf(Locale.ROOT,"%s pass=%d %s n=%d seconds=%.6f ops/s=%.3f correctSafe=%d wrongSafe=%d statusMismatch=%d%n",
                        configPath.getFileName(),pass,warmup?"warmup":"measured",results.size(),elapsed/1e9,results.size()*1e9/elapsed,
                        quality.counts().get("correctSafe"),quality.counts().get("wrongSafe"),quality.counts().get("statusMismatch"));
            }
        }
        System.out.println("Results: "+output);
    }

    static long percentile(long[] sorted,double fraction) { return sorted[(int)Math.ceil(sorted.length*fraction)-1]; }
    private static void writeQuality(Path path,QualityReport quality) throws IOException {
        Properties values=new Properties();
        quality.counts().forEach((key,value)->values.setProperty(key,value.toString()));
        values.setProperty("safePrecision",Double.toString(quality.safePrecision()));
        values.setProperty("reviewShare",Double.toString((double)quality.counts().get("review")/quality.counts().get("total")));
        quality.extraction().forEach((field,result)->{
            values.setProperty(field+".tp",Long.toString(result.truePositive)); values.setProperty(field+".fp",Long.toString(result.falsePositive));
            values.setProperty(field+".fn",Long.toString(result.falseNegative)); values.setProperty(field+".precision",Double.toString(result.precision())); values.setProperty(field+".recall",Double.toString(result.recall()));
        });
        quality.scenarioErrors().forEach((scenario,count)->values.setProperty("mismatch."+scenario,count.toString()));
        try(var writer=Files.newBufferedWriter(path)) { values.store(writer,"Quality vs independent oracle; NaN means no denominator"); }
    }
    private static void row(BufferedWriter writer,Object... values) throws IOException {
        for(int i=0;i<values.length;i++) {
            if(i>0) writer.write(',');
            writer.write('"'); writer.write(values[i]==null?"":values[i].toString().replace("\"","\"\"").replace('\n',' ').replace('\r',' ')); writer.write('"');
        }
        writer.newLine();
    }
}
