package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemoManualReviewServiceTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoManualReviewService review = new DemoManualReviewService(data);
    private final DemoContractService contracts = new DemoContractService(data);
    private final DemoOperationState state = new DemoOperationState(data.originalFixtures());

    @Test
    void queueSearchCombinesTextAndActualReason() {
        assertEquals(6, review.search("", "", 0, 8, state).total());
        assertEquals(6, review.pendingCount(state));
        var found = review.search("Ромашка", "Платеж за третье лицо", 0, 8, state);
        assertEquals(1, found.total());
        assertEquals("PAY-2026-000185", found.rows().getFirst().id());
        assertEquals(0, review.search("Иванова", "Конфликт ИНН плательщика", 0, 8, state).total());
        assertTrue(review.reasons(state).contains("Неполные сведения о получателе"));
        assertEquals(2, review.candidates(state.operation("PAY-2026-000187"), state).size());
        assertTrue(review.candidates(state.operation("PAY-2026-000186"), state).isEmpty());
        assertEquals(6, review.pendingCount(state));
    }

    @Test
    void pendingCountMovesFromManyToOneToZeroAndBack() {
        String[] ids = {"PAY-2026-000185", "PAY-2026-000186", "PAY-2026-000187",
                "PAY-2026-000190", "PAY-2026-000191", "PAY-2026-000195"};
        for (int i = 0; i < ids.length - 1; i++)
            review.confirm(ids[i], "AG-2026-014", "Проверено сотрудником", true, state);
        assertEquals(1, review.pendingCount(state));
        review.confirm(ids[5], "AG-2026-014", "Проверено сотрудником", true, state);
        assertEquals(0, review.pendingCount(state));
        assertEquals(0, review.search("не найдено", "", 0, 8, state).total());
        state.unlink(ids[5]);
        assertEquals(1, review.pendingCount(state));
    }

    @Test
    void choosingAndConfirmingAreSeparateActions() {
        String id = "PAY-2026-000187";
        assertThrows(IllegalArgumentException.class, () -> review.confirm(id, "AG-2026-044", "Проверено", false, state));
        assertThrows(IllegalArgumentException.class, () -> review.confirm(id, "", "Проверено", true, state));
        assertThrows(IllegalArgumentException.class, () -> review.confirm(id, "AG-2026-044", "", true, state));
        assertFalse(state.isLinked(id));
        assertEquals(6, review.search("", "", 0, 8, state).total());
        review.confirm(id, "AG-2026-044", "Проверены стороны и предмет договора", true, state);
        assertEquals("AG-2026-044", state.match(id).contractId());
        assertEquals("Вручную", state.match(id).method());
        assertEquals(5, data.manualItems(state).size());
        assertEquals(1, contracts.linkedOperations("AG-2026-044", state).size());
        assertEquals(1, state.search(new DemoOperationState.Query(id, null, null,
                "Обработана", "Вручную", "date", true), 0, 8).total());
        assertThrows(IllegalStateException.class, () -> review.confirm(id, "AG-2026-081", "Другой договор", true, state));
        state.unlink(id);
        assertEquals(6, data.manualItems(state).size());
        assertEquals(0, contracts.linkedOperations("AG-2026-044", state).size());
    }

    @Test
    void conflictNeedsJustificationAndDoesNotApplyUnconfirmedCandidate() {
        String id = "PAY-2026-000190";
        var candidate = data.contract("AG-2026-014");
        assertEquals(1, review.candidates(state.operation(id), state).size());
        assertTrue(review.warnings(state.operation(id), candidate, state).stream()
                .anyMatch(w -> w.contains("ИНН")));
        assertThrows(IllegalArgumentException.class, () -> review.confirm(id, candidate.id(), "", true, state));
        assertFalse(state.isLinked(id));
        review.confirm(id, candidate.id(), "Проверен платёж за группу компаний", true, state);
        assertEquals(candidate.id(), state.match(id).contractId());
        assertEquals("Проверен платёж за группу компаний", state.match(id).justification());
    }
}
