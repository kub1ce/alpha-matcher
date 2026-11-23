package ru.urfu.alphamatcher.experiment;

import java.util.List;
import java.util.LinkedHashSet;
import ru.urfu.alphamatcher.experiment.MatchingModel.Candidate;
import ru.urfu.alphamatcher.experiment.PurposeParser.Completeness;

/** Search completeness is independent of result cardinality. Empty is not a completion proof. */
public final class CandidateSearch {
    private CandidateSearch() { }
    public enum Origin { JDBC, DECLARED_FIXTURE, UNKNOWN }
    public record Evidence(Completeness completeness,List<String> requiredKeys,List<String> searchedKeys,
                           List<String> boundParameters,String route,boolean queryFullyConsumed,Origin origin,String diagnostic) {
        public Evidence {requiredKeys=List.copyOf(requiredKeys);searchedKeys=List.copyOf(searchedKeys);boundParameters=List.copyOf(boundParameters);}
    }
    public record Result(List<Candidate> candidates,Evidence evidence) {
        public Result {candidates=List.copyOf(candidates);}
    }
    public static String route(PurposeParser.Features f){return !f.contractNumbers().isEmpty()?"CONTRACT":"FALLBACK";}
    public static List<String> requiredKeys(PurposeParser.Features f) {
        if(!f.contractNumbers().isEmpty())return ContractNumbers.variants(f.contractSearchNumbers()).values().stream().map(n->"CONTRACT:"+n).toList();
        var keys=new LinkedHashSet<String>();f.inns().forEach(n->keys.add("INN:"+n));
        f.names().forEach(n->keys.add("NAME:"+NameNormalizer.key(n.surface())));f.organizations().forEach(n->keys.add("ORG:"+n));return List.copyOf(keys);
    }
    public static Result unknown(PurposeParser.Features f,List<Candidate> candidates,String diagnostic){
        return new Result(candidates,new Evidence(Completeness.UNKNOWN,requiredKeys(f),List.of(),List.of(),route(f),false,Origin.UNKNOWN,diagnostic));
    }
    static boolean complete(PurposeParser.Features f,Evidence e){
        var required=requiredKeys(f);
        return interpretationsComplete(f)
                && e.completeness()==Completeness.COMPLETE
                && e.queryFullyConsumed() && e.origin()!=Origin.UNKNOWN && route(f).equals(e.route())
                && new LinkedHashSet<>(required).equals(new LinkedHashSet<>(e.requiredKeys()))
                && new LinkedHashSet<>(required).equals(new LinkedHashSet<>(e.searchedKeys()))
                && (f.contractNumbers().isEmpty() || e.boundParameters().equals(ContractNumbers.variants(f.contractSearchNumbers()).values()));
    }
    static boolean interpretationsComplete(PurposeParser.Features f){
        return f.contractAnalysis()==Completeness.COMPLETE && f.contractReferences().stream().allMatch(r->r.completeness()==Completeness.COMPLETE)
                && PurposeParser.contractCompleteness(true,f.issues(),f.contractReferences(),f.contractNumbers())==Completeness.COMPLETE
                && f.contractReferences().stream().allMatch(r->ContractBoundaries.readings(r.surface(),r.quoted()).complete()
                    && ContractBoundaries.readings(r.surface(),r.quoted()).values().equals(r.alternatives())
                    && r.reasons().stream().noneMatch(String::isBlank))
                && ContractNumbers.variants(f.contractSearchNumbers()).complete();
    }
    /** Explicit finite-world assertion for rule-only experiments/tests, never production JDBC evidence. */
    public static Result declaredFixture(PurposeParser.Features f,List<Candidate> candidates){
        var required=requiredKeys(f);return new Result(candidates,new Evidence(Completeness.COMPLETE,required,required,
                f.contractNumbers().isEmpty()?List.of():ContractNumbers.variants(f.contractSearchNumbers()).values(),route(f),true,Origin.DECLARED_FIXTURE,"Caller asserts complete finite fixture; no SQL execution is claimed"));
    }
}
