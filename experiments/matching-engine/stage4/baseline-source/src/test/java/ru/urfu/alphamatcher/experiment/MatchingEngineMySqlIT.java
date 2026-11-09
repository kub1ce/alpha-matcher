package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;

/** No substitute database and no conditional skip: mysql-it fails when real MySQL is unavailable. */
class MatchingEngineMySqlIT {
    private Connection connection;
    private final JdbcMatchingRepository repository = new JdbcMatchingRepository();
    private final PurposeParser parser = new PurposeParser();
    private final MatchingEngine engine = new MatchingEngine(repository, parser);

    @BeforeEach
    void connectToDedicatedCatalog() throws SQLException {
        connection = ExperimentDatabase.connect();
        assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        assertEquals(ExperimentDatabase.CATALOG, connection.getCatalog());
        assertEquals(20, count("operation"), "Load sql/001_schema.sql and fixtures.sql into the disposable database");
    }

    @AfterEach
    void closeWithoutSavingChanges() throws SQLException {
        if (connection != null) {
            try { connection.rollback(); } finally { connection.close(); }
        }
    }

    @ParameterizedTest(name = "{0}: {3}")
    @CsvFileSource(resources = "/cases.csv", numLinesToSkip = 1)
    void realMysqlVerticalScenario(long operationId, Status expected, String expectedContract, String scenario) throws SQLException {
        List<String> before = snapshot();
        var decision = engine.match(connection, operationId);
        assertEquals(expected, decision.status(), () -> scenario + ": " + decision);
        assertEquals("-".equals(expectedContract) ? null : Long.valueOf(expectedContract), decision.proposedContractId());
        assertFalse(decision.checks().isEmpty());
        assertEquals(decision, engine.match(connection, operationId), "Repeat preview must be deterministic");
        assertEquals(before, snapshot(), "Preview must not change links, status, parties or stored candidates");
    }

    @Test
    void sqlInitializationAndJdbcPreserveCyrillicAndNumeroSign() throws SQLException {
        String purpose = repository.operation(connection, 1).orElseThrow().purpose();
        assertEquals("Оплата по договору № D-001, ИНН 9900000017", purpose);
        assertEquals(List.of("D-001"), parser.parse(purpose).contractNumbers());
        assertEquals(List.of("9900000017"), parser.parse(purpose).inns());
    }

    @Test
    void searchesLinkedClientsAndExternalPartiesByInnWithoutAutoLinking() throws SQLException {
        var candidates = repository.candidates(connection, parser.parse("ИНН 9900000017"));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.id() == 101));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.id() == 105));
        assertEquals(Status.REVIEW, engine.match(connection, 8).status());
    }

    @Test
    void usesParametersInsteadOfExecutingSearchInputAsSql() throws SQLException {
        var features = new PurposeParser.Features("", List.of(), List.of(),
                List.of("D-001' OR 1=1 --"), false, List.of());
        assertTrue(repository.candidates(connection, features).isEmpty());
        assertEquals(9, count("contract"));
    }

    @Test
    void doesNotTreatSavedCandidateAsConfirmedLink() throws SQLException {
        assertEquals(1, count("operation_contract_candidate"));
        assertEquals(101L, engine.match(connection, 1).proposedContractId());
        assertNull(repository.operation(connection, 1).orElseThrow().linkedContractId());
    }

    @Test
    void failsForUnknownOperationAndUnsafeTransactionMode() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> engine.match(connection, 99999));
        connection.rollback();
        connection.setAutoCommit(true);
        assertThrows(IllegalArgumentException.class, () -> engine.match(connection, 1));
        connection.setAutoCommit(false);
    }

    @Test
    void revalidatesCurrentInputsInsteadOfReusingPreviousProposal() throws SQLException {
        assertEquals(Status.SAFE, engine.match(connection, 1).status());
        try (var statement = connection.prepareStatement("UPDATE operation_party SET inn=? WHERE operation_id=1 AND role_id=(SELECT id FROM operation_party_role WHERE code='PAYER')")) {
            statement.setString(1, "9900000031");
            assertEquals(1, statement.executeUpdate());
        }
        assertEquals(Status.REVIEW, engine.match(connection, 1).status());
        connection.rollback();
        assertEquals(Status.SAFE, engine.match(connection, 1).status());
    }

    @Test
    void executesExplainAnalyzeAgainstMysqlRatherThanInventingAPlan() throws SQLException {
        try (var statement = connection.prepareStatement("EXPLAIN ANALYZE SELECT id FROM contract WHERE contract_number = ?")) {
            statement.setString(1, "D-001");
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next());
                String plan = rs.getString(1);
                assertTrue(plan.contains("actual time="), plan);
                assertTrue(plan.contains("ix_contract_number"), plan);
                System.out.println("MySQL=" + connection.getMetaData().getDatabaseProductVersion());
                System.out.println("EXPLAIN ANALYZE (small fixture, not a benchmark): " + plan);
            }
        }
    }

    private long count(String table) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> snapshot() throws SQLException {
        List<String> values = new ArrayList<>();
        for (String table : List.of("operation", "operation_party", "contract", "contract_party", "client", "operation_contract_candidate")) {
            try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder(table);
                    for (int i = 1; i <= columns; i++) row.append('|').append(rs.getString(i));
                    values.add(row.toString());
                }
            }
        }
        return values;
    }
}
