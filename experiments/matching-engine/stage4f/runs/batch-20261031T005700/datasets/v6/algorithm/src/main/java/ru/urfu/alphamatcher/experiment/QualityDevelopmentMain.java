package ru.urfu.alphamatcher.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Properties;
import java.util.Base64;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;

/** Previously exposed v1/v2 are now development data; never reads the unopened v3. */
public final class QualityDevelopmentMain {
    private QualityDevelopmentMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("before", "after").contains(args[0])) throw new IllegalArgumentException("before | after");
        Path root = Path.of("").toAbsolutePath();
        Path out = root.resolve("quality/runs/" + args[0] + "-" + System.currentTimeMillis()); Files.createDirectories(out);
        Properties version = new Properties(); version.setProperty("phase", args[0]);
        try (var c = ExperimentDatabase.connect()) {
            version.setProperty("mysql", c.getMetaData().getDatabaseProductVersion());
            try (var paths = Files.walk(Path.of(System.getProperty("quality.source", "src/main/java")))) {
                for (Path p : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) version.setProperty(p.toString(), SqlProfileMain.hash(Files.readString(p)));
            }
            SqlProfileMain.save(out.resolve("versions.properties"), version);
            for (String corpus : List.of("development-v1", "final-v1", "final-v2")) {
                var cases = Stage3Corpus.read(root.resolve("stage3/corpora/" + corpus + ".tsv"));
                boolean v2 = corpus.endsWith("v2");
                try {
                    Stage3Corpus.load(c, root.resolve("stage3/corpora/world-" + (v2 ? "v2" : "v1") + ".sql"), cases);
                    if (v2) try (var statement = c.createStatement()) {
                        String patch = Files.readString(root.resolve("stage3/corpora/external-v2.sql")).replaceAll("(?m)^--[^\\r\\n]*", "");
                        for (String sql : patch.split(";")) if (!sql.isBlank()) statement.execute(sql);
                    }
                    collect(out.resolve(corpus), c, cases.stream().map(Stage3Corpus.Case::truth).toList());
                } finally { c.rollback(); }
            }
            for (String config : List.of("simple-1000", "stress-1000", "mixed-1000", "mixed-10000")) {
                Properties p = new Properties(); try (var reader = Files.newBufferedReader(root.resolve("config/" + config + ".properties"))) { p.load(reader); }
                var settings = Config.standard(Long.parseLong(p.getProperty("seed")), Integer.parseInt(p.getProperty("clients")), Integer.parseInt(p.getProperty("contracts")), Integer.parseInt(p.getProperty("operations")), Profile.valueOf(p.getProperty("profile")));
                try {
                    var data = new SyntheticGenerator().generate(settings); new SyntheticLoader().replace(c, data);
                    collect(out.resolve(config), c, data.truth().stream().map(Stage3QualityMain::referenceV3).toList());
                } finally { c.rollback(); }
            }
        }
        System.out.println("Quality development evidence: " + out);
    }
    private static void collect(Path out, Connection c, List<Truth> truths) throws Exception {
        Files.createDirectories(out); var quality = new QualityReport();
        var engine = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());
        try (var writer = Files.newBufferedWriter(out.resolve("decisions.tsv"))) {
            SqlProfileMain.row(writer, "id", "expected", "actual", "safeId", "candidates", "issues", "sha256", "fullDecisionBase64");
            for (Truth truth : truths) {
                var result = engine.match(c, truth.id()); var issues = quality.accept(truth, result);
                SqlProfileMain.row(writer, truth.id(), truth.expectedStatus(), result.status(), result.proposedContractId(), result.candidateIds(), issues,
                        SqlProfileMain.hash(result.toString()), Base64.getEncoder().encodeToString(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        Properties summary = new Properties(); quality.counts().forEach((key,value) -> summary.setProperty(key, value.toString()));
        SqlProfileMain.save(out.resolve("summary.properties"), summary);
        System.out.println(out.getFileName() + ": " + quality.counts());
    }
}
