package ru.urfu.alphamatcher.experiment;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;

/** Parts 1-7 quality-only runner. No SQL/index/thread/CPU optimization experiments. */
public final class Stage3QualityMain {
    private Stage3QualityMain() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !List.of("baseline","development","final").contains(args[0])) throw new IllegalArgumentException("baseline | development | final");
        Path root=Path.of("").toAbsolutePath();
        if(!root.getFileName().toString().equals("matching-engine")) throw new IllegalArgumentException("Run from experiment module");
        String phase=args[0]; Path output=root.resolve("stage3/runs/"+phase+"-"+System.currentTimeMillis()); Files.createDirectories(output);
        Path corpora=root.resolve("stage3/corpora");
        var development=Stage3Corpus.read(corpora.resolve("development-v1.tsv"));
        try(var connection=ExperimentDatabase.connect()) {
            Stage3Corpus.load(connection,corpora.resolve("world-v1.sql"),development);
            collect(output.resolve("development"),development.stream().map(c->new Reference(c.truth(),c.label(),c.automatable(),c.expectedReason())).toList(),connection);
            connection.rollback();
            if(phase.equals("final")) {
                var holdout=Stage3Corpus.read(corpora.resolve("final-v1.tsv"));
                Stage3Corpus.load(connection,corpora.resolve("world-v1.sql"),holdout);
                collect(output.resolve("final-control"),holdout.stream().map(c->new Reference(c.truth(),c.label(),c.automatable(),c.expectedReason())).toList(),connection);
                connection.rollback();
            }
            if(!phase.equals("development")) {
                for(String config:List.of("simple-1000","stress-1000","mixed-1000","mixed-10000")) {
                    Properties p=new Properties(); try(var reader=Files.newBufferedReader(root.resolve("config/"+config+".properties"))) { p.load(reader); }
                    var settings=Config.standard(Long.parseLong(p.getProperty("seed")),Integer.parseInt(p.getProperty("clients")),Integer.parseInt(p.getProperty("contracts")),Integer.parseInt(p.getProperty("operations")),Profile.valueOf(p.getProperty("profile")));
                    var data=new SyntheticGenerator().generate(settings); new SyntheticLoader().replace(connection,data);
                    List<Reference> reference=new ArrayList<>();
                    for(Truth legacy:data.truth()) {
                        // Predeclared oracle v3: length alone is not a business conflict. Data/generator stay unchanged.
                        Truth truth=legacy.scenario()==Scenario.OVER_LIMIT?new Truth(legacy.id(),legacy.scenario(),"SAFE",legacy.knownTarget(),legacy.knownTarget(),legacy.inns(),legacy.invalidInns(),legacy.names(),legacy.numbers(),legacy.organizations(),legacy.thirdParty()):legacy;
                        reference.add(new Reference(truth,legacy.scenario().name(),truth.expectedStatus().equals("SAFE"),"LEGACY_"+legacy.scenario()));
                    }
                    collect(output.resolve(config),reference,connection); connection.rollback();
                }
            }
        }
        System.out.println("Stage3 results: "+output);
    }

    record Reference(Truth truth,String scenario,boolean automatable,String expectedReason) { }
    static void collect(Path output,List<Reference> reference,java.sql.Connection connection) throws Exception {
        Files.createDirectories(output); var report=new QualityReport(); var scenarios=new TreeMap<String,QualityReport>();
        var matrix=new TreeMap<String,Long>(); var reviews=new TreeMap<String,Long>();
        long auto=0, autoReview=0, knownSafe=0; long parserNanos=0, shortNanos=0,longNanos=0; int shortCount=0,longCount=0;
        var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
        try(var decisions=Files.newBufferedWriter(output.resolve("decisions.csv"));var errors=Files.newBufferedWriter(output.resolve("mismatches.csv"));var samples=Files.newBufferedWriter(output.resolve("parsing.csv"))) {
            row(decisions,"id","scenario","expected","actual","safeExpected","safeActual","target","candidates","automatable","expectedReason","reviewReason","errors","checks","features");
            row(errors,"id","scenario","expected","actual","safeExpected","safeActual","target","candidates","errors","expectedFeatures","features");
            row(samples,"id","characters","parsingNs");
            for(Reference ref:reference) {
                var timed=engine.measure(connection,ref.truth.id()); var result=timed.decision();
                var failures=report.accept(ref.truth,result); scenarios.computeIfAbsent(ref.scenario,k->new QualityReport()).accept(ref.truth,result);
                matrix.merge(ref.truth.expectedStatus()+" -> "+result.status(),1L,Long::sum);
                String reason=reviewReason(result); if(result.status()==MatchingModel.Status.REVIEW) reviews.merge(reason,1L,Long::sum);
                if(ref.automatable) { auto++; if(result.status()==MatchingModel.Status.REVIEW) autoReview++; }
                if(ref.truth.knownTarget()!=null && java.util.Objects.equals(ref.truth.knownTarget(),result.proposedContractId())) knownSafe++;
                int chars; try(var statement=connection.prepareStatement("SELECT CHAR_LENGTH(payment_purpose) FROM operation WHERE id=?")) {
                    statement.setLong(1,ref.truth.id()); try(var rs=statement.executeQuery()) { rs.next(); chars=rs.getInt(1); }
                }
                parserNanos+=timed.parsingNanos(); if(chars>4000) { longNanos+=timed.parsingNanos(); longCount++; } else { shortNanos+=timed.parsingNanos(); shortCount++; }
                row(samples,ref.truth.id(),chars,timed.parsingNanos());
                row(decisions,ref.truth.id(),ref.scenario,ref.truth.expectedStatus(),result.status(),ref.truth.safeContract(),result.proposedContractId(),ref.truth.knownTarget(),result.candidateIds(),ref.automatable,ref.expectedReason,reason,failures,result.checks(),result.features());
                if(!failures.isEmpty()) row(errors,ref.truth.id(),ref.scenario,ref.truth.expectedStatus(),result.status(),ref.truth.safeContract(),result.proposedContractId(),ref.truth.knownTarget(),result.candidateIds(),failures,ref.truth,result.features());
            }
        }
        Properties values=properties(report);
        values.setProperty("automatable",Long.toString(auto)); values.setProperty("automatableReview",Long.toString(autoReview));
        values.setProperty("automatableReviewShare",ratio(autoReview,auto)); values.setProperty("knownTargetCorrectSafe",Long.toString(knownSafe));
        values.setProperty("knownTargetSafeShare",ratio(knownSafe,report.counts().get("knownTargets")));
        values.setProperty("safeShare",ratio(report.counts().get("correctSafe")+report.counts().get("wrongSafe"),reference.size()));
        values.setProperty("parser.totalNs",Long.toString(parserNanos)); values.setProperty("parser.short.totalNs",Long.toString(shortNanos)); values.setProperty("parser.short.n",Integer.toString(shortCount));
        values.setProperty("parser.long.totalNs",Long.toString(longNanos)); values.setProperty("parser.long.n",Integer.toString(longCount));
        matrix.forEach((k,v)->values.setProperty("matrix."+k,v.toString())); reviews.forEach((k,v)->values.setProperty("review."+k,v.toString()));
        try(var writer=Files.newBufferedWriter(output.resolve("summary.properties"))) { values.store(writer,"Stage3 oracle v3; no tuning on final corpus"); }
        try(var writer=Files.newBufferedWriter(output.resolve("scenarios.csv"))) {
            row(writer,"scenario","N","correctSafe","wrongSafe","review","missedTargets","statusMismatch","expectedSafeMissed");
            for(var entry:scenarios.entrySet()) { var v=entry.getValue().counts(); row(writer,entry.getKey(),v.get("total"),v.get("correctSafe"),v.get("wrongSafe"),v.get("review"),v.get("missedTargets"),v.get("statusMismatch"),v.get("expectedSafeMissed")); }
        }
        System.out.println(output.getFileName()+": N="+reference.size()+" safe="+report.counts().get("correctSafe")+" wrongSafe="+report.counts().get("wrongSafe")+" missedTargets="+report.counts().get("missedTargets")+" autoReview="+autoReview);
    }

    static String reviewReason(Decision result) {
        if(result.status()!=MatchingModel.Status.REVIEW) return "NONE";
        String issues=String.join(" ",result.features().issues());
        if(issues.contains("4000") || issues.contains("SIZE_LIMIT")) return "LENGTH_LIMIT";
        if(issues.contains("TIME_LIMIT") || issues.contains("MATCH_LIMIT")) return "RESOURCE_LIMIT";
        if(issues.contains("UNICODE")) return "NORMALIZATION";
        if(!result.features().invalidInns().isEmpty()) return "INVALID_IDENTIFIER";
        if(!issues.isEmpty()) return "UNSUPPORTED_FORMAT";
        if(result.features().thirdParty()) return "THIRD_PARTY";
        if(result.features().contractNumbers().size()>1) return "MULTIPLE_NUMBERS";
        if(result.candidateIds().size()>1) return "MULTIPLE_CONTRACTS";
        for(var check:result.checks()) if(!check.passed()) return switch(check.code()) {
            case "OPERATION_PARTIES" -> "MISSING_OR_CONFLICTING_ROLE";
            case "ROLE_CLAIMS" -> "ROLE_CONFLICT";
            case "PURPOSE_NAMES" -> "UNKNOWN_OR_CONFLICTING_NAME";
            case "PURPOSE_ORGANIZATIONS","PURPOSE_INN","PARTY_PAYER","PARTY_RECIPIENT" -> "CONFLICT";
            case "BENEFICIARY" -> "THIRD_PARTY";
            case "SEARCH_KEYS","EXPLICIT_UNIQUE_CONTRACT" -> "MISSING_IDENTIFIERS";
            default -> check.code();
        };
        return "OTHER";
    }

    private static Properties properties(QualityReport report) {
        Properties p=new Properties(); report.counts().forEach((k,v)->p.setProperty(k,v.toString())); p.setProperty("safePrecision",Double.toString(report.safePrecision()));
        report.extraction().forEach((k,v)->{ p.setProperty(k+".tp",Long.toString(v.truePositive)); p.setProperty(k+".fp",Long.toString(v.falsePositive)); p.setProperty(k+".fn",Long.toString(v.falseNegative)); p.setProperty(k+".precision",Double.toString(v.precision())); p.setProperty(k+".recall",Double.toString(v.recall())); }); return p;
    }
    private static String ratio(long n,long d) { return d==0?"NaN":Double.toString((double)n/d); }
    private static void row(BufferedWriter writer,Object... values) throws Exception {
        for(int i=0;i<values.length;i++) { if(i>0) writer.write(','); writer.write('"'); writer.write(values[i]==null?"":values[i].toString().replace("\"","\"\"").replace('\n',' ').replace('\r',' ')); writer.write('"'); } writer.newLine();
    }
}
