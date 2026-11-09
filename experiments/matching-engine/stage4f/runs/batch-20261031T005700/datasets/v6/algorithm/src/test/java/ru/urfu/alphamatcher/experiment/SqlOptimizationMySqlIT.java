package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;

/** Development/known data only. The frozen v2 control is deliberately never opened by tests. */
class SqlOptimizationMySqlIT {
    private final MatchingEngine old = new MatchingEngine(JdbcMatchingRepository.baselineForComparison(), new PurposeParser());
    private final MatchingEngine optimized = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());

    @ParameterizedTest @ValueSource(strings = {"development-v1", "final-v1"})
    void allFixtureAndKnownCorpusDecisionsAndCandidatesAreIdentical(String corpusName) throws Exception {
        try (var connection = ExperimentDatabase.connect()) {
            for (long id = 1; id <= 20; id++) assertEquals(old.match(connection, id), optimized.match(connection, id));
            var corpus = Stage3Corpus.read(Path.of(System.getProperty("basedir", ".")).resolve("stage3/corpora/" + corpusName + ".tsv"));
            try {
                Stage3Corpus.load(connection, Path.of(System.getProperty("basedir", ".")).resolve("stage3/corpora/world-v1.sql"), corpus);
                for (var value : corpus) assertEquals(old.match(connection, value.id()), optimized.match(connection, value.id()), value.label());
            } finally { connection.rollback(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"SIMPLE", "MIXED", "STRESS"})
    void generatedFullDecisionsRemainIdentical(String profile) throws Exception {
        var data = new SyntheticGenerator().generate(Config.standard(20261029, 120, 240, 1000, Profile.valueOf(profile)));
        try (var connection = ExperimentDatabase.connect()) {
            try {
                new SyntheticLoader().replace(connection, data);
                for (var truth : data.truth()) assertEquals(old.match(connection, truth.id()), optimized.match(connection, truth.id()), "operation=" + truth.id());
            } finally { connection.rollback(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"isolation", "writable", "autocommit", "closed"})
    void invalidTransactionContractIsRejected(String condition) throws Exception {
        try (var connection = ExperimentDatabase.connect()) {
            connection.setReadOnly(true);
            switch (condition) {
                case "isolation" -> connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                case "writable" -> connection.setReadOnly(false);
                case "autocommit" -> connection.setAutoCommit(true);
                case "closed" -> connection.close();
                default -> fail();
            }
            assertThrows(IllegalArgumentException.class, () -> MatchingEngine.validateReadOnlyTransaction(connection));
        }
    }
    @Test void ownedSessionIsReadOnlyAndEndsAfterRollbackOrException() throws Exception {
        try (var connection = ExperimentDatabase.connect(); var session = MatchingEngine.openReadOnlySession()) {
            assertEquals(old.match(connection, 1), session.match(1));
            assertEquals(session.match(1), session.match(1));
            session.rollback();
            assertThrows(IllegalStateException.class, () -> session.match(1));
            session.close();
        }
        try (var session = MatchingEngine.openReadOnlySession()) {
            assertThrows(IllegalArgumentException.class, () -> session.match(Long.MAX_VALUE));
            assertThrows(IllegalStateException.class, () -> session.match(1));
        }
        try (var fresh = MatchingEngine.openReadOnlySession()) { assertEquals(MatchingModel.Status.SAFE, fresh.match(1).status()); }
    }
    @ParameterizedTest @ValueSource(strings = {"SET SESSION TRANSACTION READ WRITE", "SET autocommit=1", "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED"})
    void serverContractViolationIsRejectedEvenIfJdbcFlagsAreStale(String sql) throws Exception {
        try (var connection = ExperimentDatabase.connect()) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) { statement.execute(sql); }
            assertThrows(IllegalArgumentException.class, () -> MatchingEngine.validateReadOnlyTransaction(connection));
        }
    }
    @Test void connectionFailureInvalidatesSessionAndDoesNotPoisonNextSession() throws Exception {
        try (var session = MatchingEngine.openReadOnlySession(); var observer = ExperimentDatabase.connect()) {
            try (var statement = observer.createStatement()) { statement.execute("KILL CONNECTION " + session.connectionId()); }
            assertThrows(java.sql.SQLException.class, () -> session.match(1));
            assertThrows(IllegalStateException.class, () -> session.match(1));
        }
        try (var session = MatchingEngine.openReadOnlySession()) { assertEquals(MatchingModel.Status.SAFE, session.match(1).status()); }
    }
    @Test void sessionCannotBeUsedByAnotherThread() throws Exception {
        try (var session = MatchingEngine.openReadOnlySession()) {
            var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread thread = Thread.ofPlatform().start(() -> {
                try { session.match(1); } catch (Throwable failure) { error.set(failure); }
            });
            thread.join();
            assertInstanceOf(IllegalStateException.class, error.get());
            assertEquals(MatchingModel.Status.SAFE, session.match(1).status());
        }
    }
    @Test void snapshotRemainsStableButNextSessionSeesCommittedChanges() throws Exception {
        try (var writer = ExperimentDatabase.connect()) {
            String original;
            try (var query = writer.createStatement(); var rs = query.executeQuery("SELECT payment_purpose FROM operation WHERE id=1")) { rs.next(); original = rs.getString(1); }
            writer.rollback();
            try (var session = MatchingEngine.openReadOnlySession()) {
                var before = session.match(1);
                try {
                    try (var query = writer.createStatement()) { query.executeUpdate("UPDATE operation SET payment_purpose='Нет идентификаторов' WHERE id=1"); }
                    writer.commit();
                    assertEquals(before, session.match(1));
                    try (var fresh = MatchingEngine.openReadOnlySession()) { assertNotEquals(before, fresh.match(1)); }
                } finally {
                    try (var query = writer.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=1")) { query.setString(1, original); query.executeUpdate(); }
                    writer.commit();
                }
            }
        }
    }
}

