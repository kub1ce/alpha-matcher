package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DemoExpandedFixturesTest {
    private final DemoDataService data = new ru.urfu.alphamatcher.demo.EngineStubDataService();
    private final DemoMatchingService matching = new DemoMatchingService(data);
    private final DemoReportingService reporting = new DemoReportingService();

    @Test
    void generatedReferencesAndReportingRemainConsistent() {
        var state = new DemoOperationState(data.fixtures());
        assertEquals(96, state.all().size());
        assertEquals(30, data.contracts().size());
        assertEquals(31, data.parties().size());
        assertEquals(96, state.all().stream().map(DemoDataService.Operation::id).distinct().count());
        assertEquals(30, data.contracts().stream().map(DemoDataService.Contract::id).distinct().count());
        assertEquals(31, data.parties().stream().map(DemoDataService.Party::id).distinct().count());
        for (var contract : data.contracts()) {
            assertNotNull(data.party(contract.payer()));
            assertNotNull(data.party(contract.recipient()));
        }
        for (var operation : state.all()) {
            assertNotNull(operation.payer());
            assertNotNull(operation.recipient());
            if (!operation.initialContract().isBlank()) assertNotNull(data.contract(operation.initialContract()));
            for (String candidate : operation.candidates()) assertNotNull(data.contract(candidate));
        }
        var stats = reporting.statistics(state);
        assertEquals(stats.total(), stats.processed() + stats.pending() + stats.other());
        assertEquals(stats.pending(), stats.reasons().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(stats.pending() > 30);
        assertEquals(0, state.auditEvents().size());
        var contracts = new DemoContractService(data);
        assertEquals(state.linkedCount(), data.contracts().stream()
                .mapToInt(contract -> contracts.linkedOperations(contract.id(), state).size()).sum());
        var seeded = state.match("PAY-2026-000204");
        assertEquals("Вручную", seeded.method());
        assertFalse(seeded.justification().isBlank());
        assertNotNull(seeded.decisionAt());
    }

    @Test
    void expandedRegistriesSupportSearchSortingAndPagination() {
        var state = new DemoOperationState(data.fixtures());
        var query = new DemoOperationState.Query("", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 12), "", "", "date", true);
        var first = state.search(query, 0, 8);
        var last = state.search(query, 11, 8);
        assertEquals(96, first.total());
        assertEquals(12, first.pages());
        assertEquals(8, first.rows().size());
        assertEquals(8, last.rows().size());
        assertEquals(1, state.search(new DemoOperationState.Query("PAY-2026-000203",
                null, null, "", "", "date", true), 0, 8).total());
        var contracts = new DemoContractService(data);
        assertEquals(30, contracts.search(new DemoContractService.Query("", "", "id", false),
                0, 8, state).total());
    }

    @Test
    void oldSessionGetsMissingSeedsWithoutLosingConfirmedActions() {
        var state = new DemoOperationState(data.originalFixtures());
        state.link("PAY-2026-000185", "AG-2026-031", "Вручную", data.contracts(),
                "Проверены документы и полномочия", DemoUser.ISAEV.fullName(), null);
        int auditSize = state.auditEvents().size();
        var original = state.match("PAY-2026-000185");
        state.mergeSeedOperations(data.fixtures());
        state.mergeSeedOperations(data.fixtures());
        assertEquals(96, state.all().size());
        assertEquals(original, state.match("PAY-2026-000185"));
        assertEquals(auditSize, state.auditEvents().size());
        assertEquals("RUN-001", state.nextRunId());
        assertEquals("RUN-002", state.nextRunId());
    }

    @Test
    void previewStaysValidAfterUnrelatedChangeButDetectsRelevantChange() {
        var state = new DemoOperationState(data.fixtures());
        var preview = matching.preview(state, LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 12), false);
        int fingerprint = preview.fingerprint();
        state.queueForManual("PAY-2026-000203", "Проверка", DemoUser.ISAEV.fullName(), null);
        assertNotEquals(preview.revision(), state.revision());
        assertEquals(fingerprint, matching.fingerprint(state, preview.from(), preview.to()));
        state.link("PAY-2026-000185", "AG-2026-031", "Вручную", data.contracts(),
                "Проверены документы", DemoUser.ISAEV.fullName(), null);
        assertNotEquals(fingerprint, matching.fingerprint(state, preview.from(), preview.to()));
    }

    @Test
    void manualQueueVisibilityIsIndependentOfProcessedFilter() {
        var state = new DemoOperationState(data.fixtures());
        var from = LocalDate.of(2026, 9, 1);
        var to = LocalDate.of(2026, 10, 12);
        var hidden = matching.preview(state, from, to, false, false);
        var shown = matching.preview(state, from, to, false, true);
        assertEquals(0, hidden.count(DemoMatchingService.Status.ALREADY_MANUAL));
        assertTrue(shown.count(DemoMatchingService.Status.ALREADY_MANUAL) > 40);
        assertEquals(shown.rows().size() - shown.count(DemoMatchingService.Status.ALREADY_MANUAL),
                hidden.rows().size());
        var withProcessed = matching.preview(state, from, to, true, false);
        assertEquals(0, withProcessed.count(DemoMatchingService.Status.ALREADY_MANUAL));
        assertTrue(withProcessed.count(DemoMatchingService.Status.SKIPPED) > 0);
    }

    @Test
    void manualDecisionAndOriginalReasonSurviveLaterUnlinkInAudit() {
        var state = new DemoOperationState(data.fixtures());
        String reason = state.operation("PAY-2026-000185").reason();
        state.link("PAY-2026-000185", "AG-2026-031", "Вручную", data.contracts(),
                "Проверены документы", DemoUser.ROZHKOV.fullName(), null);
        assertEquals(reason, state.match("PAY-2026-000185").reviewReason());
        assertEquals("Проверены документы", state.match("PAY-2026-000185").justification());
        state.unlink("PAY-2026-000185", DemoUser.ROZHKOV.fullName());
        assertEquals("Проверены документы", state.auditEvents().getLast().previousJustification());
        assertEquals(reason, state.auditEvents().getLast().reviewReason());
        assertEquals(reporting.statistics(state).pending(),
                reporting.statistics(state).reasons().values().stream().mapToInt(Integer::intValue).sum());
    }
}
