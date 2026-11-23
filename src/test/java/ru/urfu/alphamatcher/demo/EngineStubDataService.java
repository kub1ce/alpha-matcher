package ru.urfu.alphamatcher.demo;

import java.util.List;
import ru.urfu.alphamatcher.experiment.*;

/** Explicit test double for UI workflow tests, never a runtime matching implementation.
 * Financial correctness is exercised separately by the engine's JDBC suite. */
public class EngineStubDataService extends DemoDataService {
    @Override public boolean hasMatchingSource() { return true; }

    @Override public MatchingModel.Decision analyze(Operation operation) {
        var candidates = operation.candidates().stream().map(this::numeric).toList();
        var status = candidates.isEmpty() ? MatchingModel.Status.NO_CONTRACT
                : candidates.size() == 1 && operation.reason().isBlank() ? MatchingModel.Status.SAFE
                : MatchingModel.Status.REVIEW;
        var features = new PurposeParser().parse(operation.purpose());
        return new MatchingModel.Decision(Integer.toUnsignedLong(operation.id().hashCode()),status,
                status == MatchingModel.Status.SAFE ? candidates.getFirst() : null,candidates,features,
                List.of(new MatchingModel.Check("UI_TEST_FIXTURE",status == MatchingModel.Status.SAFE,operation.reason().isBlank() ? "Explicit UI workflow fixture" : operation.reason())),
                CandidateSearch.unknown(features,List.of(),"Test double; not financial evidence").evidence());
    }
    private long numeric(String reference) {
        for (int i=0;i<contracts().size();i++) if(contracts().get(i).id().equals(reference))return i+1L;
        throw new IllegalArgumentException(reference);
    }
    @Override public String contractReference(long databaseId) { return contracts().get(Math.toIntExact(databaseId)-1).id(); }
}
