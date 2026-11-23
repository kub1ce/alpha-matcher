package ru.urfu.alphamatcher.experiment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;

import static org.junit.jupiter.api.Assertions.*;

class M5FinancialOracleMySqlIT {
    private static final String NUMBER = "M5-153";
    private static final String PAYER_INN = "9900000017";
    private static final String RECIPIENT_INN = "9900000024";
    private static final String THIRD_INN = "9900000031";
    private static final List<String> CONTEXTS = List.of(
            "Оплата по договору № %s", "Договор № %s не действует",
            "Платёж не касается договора № %s", "Платеж НЕ относится к договору № %s",
            "Платёж не связан с договором № %s", "Договор № %s утратил силу",
            "Договор № %s аннулирован", "Договор № %s отменен",
            "Не оплачивать по договору № %s", "Платёж не направлен на погашение по договору № %s",
            "Договор № %s не подлежит оплате", "Ранее действовавший договор № %s",
            "Не считать оплатой по договору № %s", "Номер договора № %s приведен справочно",
            "\"Договор № %s, ИНН 9900000017\". Это цитата из отмененного письма");

    // The expected IDs are facts of the synthetic catalog, independent of parser and search output.
    private enum Catalog {
        EXACT(NUMBER, "M5-153-SUFFIX", List.of(101L)),
        DUPLICATE(NUMBER, NUMBER, List.of(101L, 102L)),
        PREFIX_ONLY("M5-1530", "M5-153-SUFFIX", List.of()),
        CONFUSABLE_ONLY("М5-153", "M5-153-SUFFIX", List.of());

        final String first;
        final String second;
        final List<Long> ids;

        Catalog(String first, String second, List<Long> ids) {
            this.first = first;
            this.second = second;
            this.ids = ids;
        }
    }

    @Test
    void bindingIdentityRolesAndCatalogCollisionsHaveIndependentExpectedDecisions() throws Exception {
        var engine = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());
        try (var c = ExperimentDatabase.connect()) {
            try {
                for (Catalog catalog : Catalog.values()) {
                    set(c, "UPDATE contract SET contract_number=? WHERE id=101", catalog.first);
                    set(c, "UPDATE contract SET contract_number=? WHERE id=102", catalog.second);
                    for (int contractPayer : List.of(1, 2, 3)) {
                        try (var query = c.prepareStatement("""
                                UPDATE contract_party SET client_id=?
                                WHERE contract_id=101 AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER')
                                """)) {
                            query.setInt(1, contractPayer);
                            assertEquals(1, query.executeUpdate());
                        }
                        for (String context : CONTEXTS) {
                            for (String inn : List.of(PAYER_INN, RECIPIENT_INN, THIRD_INN, "9900000018")) {
                                for (String claim : List.of("", "Плательщик: ", "Получатель: ")) {
                                    String purpose = context.formatted(NUMBER) + "; " + claim + "ИНН " + inn;
                                    set(c, "UPDATE operation SET payment_purpose=? WHERE id=1", purpose);
                                    boolean affirmed = context.equals(CONTEXTS.getFirst());
                                    boolean supportedInn = !inn.equals("9900000018");
                                    boolean consistentIdentity = inn.equals(PAYER_INN) || inn.equals(RECIPIENT_INN);
                                    boolean consistentClaim = claim.isEmpty()
                                            || claim.startsWith("Плательщик") && inn.equals(PAYER_INN)
                                            || claim.startsWith("Получатель") && inn.equals(RECIPIENT_INN);
                                    Status expected = Status.REVIEW;
                                    if (affirmed && supportedInn && consistentIdentity && consistentClaim) {
                                        if (catalog.ids.isEmpty()) expected = Status.NO_CONTRACT;
                                        if (catalog == Catalog.EXACT && contractPayer == 1) expected = Status.SAFE;
                                    }
                                    var d = engine.match(c, 1);
                                    String scenario = catalog + "/" + contractPayer + "/" + purpose;
                                    assertAll(scenario,
                                            () -> assertEquals(catalog.ids, d.candidateIds(), d.toString()),
                                            () -> assertEquals(CandidateSearch.Origin.JDBC, d.searchEvidence().origin()),
                                            () -> assertEquals(PurposeParser.Completeness.COMPLETE, d.searchEvidence().completeness()),
                                            () -> assertEquals(d.searchEvidence().requiredKeys(), d.searchEvidence().searchedKeys()),
                                            () -> assertTrue(d.features().contractSearchNumbers().contains(NUMBER)),
                                            () -> assertEquals(affirmed && supportedInn, d.checks().stream()
                                                    .anyMatch(k -> k.code().equals("INPUT_VALID") && k.passed()), d.toString()));
                                    assertEquals(expected, d.status(), d.toString());
                                    assertEquals(expected == Status.SAFE ? 101L : null, d.proposedContractId());
                                    if (expected == Status.SAFE) assertTrue(d.checks().stream().allMatch(MatchingModel.Check::passed));
                                    if (expected == Status.NO_CONTRACT) SafeDiscoveryTest.passed(d, "ABSENCE_EVIDENCE_COMPLETE");
                                }
                            }
                        }
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static void set(Connection c, String sql, String value) throws SQLException {
        try (var update = c.prepareStatement(sql)) {
            update.setString(1, value);
            assertEquals(1, update.executeUpdate());
        }
    }
}
