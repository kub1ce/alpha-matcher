package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.time.LocalDate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DemoDataServiceTest {
    private final DemoDataService data = new DemoDataService();

    @Test
    void dashboardFixturesStayConsistent() {
        Set<String> visibleIds = data.operations().stream()
                .map(DemoDataService.Operation::id).collect(Collectors.toSet());
        assertEquals(51, data.manualItems().size());
        assertTrue(data.manualItems().stream().filter(item -> visibleIds.contains(item.operationId()))
                .allMatch(item -> data.operations().stream()
                        .anyMatch(operation -> operation.id().equals(item.operationId()) && !data.state().isLinked(operation.id()))));
        assertTrue(data.operations().stream().allMatch(operation -> data.state().isLinked(operation.id()) == !operation.initialContract().isEmpty()));
        assertTrue(data.runs().stream().allMatch(run -> run.matched() + run.manual() <= run.total()));
    }

    @Test
    void searchCombinesTextDatesStatusAndMethod() {
        DemoOperationState state = new DemoOperationState(data.originalFixtures());
        var query = new DemoOperationState.Query("6678123456", LocalDate.of(2026, 10, 11),
                LocalDate.of(2026, 10, 12), "Обработана", "Автоматически", "date", true);
        var result = state.search(query, 0, 8);
        assertEquals(2, result.total());
        assertEquals("PAY-2026-000184", result.rows().getFirst().id());
        assertEquals(1, state.search(new DemoOperationState.Query("Иванова Ивана", null, null,
                "Не обработана", "", "date", true), 0, 8).total());
        assertEquals(0, state.search(new DemoOperationState.Query("Иванова Ивана", null, null,
                "Не обработана", "Автоматически", "date", true), 0, 8).total());
    }

    @Test
    void paginationUsesFilteredRowsAndKeepsSelection() {
        DemoOperationState state = new DemoOperationState(data.originalFixtures());
        var query = new DemoOperationState.Query("", null, null, "", "", "date", true);
        var first = state.search(query, 0, 8);
        var second = state.search(query, 1, 8);
        assertEquals(12, first.total());
        assertEquals(2, first.pages());
        assertEquals(8, first.rows().size());
        assertEquals(4, second.rows().size());
        assertEquals("PAY-2026-000187", first.rows().getFirst().id());
        assertEquals("PAY-2026-000187", state.operation(first.rows().getFirst().id()).id());
        assertEquals("PAY-2026-000190", state.search(new DemoOperationState.Query("", null, null,
                "", "", "amount", false), 0, 8).rows().getFirst().id());
    }

    @Test
    void pageIsClampedWhenFilteredResultShrinks() {
        DemoOperationState state = new DemoOperationState(data.originalFixtures());
        var query = new DemoOperationState.Query("PAY-2026-000184", null, null,
                "", "", "date", true);
        var result = state.search(query, 9, 10);
        assertEquals(0, result.page());
        assertEquals(1, result.total());
        assertEquals("PAY-2026-000184", result.rows().getFirst().id());
    }

    @Test
    void operationHistoryContainsOnlyThatOperationsExistingAuditEvents() {
        DemoOperationState state = new DemoOperationState(data.originalFixtures());
        state.link("PAY-2026-000185", "AG-2026-031", "Вручную", data.contracts(), "Проверены реквизиты");
        state.unlink("PAY-2026-000184");
        var history = state.auditEventsForOperation("PAY-2026-000185");
        assertEquals(2, state.auditEvents().size());
        assertEquals(1, history.size());
        assertEquals("PAY-2026-000185", history.getFirst().operationId());
        assertEquals("Проверены реквизиты", history.getFirst().justification());
        assertTrue(state.auditEventsForOperation("PAY-2026-000186").isEmpty());
        assertTrue(state.auditEventsForOperation("INVALID").isEmpty());
    }

    @Test
    void confirmedMatchAndUnlinkStayInOneState() {
        DemoOperationState state = new DemoOperationState(data.originalFixtures());
        String id = "PAY-2026-000187";
        assertFalse(state.isLinked(id));
        assertEquals(2, state.operation(id).candidates().size());
        int before = state.linkedCount();
        state.link(id, "AG-2026-044", "Вручную", data.contracts(), "Проверены стороны");
        assertEquals(before + 1, state.linkedCount());
        assertEquals(5, data.manualItems(state).size());
        assertEquals("AG-2026-044", state.match(id).contractId());
        assertEquals(1, state.search(new DemoOperationState.Query(id, null, null,
                "Обработана", "Вручную", "date", true), 0, 8).total());
        state.link("PAY-2026-000184", "AG-2026-021", "Вручную", data.contracts(), "Проверены стороны");
        assertEquals("AG-2026-021", state.match("PAY-2026-000184").contractId());
        state.unlink(id);
        assertFalse(state.isLinked(id));
        assertEquals(before, state.linkedCount());
        assertEquals(6, data.manualItems(state).size());
        state.unlink("PAY-2026-000184");
        assertEquals(7, data.manualItems(state).size());
        assertEquals("AG-2026-021", state.previousContract("PAY-2026-000184"));
        assertThrows(IllegalArgumentException.class, () -> state.link(id, "AG-UNKNOWN", "Вручную", data.contracts()));
        assertThrows(IllegalArgumentException.class, () -> state.link("PAY-2026-000184", "AG-2026-052", "Вручную", data.contracts()));
    }
}
