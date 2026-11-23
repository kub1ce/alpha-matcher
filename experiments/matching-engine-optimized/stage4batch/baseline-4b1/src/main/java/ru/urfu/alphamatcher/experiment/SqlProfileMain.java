package ru.urfu.alphamatcher.experiment;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;

/** Finished profiling substage only: unchanged engine, single thread, unrestricted host CPU. */
public final class SqlProfileMain {
    private SqlProfileMain() { }
    private record Index(String name, String table, String columns) { }
    private static final List<Index> INDEXES = List.of(
            new Index("ix_client_fio", "client", "last_name,first_name,middle_name"),
            new Index("ix_contract_party_client_role", "contract_party", "client_id,role_id,contract_id"),
            new Index("ix_contract_party_inn_role", "contract_party", "inn,role_id,contract_id"),
            new Index("ix_operation_status_date", "operation", "status_id,operation_at"));

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!root.getFileName().toString().equals("matching-engine") || args.length != 0)
            throw new IllegalArgumentException("Run without arguments from matching-engine module");
        Path output = root.resolve("performance/runs/sql-" + System.currentTimeMillis());
        Files.createDirectories(output);
        var settings = Config.standard(20261029, 1200, 2400, 10000, Profile.MIXED);
        long generationStart = System.nanoTime();
        Dataset data = new SyntheticGenerator().generate(settings);
        Properties environment = new Properties();
        environment.setProperty("generationNs", Long.toString(System.nanoTime() - generationStart));
        environment.setProperty("java", System.getProperty("java.runtime.version"));
        environment.setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        environment.setProperty("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        environment.setProperty("config", settings.toString());
        environment.setProperty("cpuRestriction", "NONE: Windows JVM and Docker MySQL; NOT a 2 CPU measurement");
        environment.setProperty("measurement", "instrumented 10000 full pass; 3 uninstrumented 1000-operation repeats; warmup 1000");
        try (var paths = Files.walk(root.resolve("src/main/java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList())
                environment.setProperty("source." + root.relativize(path), hash(Files.readString(path)));
        }
        try (Connection connection = ExperimentDatabase.connect(); Connection observer = ExperimentDatabase.connect()) {
            environment.setProperty("mysql", connection.getMetaData().getDatabaseProductVersion());
            environment.setProperty("driver", connection.getMetaData().getDriverVersion());
            environment.setProperty("schemaSha256", hash(Files.readString(root.resolve("sql/001_schema.sql"))));
            save(output.resolve("environment.properties"), environment);
            assertIndexesAbsent(connection);
            long thread = scalar(observer, "SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID="
                    + scalar(connection, "SELECT CONNECTION_ID()"));
            if (thread == 0) throw new IllegalStateException("Performance Schema app thread missing");
            dump(observer, output.resolve("server-settings.tsv"), "SELECT @@performance_schema,@@innodb_page_size,@@innodb_buffer_pool_size,@@transaction_isolation");
            dump(observer, output.resolve("original-indexes.tsv"), "SELECT TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,NON_UNIQUE FROM information_schema.statistics WHERE TABLE_SCHEMA='matching_engine_exp' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX");
            List<Decision> baseline = null;
            for (String configuration : List.of("original", "ix_client_fio", "ix_contract_party_client_role", "ix_contract_party_inn_role", "ix_operation_status_date", "all")) {
                Path folder = output.resolve(configuration);
                Files.createDirectories(folder);
                Properties summary = new Properties();
                try {
                    // Only our own explicitly named experimental indexes are created/dropped.
                    connection.commit();
                    long ddlStart = System.nanoTime();
                    for (Index index : INDEXES) if (configuration.equals("all") || configuration.equals(index.name()))
                        execute(connection, "CREATE INDEX " + index.name() + " ON " + index.table() + "(" + index.columns() + ")");
                    summary.setProperty("indexBuildNs", Long.toString(System.nanoTime() - ddlStart));
                    long loadStart = System.nanoTime();
                    new SyntheticLoader().replace(connection, data);
                    connection.commit();
                    summary.setProperty("replaceAndCommitNs", Long.toString(System.nanoTime() - loadStart));
                    for (String table : List.of("client", "contract", "contract_party", "operation", "operation_party"))
                        execute(connection, "ANALYZE TABLE " + table);
                    // Stable observer sees freshly committed stats, not its old RR snapshot.
                    observer.rollback();
                    dump(observer, folder.resolve("index-size.tsv"), "SELECT table_name,index_name,stat_name,stat_value,stat_description FROM mysql.innodb_index_stats WHERE database_name='matching_engine_exp' AND stat_name IN ('size','n_leaf_pages') ORDER BY table_name,index_name,stat_name");
                    updateProbe(connection, folder);
                    connection.setReadOnly(true);
                    MatchingEngine engine = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());
                    for (int i = 0; i < 1000; i++) engine.match(connection, data.truth().get(i).id());
                    connection.rollback();
                    try (var repeats = Files.newBufferedWriter(folder.resolve("repeats.tsv"))) {
                        row(repeats, "repeat", "operations", "wallNs", "opsPerSec", "p50Ns", "p95Ns", "p99Ns", "parsingNs", "sqlJdbcNs", "rulesNs");
                        for (int repeat = 1; repeat <= 3; repeat++) {
                            var times = new ArrayList<Long>(); long parse = 0, sql = 0, rules = 0;
                            long start = System.nanoTime();
                            for (int i = 0; i < 1000; i++) {
                                var timed = engine.measure(connection, data.truth().get(i).id());
                                if (baseline != null && !baseline.get(i).equals(timed.decision()))
                                    throw new IllegalStateException("Repeat changed full decision: " + timed.decision().operationId());
                                times.add(timed.totalNanos()); parse += timed.parsingNanos(); sql += timed.sqlNanos(); rules += timed.rulesNanos();
                            }
                            long wall = System.nanoTime() - start;
                            row(repeats, repeat, 1000, wall, 1e12 / wall, percentile(times, .50), percentile(times, .95), percentile(times, .99), parse, sql, rules);
                            connection.rollback();
                        }
                    }
                    JdbcProfile probe = new JdbcProfile(); Connection measured = probe.wrap(connection);
                    Map<String, long[]> before = threadStats(observer, thread);
                    dump(observer, folder.resolve("digests-before.tsv"), digestSql());
                    List<Decision> decisions = new ArrayList<>(); List<Long> latencies = new ArrayList<>();
                    long parse = 0, sql = 0, rules = 0;
                    long start = System.nanoTime();
                    for (var truth : data.truth()) {
                        var timed = engine.measure(measured, truth.id());
                        decisions.add(timed.decision()); latencies.add(timed.totalNanos());
                        parse += timed.parsingNanos(); sql += timed.sqlNanos(); rules += timed.rulesNanos();
                    }
                    long wall = System.nanoTime() - start;
                    Map<String, long[]> after = threadStats(observer, thread);
                    dump(observer, folder.resolve("digests-after.tsv"), digestSql());
                    writeThreadDelta(folder.resolve("server-thread.tsv"), before, after);
                    var quality = new QualityReport();
                    int differences = 0;
                    try (var full = Files.newBufferedWriter(folder.resolve(baseline == null ? "full-decisions.tsv" : "decision-comparison.tsv"));
                         var failures = Files.newBufferedWriter(folder.resolve("quality-errors.tsv"))) {
                        row(full, "operationId", "sha256", baseline == null ? "fullDecisionBase64Utf8" : "equalToOriginal");
                        row(failures, "operationId", "errors");
                        for (int i = 0; i < decisions.size(); i++) {
                            Decision decision = decisions.get(i);
                            var errors = quality.accept(Stage3QualityMain.referenceV3(data.truth().get(i)), decision);
                            if (!errors.isEmpty()) row(failures, decision.operationId(), errors);
                            boolean equal = baseline == null || baseline.get(i).equals(decision);
                            if (!equal) differences++;
                            row(full, decision.operationId(), hash(decision.toString()), baseline == null
                                    ? Base64.getEncoder().encodeToString(decision.toString().getBytes(StandardCharsets.UTF_8)) : equal);
                        }
                    }
                    for (var count : quality.counts().entrySet()) summary.setProperty("quality." + count.getKey(), count.getValue().toString());
                    summary.setProperty("fullDecisionDifferences", Integer.toString(differences));
                    summary.setProperty("wallNs", Long.toString(wall));
                    summary.setProperty("opsPerSec", Double.toString(1e13 / wall));
                    summary.setProperty("p50Ns", Long.toString(percentile(latencies, .50)));
                    summary.setProperty("p95Ns", Long.toString(percentile(latencies, .95)));
                    summary.setProperty("p99Ns", Long.toString(percentile(latencies, .99)));
                    summary.setProperty("parsingNs", Long.toString(parse)); summary.setProperty("sqlJdbcNs", Long.toString(sql)); summary.setProperty("rulesNs", Long.toString(rules));
                    writeProbe(connection, folder, probe);
                    summary.setProperty("appQueries", Long.toString(probe.samples.values().stream().mapToLong(s -> s.executions).sum()));
                    summary.setProperty("jdbcRows", Long.toString(probe.samples.values().stream().mapToLong(s -> s.rows).sum()));
                    connection.rollback(); connection.setReadOnly(false);
                    summary.setProperty("status", differences == 0 && quality.counts().get("wrongSafe") == 0 ? "PASS" : "FAIL");
                    if (baseline == null) baseline = List.copyOf(decisions);
                    save(folder.resolve("summary.properties"), summary);
                    System.out.println(configuration + ": " + summary.getProperty("opsPerSec") + " ops/s, differences=" + differences + ", wrongSafe=" + quality.counts().get("wrongSafe"));
                    if (!summary.getProperty("status").equals("PASS")) throw new IllegalStateException("Quality regression");
                } catch (Exception failure) {
                    summary.setProperty("failure", failure.toString()); save(folder.resolve("summary.properties"), summary); throw failure;
                } finally {
                    connection.rollback(); connection.setReadOnly(false);
                    for (Index index : INDEXES) if (configuration.equals("all") || configuration.equals(index.name()))
                        execute(connection, "DROP INDEX " + index.name() + " ON " + index.table());
                }
            }
            // Restore the stage-1 fixtures for the subsequent real-MySQL regression suite.
            new SyntheticLoader().replace(connection, new Dataset(settings, List.of(), List.of(), List.of(), List.of()));
            connection.commit();
            System.out.println("SQL profile output: " + output);
        }
    }

    private static void assertIndexesAbsent(Connection connection) throws Exception {
        for (Index index : INDEXES) try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.statistics WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND INDEX_NAME=?")) {
            query.setString(1, ExperimentDatabase.CATALOG); query.setString(2, index.table()); query.setString(3, index.name());
            try (var rs = query.executeQuery()) { rs.next(); if (rs.getInt(1) != 0) throw new IllegalStateException("Index already exists: " + index.name()); }
        }
        connection.rollback();
    }
    private static void updateProbe(Connection connection, Path folder) throws Exception {
        // Real indexed-column changes, rolled back before matching; not an application workflow.
        try (var writer = Files.newBufferedWriter(folder.resolve("update-cost.tsv"))) {
            row(writer, "repeat", "table", "rows", "executeNs", "rollbackNs");
            for (int repeat = 1; repeat <= 3; repeat++) for (var entry : Map.of(
                    "client", "last_name=CONCAT(COALESCE(last_name,''),'X')",
                    "contract_party", "client_id=(SELECT MIN(id) FROM client WHERE id>=1000000),inn='9900000000'",
                    "operation", "operation_at=DATE_ADD(operation_at,INTERVAL 1 SECOND)").entrySet()) {
                long start = System.nanoTime(); int rows;
                try (var statement = connection.createStatement()) { rows = statement.executeUpdate("UPDATE " + entry.getKey() + " SET " + entry.getValue() + " WHERE "
                        + (entry.getKey().equals("contract_party") ? "contract_id" : "id") + ">=1000000 ORDER BY " + (entry.getKey().equals("contract_party") ? "contract_id,role_id" : "id") + " LIMIT 1000"); }
                long executed = System.nanoTime(); connection.rollback(); long rolledBack = System.nanoTime();
                row(writer, repeat, entry.getKey(), rows, executed - start, rolledBack - executed);
            }
        }
    }
    static void writeProbe(Connection connection, Path folder, JdbcProfile probe) throws Exception {
        try (var writer = Files.newBufferedWriter(folder.resolve("jdbc-queries.tsv"))) {
            row(writer, "shape", "prepares", "executions", "rows", "prepareNs", "executeNs", "resultAccessNs", "p50ExecuteNs", "p95ExecuteNs", "p99ExecuteNs");
            int i = 0;
            for (var sample : probe.samples.values()) {
                String key = "query-" + ++i;
                row(writer, key, sample.prepares, sample.executions, sample.rows, sample.prepareNanos, sample.executeNanos, sample.resultNanos,
                        percentile(sample.latencies, .5), percentile(sample.latencies, .95), percentile(sample.latencies, .99));
                Files.writeString(folder.resolve(key + ".sql"), sample.sql);
                Files.writeString(folder.resolve(key + "-parameters.txt"), sample.parameters.toString());
                try (var query = connection.prepareStatement("EXPLAIN ANALYZE " + sample.sql)) {
                    for (int p = 0; p < sample.parameters.size(); p++) query.setObject(p + 1, sample.parameters.get(p));
                    try (var rs = query.executeQuery(); var plan = Files.newBufferedWriter(folder.resolve(key + "-explain.txt"))) {
                        while (rs.next()) { plan.write(rs.getString(1)); plan.newLine(); }
                    }
                }
            }
        }
    }
    private static String digestSql() {
        return "SELECT COALESCE(SCHEMA_NAME,''),DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED,SUM_ROWS_SENT FROM performance_schema.events_statements_summary_by_digest WHERE COUNT_STAR>0 ORDER BY DIGEST";
    }
    static Map<String, long[]> threadStats(Connection observer, long thread) throws Exception {
        Map<String, long[]> result = new LinkedHashMap<>();
        try (var statement = observer.createStatement(); var rs = statement.executeQuery("SELECT EVENT_NAME,COUNT_STAR,SUM_TIMER_WAIT FROM performance_schema.events_statements_summary_by_thread_by_event_name WHERE THREAD_ID=" + thread + " AND COUNT_STAR>0 ORDER BY EVENT_NAME")) {
            while (rs.next()) result.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
        }
        return result;
    }
    static void writeThreadDelta(Path path, Map<String, long[]> before, Map<String, long[]> after) throws Exception {
        try (var writer = Files.newBufferedWriter(path)) {
            row(writer, "event", "count", "serverTimerPicoseconds");
            for (var entry : after.entrySet()) {
                long[] initial = before.getOrDefault(entry.getKey(), new long[2]);
                if (entry.getValue()[0] != initial[0]) row(writer, entry.getKey(), entry.getValue()[0] - initial[0], entry.getValue()[1] - initial[1]);
            }
        }
    }
    private static long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) { if (!rs.next()) throw new IllegalStateException("Missing scalar"); return rs.getLong(1); }
    }
    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    static void dump(Connection connection, Path path, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql); var writer = Files.newBufferedWriter(path)) {
            while (rs.next()) {
                Object[] values = new Object[rs.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) values[i] = rs.getString(i + 1);
                row(writer, values);
            }
        }
    }
    static long percentile(List<Long> samples, double fraction) {
        if (samples.isEmpty()) return 0;
        var sorted = new ArrayList<>(samples); sorted.sort(Long::compare);
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1));
    }
    static String hash(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    static void save(Path path, Properties values) throws Exception {
        try (var writer = Files.newBufferedWriter(path)) { values.store(writer, "SQL profiling substage; no engine optimization"); }
    }
    static void row(BufferedWriter writer, Object... values) throws java.io.IOException {
        for (int i = 0; i < values.length; i++) { if (i > 0) writer.write('\t'); writer.write(String.valueOf(values[i]).replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")); }
        writer.newLine();
    }
}

