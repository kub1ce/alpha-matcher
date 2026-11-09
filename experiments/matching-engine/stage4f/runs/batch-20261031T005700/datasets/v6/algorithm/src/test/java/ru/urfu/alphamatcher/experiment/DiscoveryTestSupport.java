package ru.urfu.alphamatcher.experiment;
import java.util.List;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;
/** Rule-unit tests explicitly assert a complete finite fixture, never a real SQL execution. */
final class DiscoveryTestSupport {
    static Decision evaluate(Operation operation,PurposeParser.Features features,List<Candidate> candidates){
        return MatchingEngine.evaluate(operation,features,CandidateSearch.declaredFixture(features,candidates));
    }
}
