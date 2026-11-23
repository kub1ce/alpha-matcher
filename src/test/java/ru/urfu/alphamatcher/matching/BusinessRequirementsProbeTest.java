package ru.urfu.alphamatcher.matching;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.urfu.alphamatcher.experiment.JdbcMatchingRepository;
import ru.urfu.alphamatcher.experiment.MatchingEngine;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;
import ru.urfu.alphamatcher.experiment.PurposeParser;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ALPHA_BR_DISPOSABLE", matches = "true")
class BusinessRequirementsProbeTest {
    private static final String INN = "990000000115";
    private static final String PAYER = "Иванов Иван Иванович";
    private static final String OTHER_PERSON = "Петров Пётр Петрович";
    private final MatchingEngine engine = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());

    @Test void evidenceForIncompleteAndConflictingPersonalRequisites() throws Exception {
        try (var db = open()) {
            configure(db, PAYER, INN, PAYER, INN);
            purpose(db, "Оплата по договору № D-12, ИНН " + INN);
            assertEquals(108L, observe(db, 16, "FIO positive control").proposedContractId());

            configure(db, PAYER, null, PAYER, INN);
            rejected(observe(db, 16, "Same FIO, missing INN"), "OPERATION_PARTIES");
            configure(db, PAYER, INN, PAYER, "990000000299");
            rejected(observe(db, 16, "Same FIO, different INN"), "PARTY_PAYER");
            configure(db, PAYER, INN, OTHER_PERSON, "990000000299");
            rejected(observe(db, 16, "Different FIO and INN"), "PARTY_PAYER");
            configure(db, PAYER, INN, OTHER_PERSON, INN);
            rejected(observe(db, 16, "Different FIO with same INN and no master client IDs"), "PARTY_PAYER");

            configure(db, PAYER, "990000000116", PAYER, INN);
            rejected(observe(db, 16, "Invalid INN in operation"), "OPERATION_PARTIES");
            configure(db, PAYER, INN, PAYER, INN);
            purpose(db, "Оплата по договору № D-12, ИНН " + INN + "; ФИО " + PAYER + "; ФИО " + OTHER_PERSON);
            var multiple = observe(db, 16, "Two individuals in purpose");
            assertEquals(Status.REVIEW, multiple.status());
            assertTrue(multiple.features().names().stream().anyMatch(name -> name.surface().contains("Петров")));
            purpose(db, null);
            rejected(observe(db, 16, "Missing purpose; no master client"), "SEARCH_KEYS");
            configure(db, PAYER, null, PAYER, INN);
            purpose(db, " ");
            rejected(observe(db, 16, "Combined missing purpose, INN, client"), "OPERATION_PARTIES");
        }
    }

    @Test void candidateAndThirdPartyControls() throws Exception {
        try (var db = open()) {
            assertEquals(101L, observe(db, 1, "Unique supported link").proposedContractId());
            var duplicates = observe(db, 2, "Duplicate contract numbers");
            rejected(duplicates, "EXPLICIT_UNIQUE_CONTRACT");
            assertEquals(List.of(102L, 103L), duplicates.candidateIds());
            rejected(observe(db, 5, "Reversed operation roles"), "PARTY_PAYER");
            rejected(observe(db, 14, "Inconsistent beneficiary"), "BENEFICIARY");
            rejected(observe(db, 8, "INN without contract number"), "EXPLICIT_UNIQUE_CONTRACT");
            rejected(observe(db, 12, "Missing recipient INN and client"), "OPERATION_PARTIES");

            String prefix = "По договору № D-12, ИНН " + INN + "; ";
            for (String text : List.of("оплата за третье лицо; ФИО " + PAYER,
                    "оплата не за третье лицо", "это цитата: «оплата за третье лицо»", "оплата по счёту № 77")) {
                purpose(db, prefix + text);
                observe(db, 16, text);
            }
        }
    }

    private static Connection open() throws Exception {
        // The owned runner provisions this catalog separately from alpha_matcher_ui_demo.
        var db = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:33368/matching_engine_exp?useSSL=false&allowPublicKeyRetrieval=true",
                "matcher", System.getenv("MATCHING_DB_PASSWORD"));
        db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        db.setAutoCommit(false);
        return db;
    }

    private static void configure(Connection db, String payer, String inn, String counterparty, String counterpartyInn)
            throws Exception {
        try (var op = db.prepareStatement("""
                UPDATE operation_party SET client_id=NULL, name=?, inn=? WHERE operation_id=16
                  AND role_id=(SELECT id FROM operation_party_role WHERE code='PAYER')
                """); var contract = db.prepareStatement("""
                UPDATE contract_party SET client_id=NULL, name=?, inn=? WHERE contract_id=108
                  AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER')
                """)) {
            op.setString(1, payer);
            op.setString(2, inn);
            op.executeUpdate();
            contract.setString(1, counterparty);
            contract.setString(2, counterpartyInn);
            contract.executeUpdate();
        }
    }

    private static void purpose(Connection db, String purpose) throws Exception {
        try (var row = db.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=16")) {
            row.setString(1, purpose);
            row.executeUpdate();
        }
    }

    private Decision observe(Connection db, int id, String scenario) throws Exception {
        var result = engine.match(db, id);
        System.out.println("BR " + scenario + " | thirdParty=" + result.features().thirdPartyAssertion()
                + " | mentions=" + result.features().mentions() + " | " + result);
        return result;
    }

    private static void rejected(Decision decision, String gate) {
        assertEquals(Status.REVIEW, decision.status());
        assertNull(decision.proposedContractId());
        assertTrue(decision.checks().stream().anyMatch(check -> gate.equals(check.code()) && !check.passed()),
                () -> gate + ": " + decision);
    }
}
