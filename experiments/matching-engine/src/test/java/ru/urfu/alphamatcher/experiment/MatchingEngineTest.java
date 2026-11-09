package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class MatchingEngineTest {
    private final PurposeParser parser = new PurposeParser();
    private final Party payer = new Party(1L, "9900000017", "9900000017", true);
    private final Party recipient = new Party(2L, "9900000024", "9900000024", true);
    private final Map<String, Party> parties = Map.of("PAYER", payer, "RECIPIENT", recipient);
    private final Candidate contract = new Candidate(101, "D-001", parties);

    @ParameterizedTest
    @CsvSource({
            "'Договор D-001, ИНН 9900000017',SAFE",
            "'Договор D-001, ИНН 9900000018',REVIEW",
            "'Договор D-001, ИНН 9900000031',REVIEW",
            "'Договор D-001',REVIEW",
            "'Оплата за третье лицо, договор D-001, ИНН 9900000017',REVIEW",
            "'Договор D-001, договор D-002, ИНН 9900000017',REVIEW"
    })
    void safeRequiresAllSupportedEvidenceToAgree(String purpose, Status expected) {
        var decision = evaluate(purpose, parties, List.of(contract));
        assertEquals(expected, decision.status());
        assertEquals(expected == Status.SAFE ? 101L : null, decision.proposedContractId());
        assertFalse(decision.checks().isEmpty());
    }

    @Test
    void neverChoosesFirstOfDuplicateCandidates() {
        var decision = evaluate("Договор D-001, ИНН 9900000017", parties,
                List.of(contract, new Candidate(102, "D-001", parties)));
        assertEquals(Status.REVIEW, decision.status());
        assertEquals(List.of(101L, 102L), decision.candidateIds());
        assertNull(decision.proposedContractId());
    }

    @Test
    void distinguishesNoContractFromInsufficientEvidence() {
        assertEquals(Status.NO_CONTRACT, evaluate("Договор D-404, ИНН 9900000017", parties, List.of()).status());
        assertEquals(Status.REVIEW, evaluate("Оплата услуг", parties, List.of()).status());
        assertEquals(Status.REVIEW, evaluate("ИНН 9900000018", parties, List.of()).status());
    }

    @Test
    void rejectsReversedRolesMissingDataAndConflictingClientIdentity() {
        String purpose = "Договор D-001, ИНН 9900000017";
        assertEquals(Status.REVIEW, evaluate(purpose, Map.of("PAYER", recipient, "RECIPIENT", payer), List.of(contract)).status());
        assertEquals(Status.REVIEW, evaluate(purpose, Map.of("PAYER", payer), List.of(contract)).status());
        Party differentId = new Party(3L, payer.inn(), payer.clientInn(), true);
        assertEquals(Status.REVIEW, evaluate(purpose, Map.of("PAYER", differentId, "RECIPIENT", recipient), List.of(contract)).status());
        Party conflicting = new Party(1L, "9900000031", payer.clientInn(), true);
        assertEquals(Status.REVIEW, evaluate(purpose, Map.of("PAYER", conflicting, "RECIPIENT", recipient), List.of(contract)).status());
    }

    @Test
    void rejectsInactiveRoleAndSeparateBeneficiary() {
        String purpose = "Договор D-001, ИНН 9900000017";
        Party inactive = new Party(1L, payer.inn(), payer.clientInn(), false);
        assertEquals(Status.REVIEW, evaluate(purpose, Map.of("PAYER", inactive, "RECIPIENT", recipient), List.of(contract)).status());
        Candidate thirdParty = new Candidate(101, "D-001", Map.of("PAYER", payer, "RECIPIENT", recipient,
                "BENEFICIARY", new Party(3L, "9900000031", "9900000031", true)));
        assertEquals(Status.REVIEW, evaluate(purpose, parties, List.of(thirdParty)).status());
    }

    @Test
    void doesNotEquateVisuallySimilarLatinAndCyrillicLetters() {
        assertEquals(Status.REVIEW, evaluate("Договор А-001, ИНН 9900000017", parties,
                List.of(new Candidate(101, "A-001", parties))).status());
    }

    private Decision evaluate(String text, Map<String, Party> operationParties, List<Candidate> candidates) {
        return DiscoveryTestSupport.evaluate(new Operation(1, text, "LOADED", true, null, operationParties),
                parser.parse(text), candidates);
    }
}
