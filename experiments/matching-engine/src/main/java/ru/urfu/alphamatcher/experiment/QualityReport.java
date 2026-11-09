package ru.urfu.alphamatcher.experiment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.Truth;

/** Reports identity retrieval separately from conservative routing. No expectations come from the engine. */
public final class QualityReport {
    public static final class Extraction {
        public long truePositive, falsePositive, falseNegative;
        void add(List<String> expected, List<String> actual) {
            var wanted=new TreeSet<>(expected); var found=new TreeSet<>(actual);
            truePositive+=found.stream().filter(wanted::contains).count();
            falsePositive+=found.stream().filter(value->!wanted.contains(value)).count();
            falseNegative+=wanted.stream().filter(value->!found.contains(value)).count();
        }
        public double precision() { return truePositive+falsePositive==0?Double.NaN:(double)truePositive/(truePositive+falsePositive); }
        public double recall() { return truePositive+falseNegative==0?Double.NaN:(double)truePositive/(truePositive+falseNegative); }
    }
    private final Map<String,Long> counts=new LinkedHashMap<>();
    private final Map<String,Extraction> extraction=new LinkedHashMap<>();
    private final Map<String,Long> scenarioErrors=new LinkedHashMap<>();

    public QualityReport() {
        for(String name:List.of("total","correctSafe","wrongSafe","expectedSafeMissed","statusMismatch","knownTargets","missedTargets",
                "correctNoContract","wrongNoContract","missedNoContract","review","skipped","alreadyManual","thirdTP","thirdFP","thirdFN","unresolvedNames")) counts.put(name,0L);
        for(String field:List.of("inn","invalidInn","name","contract","organization")) extraction.put(field,new Extraction());
    }

    public List<String> accept(Truth truth, Decision actual) {
        List<String> errors=new ArrayList<>();
        increment("total");
        boolean safe=actual.status().name().equals("SAFE");
        boolean correctSafe=safe && truth.expectedStatus().equals("SAFE") && java.util.Objects.equals(truth.safeContract(),actual.proposedContractId());
        if(correctSafe) increment("correctSafe");
        if(safe && !correctSafe) { increment("wrongSafe"); errors.add("WRONG_SAFE"); }
        if(truth.expectedStatus().equals("SAFE") && !correctSafe) { increment("expectedSafeMissed"); errors.add("EXPECTED_SAFE_MISSED"); }
        if(!truth.expectedStatus().equals(actual.status().name())) { increment("statusMismatch"); errors.add("STATUS"); }
        if(truth.knownTarget()!=null && !truth.expectedStatus().equals("SKIPPED")) {
            increment("knownTargets");
            if(!actual.candidateIds().contains(truth.knownTarget())) { increment("missedTargets"); errors.add("TARGET_NOT_RETRIEVED"); }
        }
        if(actual.status().name().equals("NO_CONTRACT")) {
            if(truth.expectedStatus().equals("NO_CONTRACT")) increment("correctNoContract");
            else { increment("wrongNoContract"); errors.add("WRONG_NO_CONTRACT"); }
        } else if(truth.expectedStatus().equals("NO_CONTRACT")) increment("missedNoContract");
        if(actual.status().name().equals("REVIEW")) increment("review");
        if(actual.status().name().equals("SKIPPED")) increment("skipped");
        if(actual.status().name().equals("ALREADY_MANUAL")) increment("alreadyManual");
        var features=actual.features();
        compare("inn",truth.inns(),features.inns(),errors);
        compare("invalidInn",truth.invalidInns(),features.invalidInns(),errors);
        compare("name",truth.names(),features.names().stream().map(NameNormalizer.Mention::key).toList(),errors);
        compare("contract",truth.numbers(),features.contractNumbers(),errors);
        compare("organization",truth.organizations(),features.organizations(),errors);
        counts.merge("unresolvedNames",features.names().stream().filter(name->!name.recognized()).count(),Long::sum);
        if(truth.thirdParty() && features.thirdParty()) increment("thirdTP");
        if(!truth.thirdParty() && features.thirdParty()) { increment("thirdFP"); errors.add("THIRD_PARTY_FP"); }
        if(truth.thirdParty() && !features.thirdParty()) { increment("thirdFN"); errors.add("THIRD_PARTY_FN"); }
        if(!errors.isEmpty()) scenarioErrors.merge(truth.scenario().name(),1L,Long::sum);
        return List.copyOf(errors);
    }

    public Map<String,Long> counts() { return Map.copyOf(counts); }
    public Map<String,Extraction> extraction() { return Map.copyOf(extraction); }
    public Map<String,Long> scenarioErrors() { return Map.copyOf(scenarioErrors); }
    public double safePrecision() { long all=counts.get("correctSafe")+counts.get("wrongSafe"); return all==0?Double.NaN:(double)counts.get("correctSafe")/all; }
    private void increment(String name) { counts.merge(name,1L,Long::sum); }
    private void compare(String field,List<String> expected,List<String> actual,List<String> errors) {
        extraction.get(field).add(expected,actual);
        if(!new TreeSet<>(expected).equals(new TreeSet<>(actual))) errors.add("EXTRACTION_"+field);
    }
}
