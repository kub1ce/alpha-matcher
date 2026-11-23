package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class JdbcProfileMySqlIT {
    @Test void instrumentationPreservesWholeDecisionAndMeasuresActualRows() throws Exception {
        try (var connection = ExperimentDatabase.connect()) {
            var engine = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());
            var expected = engine.match(connection, 1);
            var profile = new JdbcProfile();
            assertEquals(expected, engine.match(profile.wrap(connection), 1));
            assertEquals(2, profile.samples.size());
            assertEquals(2, profile.samples.values().stream().mapToLong(s -> s.executions).sum());
            assertTrue(profile.samples.values().stream().allMatch(s -> s.rows >= 2 && s.executeNanos > 0 && s.resultNanos > 0));
            assertTrue(profile.samples.values().stream().allMatch(s -> !s.parameters.isEmpty()));
            connection.rollback();
        }
    }
}
