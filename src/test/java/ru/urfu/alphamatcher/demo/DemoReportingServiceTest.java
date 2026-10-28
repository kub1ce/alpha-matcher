package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DemoReportingServiceTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoReportingService reporting = new DemoReportingService();
    private final DemoMatchingService matching = new DemoMatchingService(data);
    private final DemoOperationState state = new DemoOperationState(data.originalFixtures());
    private final LocalDate from = LocalDate.of(2026, 10, 5);
    private final LocalDate to = LocalDate.of(2026, 10, 13);

    @Test
    @SuppressWarnings("unchecked")
    void legacySessionIdsAreNormalizedWithoutBreakingRunLinksOrColliding() throws Exception {
        Field runsField = DemoOperationState.class.getDeclaredField("batchRuns");
        runsField.setAccessible(true);
        List<DemoOperationState.BatchRun> runs = (List<DemoOperationState.BatchRun>) runsField.get(state);
        runs.add(testRun("RUN-001"));
        runs.add(testRun("RUN-DEMO-001"));

        Field auditField = DemoOperationState.class.getDeclaredField("auditEvents");
        auditField.setAccessible(true);
        List<DemoOperationState.AuditEvent> audit = (List<DemoOperationState.AuditEvent>) auditField.get(state);
        audit.add(testEvent("AUD-0001", "RUN-001"));
        audit.add(testEvent("AUD-DEMO-0001", "RUN-DEMO-001"));

        assertEquals(List.of("RUN-001", "RUN-002"), state.batchRuns().stream().map(DemoOperationState.BatchRun::id).toList());
        assertEquals(List.of("AUD-0001", "AUD-0002"), state.auditEvents().stream().map(DemoOperationState.AuditEvent::id).toList());
        assertEquals("RUN-002", state.auditEvents().getLast().runId());
        assertEquals(1, reporting.runs(state, "RUN-002", "", true, 0, 10).total());
        assertEquals("RUN-003", state.nextRunId());
        state.link("PAY-2026-000185", "AG-2026-014", "Вручную", data.contracts(),
                "Проверены документы", DemoUser.ISAEV.fullName(), null);
        assertEquals("AUD-0003", state.auditEvents().getLast().id());
    }

    private DemoOperationState.BatchRun testRun(String id) {
        return new DemoOperationState.BatchRun(id, LocalDateTime.of(2026, 10, 12, 12, 0),
                DemoUser.ISAEV.fullName(), from, to, 1, 1, 1, 1, 0, 0, 0, 0, 0,
                "Автоматическая привязка", new ArrayList<>(), false, false);
    }

    private DemoOperationState.AuditEvent testEvent(String id, String runId) {
        return new DemoOperationState.AuditEvent(id, LocalDateTime.of(2026, 10, 12, 12, 0),
                "PAY-2026-000185", "Автоматическая привязка", "Нет договора", "AG-2026-014",
                DemoUser.ISAEV.fullName(), "Автоматически", runId, null, null, null, null, null);
    }

    @Test
    void statisticsPartitionTheRegisterAndHandleEmptyDenominators() {
        var stats = reporting.statistics(state);
        assertEquals(12, stats.total());
        assertEquals(stats.total(), stats.automatic() + stats.manual() + stats.pending() + stats.other());
        assertEquals(stats.pending(), stats.reasons().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(DemoReportingService.percent(stats.automatic(), stats.processed()), stats.autoShare());
        var empty = reporting.statistics(new DemoOperationState(List.of()));
        assertEquals(0, empty.total());
        assertEquals(0, empty.autoShare());
        assertEquals(0, empty.percentOfQueue(0));
        assertEquals(0, reporting.runs(new DemoOperationState(List.of()), "", "", true, 0, 8).total());
        assertEquals(0, reporting.audit(new DemoOperationState(List.of()), "", "", "", true, 0, 8).total());
    }

    @Test
    void statisticsAndAuditFollowRealManualMutationsWithoutDuplicates() {
        int pending = reporting.statistics(state).pending();
        state.link("PAY-2026-000185", "AG-2026-014", "Вручную", data.contracts(),
                "Проверены документы", DemoUser.ISAEV.fullName(), null);
        assertEquals(pending - 1, reporting.statistics(state).pending());
        assertEquals("Ручная привязка", state.auditEvents().getLast().action());
        assertEquals("Нет договора (ручной разбор)", state.auditEvents().getLast().previous());
        assertEquals("AG-2026-014", state.auditEvents().getLast().current());
        state.link("PAY-2026-000185", "AG-2026-031", "Вручную", data.contracts(),
                "Решение сотрудника", DemoUser.ROZHKOV.fullName(), null);
        assertEquals("Изменение договора", state.auditEvents().getLast().action());
        assertEquals("AG-2026-014", state.auditEvents().getLast().previous());
        assertEquals("AG-2026-031", state.auditEvents().getLast().current());
        assertEquals(DemoUser.ISAEV.fullName(), state.auditEvents().getFirst().actor());
        assertEquals(DemoUser.ROZHKOV.fullName(), state.auditEvents().getLast().actor());
        int beforeNoOp = state.auditEvents().size();
        state.link("PAY-2026-000185", "AG-2026-031", "Вручную", data.contracts(),
                "Решение сотрудника", DemoUser.ROZHKOV.fullName(), null);
        assertEquals(beforeNoOp, state.auditEvents().size());
        state.unlink("PAY-2026-000185", DemoUser.ROZHKOV.fullName());
        assertEquals("Отвязка договора", state.auditEvents().getLast().action());
        assertEquals("AG-2026-031", state.auditEvents().getLast().previous());
        assertEquals(pending, reporting.statistics(state).pending());
        state.unlink("PAY-2026-000185", DemoUser.ROZHKOV.fullName());
        assertEquals(beforeNoOp + 1, state.auditEvents().size());
    }

    @Test
    void previewDoesNotCreateRunAndConfirmedBatchCreatesLinkedAudit() {
        matching.loadTestBatch(state);
        var preview = matching.preview(state, from, to, false);
        assertTrue(state.batchRuns().isEmpty());
        assertTrue(state.auditEvents().isEmpty());
        var result = matching.apply(state, preview, Set.of("PAY-2026-000196", "PAY-2026-000198"),
                DemoUser.ISAEV.fullName());
        assertEquals(1, result.applied());
        assertEquals(1, result.skipped());
        assertEquals(1, state.batchRuns().size());
        var run = state.batchRuns().getFirst();
        assertEquals(result.runId(), run.id());
        assertEquals(1, run.applied());
        assertEquals(1, run.eligible());
        assertEquals(List.of("PAY-2026-000196"), run.operationIds());
        assertEquals(1, state.auditEvents().size());
        assertEquals(run.id(), state.auditEvents().getFirst().runId());
        assertEquals("Автоматическая привязка", state.auditEvents().getFirst().action());
        assertEquals("Нет договора", state.auditEvents().getFirst().previous());
        assertEquals("AG-2026-014", state.auditEvents().getFirst().current());
        assertThrows(IllegalStateException.class, () -> matching.apply(state, preview,
                Set.of("PAY-2026-000196"), DemoUser.ISAEV.fullName()));
        assertEquals(1, state.batchRuns().size());
        assertEquals(1, state.auditEvents().size());
        assertEquals(1, reporting.runs(state, run.id(), "", true, 0, 8).total());
    }

    @Test
    void manualQueueActionsAndAuditFiltersUseActualEvents() {
        matching.loadTestBatch(state);
        var preview = matching.preview(state, from, to, true);
        matching.sendToManual(state, preview, Set.of("PAY-2026-000200", "PAY-2026-000185"),
                DemoUser.ROZHKOV.fullName());
        assertEquals(1, state.batchRuns().size());
        assertEquals("Передача на ручной разбор", state.batchRuns().getFirst().type());
        assertEquals(1, state.batchRuns().getFirst().manual());
        assertEquals(1, state.batchRuns().getFirst().alreadyQueued());
        assertEquals(1, state.auditEvents().size());
        assertEquals("PAY-2026-000200", state.auditEvents().getFirst().operationId());
        assertEquals(1, reporting.audit(state, "000200", DemoUser.ROZHKOV.fullName(),
                "Постановка на ручной разбор", true, 0, 10).total());
        assertEquals(0, reporting.audit(state, "000185", "", "", true, 0, 10).total());
        assertEquals(0, reporting.audit(state, "", DemoUser.ISAEV.fullName(), "", true, 0, 10).total());
        assertFalse(reporting.actors(state).isEmpty());
        assertEquals(1, reporting.audit(state, "", "", "", true, 20, 1).pages());
    }
}
