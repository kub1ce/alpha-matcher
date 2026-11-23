package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;

class QualityReportTest {
    @Test
    void conservativeRoutingDoesNotCountAsCorrectIdentityOrSafe() {
        var truth=new Truth(1,Scenario.MISSING_INN,"REVIEW",null,101L,List.of(),List.of(),List.of(),List.of("D-1"),List.of(),false);
        var report=new QualityReport();
        report.accept(truth,new Decision(1,Status.REVIEW,null,List.of(),new PurposeParser.Features("",List.of(),List.of(),List.of("D-1"),false,List.of()),List.of()));
        assertEquals(0L,report.counts().get("statusMismatch"));
        assertEquals(1L,report.counts().get("missedTargets"));
        assertTrue(Double.isNaN(report.safePrecision()));
    }

    @Test
    void wrongSafeAndFeatureErrorsAreRecordedEvenWithCorrectStatusWord() {
        var truth=new Truth(1,Scenario.CLEAR,"SAFE",101L,101L,List.of("9900000017"),List.of(),List.of(),List.of("D-1"),List.of(),false);
        var report=new QualityReport();
        var errors=report.accept(truth,new Decision(1,Status.SAFE,102L,List.of(102L),new PurposeParser.Features("",List.of(),List.of(),List.of("D-2"),true,List.of()),List.of()));
        assertTrue(errors.contains("WRONG_SAFE")); assertEquals(0.0,report.safePrecision());
        assertEquals(1,report.extraction().get("inn").falseNegative);
        assertEquals(1,report.extraction().get("contract").falsePositive);
        assertEquals(1L,report.counts().get("thirdFP"));
    }

    @Test
    void matchingTargetIdDoesNotMakeAnUnsafeDecisionCorrect() {
        var forbidden=new Truth(1,Scenario.CONFLICT,"REVIEW",null,101L,List.of(),List.of(),List.of(),List.of(),List.of(),false);
        var decision=new Decision(1,Status.SAFE,101L,List.of(101L),new PurposeParser.Features("",List.of(),List.of(),List.of(),false,List.of()),List.of());
        assertFalse(Stage3QualityMain.correctKnownSafe(forbidden,decision));
        var allowed=new Truth(1,Scenario.CLEAR,"SAFE",101L,101L,List.of(),List.of(),List.of(),List.of(),List.of(),false);
        assertTrue(Stage3QualityMain.correctKnownSafe(allowed,decision));
    }

    @Test
    void nearestRankPercentilesDoNotSelectBestRun() {
        long[] sorted={1,2,3,4,5,6,7,8,9,10};
        assertEquals(5,BenchmarkMain.percentile(sorted,0.5));
        assertEquals(10,BenchmarkMain.percentile(sorted,0.95)); assertEquals(10,BenchmarkMain.percentile(sorted,0.99));
    }
}
