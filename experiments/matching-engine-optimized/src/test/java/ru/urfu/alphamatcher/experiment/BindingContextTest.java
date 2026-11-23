package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;

import static org.junit.jupiter.api.Assertions.*;
import static ru.urfu.alphamatcher.experiment.CorrectnessRemediationTest.CONTRACT;
import static ru.urfu.alphamatcher.experiment.CorrectnessRemediationTest.decide;

class BindingContextTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Оплата не по договору ZX-583, ИНН 9900000017",
            "\"Оплата по договору ZX-583, ИНН 9900000017\"",
            "Договор № ZX-583 не действует, ИНН 9900000017",
            "Договор № \"ZX-583\" не действует, ИНН 9900000017",
            "Платёж не касается договора ZX-583, ИНН 9900000017",
            "Платёж не связан с договором № ZX-583, ИНН 9900000017",
            "Договор № ZX-583 утратил силу, ИНН 9900000017",
            "Договор № ZX-583 аннулирован, ИНН 9900000017",
            "Договор № ZX-583 отменен, ИНН 9900000017",
            "Не оплачивать по договору № ZX-583, ИНН 9900000017",
            "Платёж не направлен на погашение по договору № ZX-583, ИНН 9900000017",
            "Договор № ZX-583 не подлежит оплате, ИНН 9900000017",
            "Оплата по договору № ZX-583, кроме суммы по договору № ZX-583, ИНН 9900000017",
            "Ранее действовавший договор № ZX-583, ИНН 9900000017",
            "Не считать оплатой по договору № ZX-583, ИНН 9900000017",
            "Номер договора № ZX-583 приведен справочно, ИНН 9900000017",
            "Оплата по договору ZX-583, ИНН 9900000017; \uD800",
            "Оплата по договору ZX-583, ИНН 9900000017; \uDC00"
    })
    void nonBindingReferenceRemainsDiscoverableWithoutAuthorizingLink(String purpose) {
        var decision = decide(purpose, List.of(CONTRACT));
        assertAll(purpose,
                () -> assertEquals(Status.REVIEW, decision.status(), decision.toString()),
                () -> assertNull(decision.proposedContractId()),
                () -> assertEquals(List.of(101L), decision.candidateIds()),
                () -> assertTrue(decision.features().contractSearchNumbers().contains("ZX-583")),
                () -> assertTrue(decision.checks().stream()
                        .anyMatch(check -> check.code().equals("INPUT_VALID") && !check.passed())));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Оплата по договору ZX-583, ИНН 9900000017",
            "Оплата по договору \"ZX-583\", ИНН 9900000017",
            "Оплата по договору ZX-583, ИНН 9900000017; скидка не действует",
            "Оплата по договору № ZX-583, ИНН 9900000017, счет не подлежит повторной оплате",
            "Оплата по договору ZX-583, ИНН 9900000017; \uD83D\uDE00"
    })
    void affirmedContractIncludingQuotedNumberCanStillAuthorizeLink(String purpose) {
        var decision = decide(purpose, List.of(CONTRACT));
        assertEquals(Status.SAFE, decision.status(), decision.toString());
        assertEquals(101L, decision.proposedContractId());
        assertEquals(List.of(101L), decision.candidateIds());
        assertTrue(decision.checks().stream().allMatch(MatchingModel.Check::passed));
    }
}
