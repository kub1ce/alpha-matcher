package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemoContractServiceTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoContractService contracts = new DemoContractService(data);
    private final DemoOperationState state = new DemoOperationState(data.originalFixtures());

    @Test
    void searchCombinesPartyInnAndTypeAndPaginates() {
        var result = contracts.search(new DemoContractService.Query("6677889900", "Лизинг", "date", true),
                0, 8, state);
        assertEquals(1, result.total());
        assertEquals("AG-2026-081", result.rows().getFirst().id());
        assertEquals(0, contracts.search(new DemoContractService.Query("6677889900", "Поставка", "id", false),
                0, 8, state).total());
        assertEquals(1, contracts.search(new DemoContractService.Query("Петров", "", "id", false),
                0, 8, state).total());
        var first = contracts.search(new DemoContractService.Query("", "", "id", false), 0, 8, state);
        var last = contracts.search(new DemoContractService.Query("", "", "id", false), 3, 8, state);
        assertEquals(30, first.total());
        assertEquals(4, first.pages());
        assertEquals(6, last.rows().size());
        assertEquals("AG-2026-116", last.rows().getFirst().id());
        assertTrue(contracts.types().contains("Лизинг"));
    }

    @Test
    void linkedOperationsFollowConfirmedChangesInBothContracts() {
        assertEquals("PAY-2026-000192", contracts.linkedOperations("AG-2026-081", state).getFirst().id());
        assertEquals(2, contracts.linkedOperations("AG-2026-014", state).size());
        assertEquals(0, contracts.linkedOperations("AG-2026-021", state).size());
        state.link("PAY-2026-000184", "AG-2026-021", "Вручную", data.contracts(), "Проверены стороны");
        assertEquals(1, contracts.linkedOperations("AG-2026-014", state).size());
        assertEquals(1, contracts.linkedOperations("AG-2026-021", state).size());
        state.unlink("PAY-2026-000184");
        assertEquals(0, contracts.linkedOperations("AG-2026-021", state).size());
        assertEquals(1, contracts.linkedOperations("AG-2026-014", state).size());
    }

    @Test
    void missingContractDoesNotResolveOrGainOperations() {
        assertNull(contracts.contract("AG-NOT-FOUND"));
        assertTrue(contracts.linkedOperations("AG-NOT-FOUND", state).isEmpty());
    }
}
