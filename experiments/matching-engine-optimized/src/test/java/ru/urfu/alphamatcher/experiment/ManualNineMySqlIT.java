package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;

class ManualNineMySqlIT {
    private final PurposeParser parser = new PurposeParser();
    private final JdbcMatchingRepository repository = new JdbcMatchingRepository();

    @Test void exactManualTextsNeverBecomeSafeWithoutMatchingDatabaseParticipants() throws Exception {
        try (Connection connection = ExperimentDatabase.connect()) {
            try {
                var engine = new MatchingEngine(repository, parser);
                for (String text : ManualNineParserTest.TEXTS) {
                    setPurpose(connection, text);
                    var decision = engine.match(connection, 1);
                    assertNotEquals(Status.SAFE, decision.status(), decision.toString());
                    assertFalse(decision.checks().isEmpty());
                    assertEquals(decision, engine.match(connection, 1), "Full checks, candidates and evidence must be deterministic");
                }
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM contract")) {
                    assertTrue(rows.next()); assertEquals(9, rows.getInt(1), "SQL-like input must leave schema and data intact");
                }
            } finally { connection.rollback(); }
        }
    }

    @Test void typedAndListedContractsPreserveFinancialGates() throws Exception {
        try (Connection connection = ExperimentDatabase.connect()) {
            try {
                var engine = new MatchingEngine(repository, parser);
                for (String type : List.of("займа", "аренды", "поставки", "оказания услуг")) {
                    setPurpose(connection, "Оплата по договору " + type + " № D-001, ИНН 9900000017");
                    assertEquals(Status.SAFE, engine.match(connection, 1).status());
                }
                setPurpose(connection, "Оплата по договорам № D-001 от 03.03.2026 и № D-002 от 04.03.2026, ИНН 9900000017");
                assertNotEquals(Status.SAFE, engine.match(connection, 1).status(), "A second obligation must not disappear");
                setPurpose(connection, "Оплата по договору № D-0О1, ИНН 9900000017");
                assertNotEquals(Status.SAFE, engine.match(connection, 1).status(), "Confusable evidence is never certified by normalization");
            } finally { connection.rollback(); }
        }
    }

    private static void setPurpose(Connection connection, String text) throws Exception {
        try (var statement = connection.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=1")) {
            statement.setString(1, text); assertEquals(1, statement.executeUpdate());
        }
    }
}
