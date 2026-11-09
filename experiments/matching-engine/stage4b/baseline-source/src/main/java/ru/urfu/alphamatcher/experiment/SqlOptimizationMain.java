package ru.urfu.alphamatcher.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;
import static ru.urfu.alphamatcher.experiment.SqlProfileMain.*;

/** Sequential SQL optimization experiment; no pool, threads, cache or final-link writes. */
public final class SqlOptimizationMain {
    private SqlOptimizationMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("Measure", "Control").contains(args[0])) throw new IllegalArgumentException("Measure | Control");
        Path root = Path.of("").toAbsolutePath();
        if (!root.getFileName().toString().equals("matching-engine")) throw new IllegalArgumentException("Run from module");
        verifyControlHashes(root);
        if (args[0].equals("Control")) { control(root); return; }
        Path out = root.resolve("performance/runs/optimization-" + System.currentTimeMillis());
        Files.createDirectories(out); metadata(out);
        try (var loader = ExperimentDatabase.connect(); var observer = ExperimentDatabase.connect()) {
            for (int n : List.of(1000, 10000)) {
                var config = Config.standard(20261029, n == 1000 ? 120 : 1200, n == 1000 ? 240 : 2400, n, Profile.MIXED);
                Path folder = out.resolve("mixed-" + n); Files.createDirectories(folder);
                long start = System.nanoTime(); var data = new SyntheticGenerator().generate(config);
                Properties setup = new Properties(); setup.setProperty("config", config.toString());
                setup.setProperty("generationNs", Long.toString(System.nanoTime() - start));
                start = System.nanoTime(); new SyntheticLoader().replace(loader, data); loader.commit();
                setup.setProperty("loadAndCommitNs", Long.toString(System.nanoTime() - start));
                try (var query = loader.createStatement()) {
                    for (String table : List.of("client", "contract", "contract_party", "operation", "operation_party")) query.execute("ANALYZE TABLE " + table);
                }
                save(folder.resolve("setup.properties"), setup);
                List<Decision> baseline = null;
                for (String mode : List.of("baseline-before", "sql-only", "owned-session", "baseline-after")) {
                    // Two independent snapshots warm up the same 1000 (or fewer) operations.
                    for (int i = 1; i <= 2; i++) {
                        var result = pass(folder.resolve(mode + "-warmup-" + i), mode, false, data.truth().subList(0, Math.min(1000, n)), baseline, observer);
                        if (baseline == null && n == 1000) baseline = result;
                    }
                    for (int repeat = 1; repeat <= 2; repeat++) {
                        var result = pass(folder.resolve(mode + "-repeat-" + repeat), mode, false, data.truth(), baseline, observer);
                        if (baseline == null) baseline = result;
                    }
                    pass(folder.resolve(mode + "-instrumented"), mode, true, data.truth(), baseline, observer);
                }
                System.out.println("Finished comparisons: " + folder);
            }
            // Fresh temporary database only; leave fixtures intact for post-run tests.
            var emptyConfig = Config.standard(20261029, 120, 240, 1, Profile.MIXED);
            new SyntheticLoader().replace(loader, new Dataset(emptyConfig, List.of(), List.of(), List.of(), List.of()));
            loader.commit();
        }
        System.out.println("Optimization evidence: " + out);
    }

    private static List<Decision> pass(Path folder, String mode, boolean instrumented, List<Truth> truths,
                                       List<Decision> baseline, Connection observer) throws Exception {
        Files.createDirectories(folder);
        Properties summary = new Properties(); summary.setProperty("mode", mode); summary.setProperty("instrumented", Boolean.toString(instrumented));
        JdbcProfile probe = new JdbcProfile();
        boolean owned = mode.equals("owned-session");
        boolean oldSql = mode.startsWith("baseline");
        try (Connection raw = owned ? null : ExperimentDatabase.connect();
             var session = owned ? MatchingEngine.openReadOnlySession(instrumented ? probe : null) : null) {
            Connection connection = owned ? null : instrumented ? probe.wrap(raw) : raw;
            if (raw != null) raw.setReadOnly(true);
            long connectionId;
            if (owned) connectionId = session.connectionId();
            else try (var query = raw.createStatement(); var rs = query.executeQuery("SELECT CONNECTION_ID()")) { rs.next(); connectionId = rs.getLong(1); }
            long threadId;
            try (var query = observer.createStatement(); var rs = query.executeQuery("SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=" + connectionId)) { rs.next(); threadId = rs.getLong(1); }
            var before = threadStats(observer, threadId);
            var engine = new MatchingEngine(oldSql ? JdbcMatchingRepository.baselineForComparison() : new JdbcMatchingRepository(), new PurposeParser());
            var decisions = new ArrayList<Decision>(); var latencies = new ArrayList<Long>();
            long parse = 0, sql = 0, rules = 0;
            long start = System.nanoTime();
            for (Truth truth : truths) {
                var result = owned ? session.measure(truth.id()) : engine.measure(connection, truth.id());
                decisions.add(result.decision()); latencies.add(result.totalNanos());
                parse += result.parsingNanos(); sql += result.sqlNanos(); rules += result.rulesNanos();
            }
            long wall = System.nanoTime() - start;
            var after = threadStats(observer, threadId);
            writeThreadDelta(folder.resolve("server-thread.tsv"), before, after);
            var quality = new QualityReport(); int differences = 0;
            try (var writer = Files.newBufferedWriter(folder.resolve("decisions.tsv")); var errors = Files.newBufferedWriter(folder.resolve("errors.tsv"))) {
                row(writer, "id", "sha256", "equal", "fullDecisionBase64Utf8"); row(errors, "id", "errors");
                for (int i = 0; i < truths.size(); i++) {
                    var decision = decisions.get(i);
                    var issues = quality.accept(Stage3QualityMain.referenceV3(truths.get(i)), decision);
                    if (!issues.isEmpty()) row(errors, decision.operationId(), issues);
                    boolean equal = baseline == null || baseline.get(i).equals(decision);
                    if (!equal) differences++;
                    row(writer, decision.operationId(), hash(decision.toString()), equal,
                            Base64.getEncoder().encodeToString(decision.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            for (var entry : quality.counts().entrySet()) summary.setProperty("quality." + entry.getKey(), entry.getValue().toString());
            summary.setProperty("N", Integer.toString(truths.size())); summary.setProperty("wallNs", Long.toString(wall));
            summary.setProperty("opsPerSec", Double.toString(truths.size() * 1e9 / wall));
            summary.setProperty("p50Ns", Long.toString(percentile(latencies, .5))); summary.setProperty("p95Ns", Long.toString(percentile(latencies, .95))); summary.setProperty("p99Ns", Long.toString(percentile(latencies, .99)));
            summary.setProperty("parsingNs", Long.toString(parse)); summary.setProperty("sqlJdbcNs", Long.toString(sql)); summary.setProperty("rulesNs", Long.toString(rules));
            summary.setProperty("fullDecisionDifferences", Integer.toString(differences));
            long selects = after.getOrDefault("statement/sql/select", new long[2])[0] - before.getOrDefault("statement/sql/select", new long[2])[0];
            summary.setProperty("serverSelects", Long.toString(selects));
            if (instrumented) {
                // EXPLAIN executes SELECT: release the previous dataset's observer RR snapshot.
                observer.rollback();
                writeProbe(observer, folder, probe);
                observer.rollback();
                summary.setProperty("appSelects", Long.toString(probe.samples.values().stream().mapToLong(s -> s.executions).sum()));
                summary.setProperty("jdbcRows", Long.toString(probe.samples.values().stream().mapToLong(s -> s.rows).sum()));
            }
            boolean valid = differences == 0 && quality.counts().get("wrongSafe") == 0 && quality.counts().get("statusMismatch") == 0 && quality.counts().get("missedTargets") == 0;
            summary.setProperty("status", valid ? "PASS" : "FAIL"); save(folder.resolve("summary.properties"), summary);
            if (raw != null) raw.rollback();
            System.out.println(folder.getParent().getFileName() + "/" + folder.getFileName() + ": " + summary.getProperty("opsPerSec") + " ops/s; differences=" + differences + "; selects=" + selects);
            if (!valid) throw new IllegalStateException("Regression, inspect " + folder);
            return List.copyOf(decisions);
        } catch (Exception failure) {
            summary.setProperty("failure", failure.toString()); save(folder.resolve("summary.properties"), summary); throw failure;
        }
    }

    private static void verifyControlHashes(Path root) throws Exception {
        Path corpus = root.resolve("stage3/corpora");
        for (String line : Files.readAllLines(corpus.resolve("v2-SHA256SUMS"))) {
            String[] parts = line.replace("\uFEFF", "").split("  ", 2);
            if (!hash(Files.readString(corpus.resolve(parts[1]))).equalsIgnoreCase(parts[0])) throw new IllegalStateException("Frozen v2 changed: " + parts[1]);
        }
    }
    private static void metadata(Path out) throws Exception {
        Properties values = new Properties();
        values.setProperty("java", System.getProperty("java.runtime.version")); values.setProperty("os", System.getProperty("os.name"));
        values.setProperty("cpu", "UNRESTRICTED; NOT a two CPU experiment");
        try (var stream = Files.walk(Path.of("src/main/java"))) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).sorted().toList()) values.setProperty(file.toString(), hash(Files.readString(file)));
        }
        try (var c = ExperimentDatabase.connect()) { values.setProperty("mysql", c.getMetaData().getDatabaseProductVersion()); values.setProperty("driver", c.getMetaData().getDriverVersion()); }
        save(out.resolve("source-and-environment.properties"), values);
    }
    private static void control(Path root) throws Exception {
        // Create marker first and refuse a second 'first opening'. Historical result is never overwritten.
        Path marker = root.resolve("performance/control-v2-first-opening.properties");
        if (Files.exists(marker)) throw new IllegalStateException("v2 already opened; use the preserved result, not a new first opening");
        Path out = root.resolve("performance/runs/control-v2-" + System.currentTimeMillis()); Files.createDirectories(out);
        Files.writeString(marker, "output=" + out + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        metadata(out);
        Path corpus = root.resolve("stage3/corpora");
        var cases = Stage3Corpus.read(corpus.resolve("final-v2.tsv"));
        try (var c = ExperimentDatabase.connect()) {
            Stage3Corpus.load(c, corpus.resolve("world-v2.sql"), cases);
            try (var statement = c.createStatement()) {
                String patch = Files.readString(corpus.resolve("external-v2.sql")).replaceAll("(?m)^--[^\\r\\n]*", "");
                for (String sql : patch.split(";")) if (!sql.isBlank()) statement.execute(sql);
            }
            c.commit();
            var baseline = new MatchingEngine(JdbcMatchingRepository.baselineForComparison(), new PurposeParser());
            var optimized = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());
            int differences = 0; var quality = new QualityReport();
            try (var session = MatchingEngine.openReadOnlySession(); var writer = Files.newBufferedWriter(out.resolve("first-results.tsv"))) {
                row(writer, "case", "expected", "actual", "safeExpected", "safeActual", "candidates", "oldEqualsNew", "sessionEqualsNew", "issues", "fullDecisionBase64Utf8");
                for (var value : cases) {
                    var old = baseline.match(c, value.id()); var next = optimized.match(c, value.id()); var owned = session.match(value.id());
                    if (!old.equals(next) || !next.equals(owned)) differences++;
                    var issues = quality.accept(value.truth(), next);
                    row(writer, value.label(), value.truth().expectedStatus(), next.status(), value.truth().safeContract(), next.proposedContractId(), next.candidateIds(), old.equals(next), next.equals(owned), issues,
                            Base64.getEncoder().encodeToString(next.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            Properties summary = new Properties(); quality.counts().forEach((key,value) -> summary.setProperty(key, value.toString()));
            summary.setProperty("fullDecisionDifferences", Integer.toString(differences)); save(out.resolve("summary.properties"), summary);
            // Remove only the frozen corpus namespace after preserving its first result.
            Stage3Corpus.load(c, corpus.resolve("world-v2.sql"), List.of());
            // The fixture regression suite also requires exactly 4 clients / 9 contracts.
            try (var cleanup = c.createStatement()) {
                cleanup.executeUpdate("DELETE FROM contract_party WHERE contract_id BETWEEN 30000000 AND 39999999");
                cleanup.executeUpdate("DELETE FROM contract WHERE id BETWEEN 30000000 AND 39999999");
                cleanup.executeUpdate("DELETE FROM client WHERE id BETWEEN 30000000 AND 39999999");
            }
            c.commit();
            System.out.println("First v2 opening: " + out + "; differences=" + differences + "; wrongSafe=" + quality.counts().get("wrongSafe") + "; expectedSafeMissed=" + quality.counts().get("expectedSafeMissed"));
            if (differences != 0) throw new IllegalStateException("v2 SQL equivalence failed; preserve result and investigate without tuning control");
        }
    }
}
