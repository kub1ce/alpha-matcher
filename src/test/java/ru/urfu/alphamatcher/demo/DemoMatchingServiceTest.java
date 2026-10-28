package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.demo.DemoMatchingService.Status;

class DemoMatchingServiceTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoMatchingService matching = new DemoMatchingService(data);
    private final DemoManualReviewService review = new DemoManualReviewService(data);
    private final DemoContractService contracts = new DemoContractService(data);
    private final DemoOperationState state = new DemoOperationState(data.originalFixtures());
    private final LocalDate from = LocalDate.of(2026, 10, 5);
    private final LocalDate to = LocalDate.of(2026, 10, 13);

    @Test
    void previewIsReadOnlyAndExistingManualWorkIsPreserved() {
        for (String id : Set.of("PAY-2026-000185", "PAY-2026-000186", "PAY-2026-000187",
                "PAY-2026-000190", "PAY-2026-000191", "PAY-2026-000195"))
            review.confirm(id, "AG-2026-014", "Проверены документы", true, state);
        assertEquals(0, review.pendingCount(state));
        int links = state.linkedCount();
        long revision = state.revision();
        assertEquals(0, matching.preview(state, from, to, false).rows().size());
        assertEquals(links, state.linkedCount());
        assertEquals(revision, state.revision());
        assertEquals(0, review.pendingCount(state));
        assertFalse(matching.loadTestBatch(state) && matching.loadTestBatch(state));
        assertEquals(0, review.pendingCount(state));
        assertEquals(2, matching.preview(state, from, to, false).count(Status.SAFE));
        assertNotNull(state.match("PAY-2026-000185"));
    }

    @Test
    void safeOnlyRequiresExplicitContractAndConsistentParties() {
        assertTrue(matching.loadTestBatch(state));
        assertFalse(matching.loadTestBatch(state));
        var preview = matching.preview(state, from, to, false);
        assertEquals(12, preview.rows().size());
        assertEquals(2, preview.count(Status.SAFE));
        assertEquals("AG-2026-014", preview.find("PAY-2026-000196").contractId());
        assertEquals("AG-2026-052", preview.find("PAY-2026-000197").contractId());
        for (String id : Set.of("PAY-2026-000198", "PAY-2026-000199"))
            assertEquals(Status.REVIEW, preview.find(id).status());
        for (String id : Set.of("PAY-2026-000200",
                "PAY-2026-000201")) assertEquals(Status.NO_CONTRACT, preview.find(id).status());
        for (String id : Set.of("PAY-2026-000185", "PAY-2026-000186", "PAY-2026-000187",
                "PAY-2026-000190", "PAY-2026-000191", "PAY-2026-000195"))
            assertEquals(Status.ALREADY_MANUAL, preview.find(id).status());
        assertEquals(6, review.pendingCount(state));
        assertEquals(6, state.linkedCount());
    }

    @Test
    void applyingSubsetSkipsUnsafeAndUpdatesSameContractProjection() {
        matching.loadTestBatch(state);
        var preview = matching.preview(state, from, to, false);
        int before = contracts.linkedOperations("AG-2026-014", state).size();
        var result = matching.apply(state, preview,
                Set.of("PAY-2026-000196", "PAY-2026-000198", "PAY-2026-000199"), "Сотрудник");
        assertEquals(1, result.applied());
        assertEquals(2, result.skipped());
        assertEquals("Автоматически", state.match("PAY-2026-000196").method());
        assertEquals(before + 1, contracts.linkedOperations("AG-2026-014", state).size());
        assertFalse(state.isLinked("PAY-2026-000198"));
        assertFalse(state.isManualQueued("PAY-2026-000198"));
        assertEquals(1, state.batchRuns().size());
        assertThrows(IllegalStateException.class, () -> matching.apply(state, preview,
                Set.of("PAY-2026-000196"), "Сотрудник"));
        assertEquals(before + 1, contracts.linkedOperations("AG-2026-014", state).size());
    }

    @Test
    void appliedAutomaticProposalKeepsItsOriginalReasonSeparateFromManualJustification() {
        matching.loadTestBatch(state);
        var preview = matching.preview(state, from, to, false);
        var proposal = preview.find("PAY-2026-000196");
        assertEquals(Status.SAFE, proposal.status());
        matching.apply(state, preview, Set.of(proposal.operationId()), DemoUser.ISAEV.fullName());
        var match = state.match(proposal.operationId());
        var event = state.auditEvents().getLast();
        assertEquals(proposal.reason(), match.automaticReason());
        assertEquals(proposal.reason(), event.automaticReason());
        assertTrue(match.justification().isBlank());
        assertTrue(event.justification().isBlank());
        matching.preview(state, from, to, true);
        assertEquals(proposal.reason(), state.auditEvents().getLast().automaticReason());
        state.unlink(proposal.operationId());
        assertEquals(proposal.reason(), state.auditEvents().getFirst().automaticReason());
    }

    @Test
    void repeatOfCompletedBatchCannotDuplicateRecordsAndNewBatchStillWorks() {
        matching.loadTestBatch(state);
        var preview = matching.preview(state, from, to, false);
        matching.apply(state, preview, Set.of("PAY-2026-000196"), DemoUser.ISAEV.fullName());
        assertThrows(IllegalStateException.class, () -> matching.apply(state, preview,
                Set.of("PAY-2026-000196"), DemoUser.ISAEV.fullName()));
        assertEquals(1, state.batchRuns().size());
        assertEquals(1, state.auditEvents().size());
        var next = matching.preview(state, from, to, false);
        matching.apply(state, next, Set.of("PAY-2026-000197"), DemoUser.ISAEV.fullName());
        assertEquals(2, state.batchRuns().size());
        assertEquals(2, state.auditEvents().size());
    }

    @Test
    void manualTransferIsExplicitIdempotentAndRejectsProcessed() {
        matching.loadTestBatch(state);
        var preview = matching.preview(state, from, to, true);
        var result = matching.sendToManual(state, preview,
                Set.of("PAY-2026-000196", "PAY-2026-000198", "PAY-2026-000184"), "Сотрудник");
        assertEquals(2, result.sentManual());
        assertEquals(1, result.skipped());
        assertEquals(8, review.pendingCount(state));
        assertEquals("Передано сотрудником из предпросмотра",
                review.reason(state.operation("PAY-2026-000196"), state));
        assertFalse(state.isManualQueued("PAY-2026-000184"));
        var current = matching.preview(state, from, to, true);
        assertThrows(IllegalArgumentException.class, () -> matching.sendToManual(state, current,
                Set.of("PAY-2026-000196", "PAY-2026-000198"), "Сотрудник"));
        assertEquals(8, review.pendingCount(state));
    }

    @Test
    void staleOrUnknownSelectionCausesNoPartialBatch() {
        matching.loadTestBatch(state);
        var preview = matching.preview(state, from, to, false);
        int linked = state.linkedCount();
        assertThrows(IllegalArgumentException.class, () -> matching.apply(state, preview,
                Set.of("PAY-2026-000196", "UNKNOWN"), "Сотрудник"));
        assertEquals(linked, state.linkedCount());
        assertEquals(0, state.batchRuns().size());
        state.queueForManual("PAY-2026-000198");
        assertThrows(IllegalStateException.class, () -> matching.apply(state, preview,
                Set.of("PAY-2026-000196"), "Сотрудник"));
        assertEquals(linked, state.linkedCount());
        assertEquals(0, state.batchRuns().size());
        assertThrows(IllegalArgumentException.class, () -> matching.preview(state, to, from, false));
    }

    @Test
    void mixedManualSelectionSkipsExistingQueueAndProcessedButTakesNoContract() {
        matching.loadTestBatch(state);
        var preview = matching.preview(state, from, to, true);
        var result = matching.sendToManual(state, preview,
                Set.of("PAY-2026-000196", "PAY-2026-000198", "PAY-2026-000200",
                        "PAY-2026-000185", "PAY-2026-000184"), DemoUser.ISAEV.fullName());
        assertEquals(3, result.sentManual());
        assertEquals(2, result.skipped());
        assertEquals(9, review.pendingCount(state));
        assertTrue(state.isManualQueued("PAY-2026-000200"));
        assertEquals(Status.ALREADY_MANUAL,
                matching.preview(state, from, to, true).find("PAY-2026-000200").status());
        assertFalse(state.isManualQueued("PAY-2026-000184"));
        assertEquals(DemoUser.ISAEV.fullName(), state.batchRuns().getFirst().initiator());
        var current = matching.preview(state, from, to, true);
        matching.apply(state, current, Set.of("PAY-2026-000197"), DemoUser.ROZHKOV.fullName());
        assertEquals(DemoUser.ISAEV.fullName(), state.batchRuns().getFirst().initiator());
        assertEquals(DemoUser.ROZHKOV.fullName(), state.batchRuns().getLast().initiator());
    }

    @Test
    void draftKeepsPreviewAndSelectionUntilRecalculation() {
        matching.loadTestBatch(state);
        DemoMatchingDraft draft = new DemoMatchingDraft();
        var first = matching.preview(state, from, to, false);
        draft.save(first, DemoUser.ISAEV.fullName());
        draft.selected().add("PAY-2026-000198");
        draft.hide();
        assertTrue(draft.hidden());
        assertEquals(first, draft.preview());
        assertEquals(Set.of("PAY-2026-000198"), draft.selected());
        assertEquals(DemoUser.ISAEV.fullName(), draft.previewAuthor());
        draft.restore();
        state.queueForManual("PAY-2026-000198");
        assertTrue(draft.preview().revision() != state.revision());
        draft.save(matching.preview(state, from, to, false), DemoUser.ROZHKOV.fullName());
        assertTrue(draft.selected().isEmpty());
    }
}
