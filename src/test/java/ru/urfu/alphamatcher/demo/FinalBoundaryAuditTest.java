package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;
import ru.urfu.alphamatcher.demo.DemoMatchingService.Status;
import ru.urfu.alphamatcher.ui.AppPagination;

class FinalBoundaryAuditTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoMatchingService matching = new DemoMatchingService(data);
    private final DemoManualReviewService review = new DemoManualReviewService(data);

    @Test
    void emptyManualQueueHasZeroCountAndStableEmptyPagination() {
        DemoOperationState state = new DemoOperationState(List.of());
        assertEquals(0, review.pendingCount(state));
        for (String text : List.of("", "несуществующий запрос")) {
            var page = review.search(text, "", 99, 10, state);
            assertEquals(0, page.total());
            assertEquals(0, page.page());
            assertEquals(1, page.pages());
            assertTrue(page.rows().isEmpty());
        }
        var reset = review.search("", "", 0, 10, state);
        assertEquals(0, reset.total());
        assertEquals(0, AppPagination.window(reset.total(), 99).start());
        assertTrue(state.auditEvents().isEmpty());
    }

    @Test
    void oneHundredThirtyMixedProposalsKeepExactSelectionAndActionCounts() {
        DemoOperationState state = new DemoOperationState(mixedOperations());
        var all = matching.preview(state, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13), true, true);
        assertEquals(130, all.rows().size());
        for (Status status : Status.values()) assertEquals(26, all.count(status));
        assertEquals(78, all.rows().stream().filter(proposal -> proposal.status() == Status.SAFE
                || proposal.status() == Status.REVIEW || proposal.status() == Status.NO_CONTRACT).count());
        assertEquals(13, AppPagination.window(130, 0).pages());
        assertEquals(0, AppPagination.window(130, 0).page());
        assertEquals(7, AppPagination.window(130, 7).page());
        assertEquals(12, AppPagination.window(130, 999).page());
        assertEquals(121, AppPagination.window(130, 12).start());

        DemoMatchingDraft draft = new DemoMatchingDraft();
        draft.save(all, "Аудит");
        draft.selectPage(all.rows().subList(0, 10), true);
        assertEquals(10, draft.selected().size());
        draft.selectResults(all.rows());
        assertEquals(130, draft.selected().size());
        draft.page(7);
        draft.selectPage(all.rows().subList(70, 73), false);
        assertEquals(127, draft.selected().size());
        assertEquals(7, draft.page());
        assertTrue(draft.selected().contains(all.rows().getFirst().operationId()));
        draft.selectPage(all.rows().subList(70, 71), true);
        assertEquals(128, draft.selected().size());
        draft.selected().clear();
        assertTrue(draft.selected().isEmpty());
        draft.selectPage(all.rows().subList(0, 10), true);
        assertEquals(10, draft.selected().size());

        var filtered = matching.preview(state, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), true, true);
        assertEquals(70, filtered.rows().size());
        assertEquals(7, AppPagination.window(filtered.rows().size(), 999).pages());
        draft.save(filtered, "Аудит");
        assertTrue(draft.selected().isEmpty());
        assertEquals(0, draft.page());

        var applied = matching.apply(state, all,
                all.rows().stream().map(DemoMatchingService.Proposal::operationId).collect(java.util.stream.Collectors.toSet()),
                DemoUser.ISAEV.fullName());
        assertEquals(130, applied.selected());
        assertEquals(26, applied.applied());
        assertEquals(104, applied.skipped());
        assertEquals(26, state.auditEvents().size());
        assertEquals(1, state.batchRuns().size());
        assertEquals(26, state.batchRuns().getFirst().applied());
    }

    @Test
    void parallelCallsOnOneDemoStateApplyOnlyOnceForEachAction() throws Exception {
        for (boolean manual : List.of(false, true)) {
            DemoOperationState state = new DemoOperationState(data.originalFixtures());
            matching.loadTestBatch(state);
            var preview = matching.preview(state, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), false);
            String id = "PAY-2026-000196";
            assertEquals(Status.SAFE, preview.find(id).status());
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<Boolean> action = () -> {
                    start.await();
                    try {
                        if (manual) matching.sendToManual(state, preview, Set.of(id), "Аудит");
                        else matching.apply(state, preview, Set.of(id), "Аудит");
                        return true;
                    } catch (IllegalStateException | IllegalArgumentException expected) {
                        return false;
                    }
                };
                Future<Boolean> first = pool.submit(action);
                Future<Boolean> second = pool.submit(action);
                start.countDown();
                assertNotEquals(first.get(), second.get());
            }
            assertEquals(1, state.batchRuns().size());
            assertEquals(1, state.auditEvents().size());
            assertEquals(manual, state.isManualQueued(id));
            assertEquals(!manual, state.isLinked(id));
            assertThrows(IllegalStateException.class, () -> {
                if (manual) matching.sendToManual(state, preview, Set.of(id), "Аудит");
                else matching.apply(state, preview, Set.of(id), "Аудит");
            });
            assertEquals(1, state.batchRuns().size());
        }
    }

    @Test
    void previewAgeAloneDoesNotInvalidateItButChangedLinkDoes() throws Exception {
        DemoOperationState state = new DemoOperationState(data.originalFixtures());
        matching.loadTestBatch(state);
        var preview = matching.preview(state, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), false);
        DemoMatchingDraft draft = new DemoMatchingDraft();
        draft.save(preview, "Аудит");
        var timestamp = DemoMatchingDraft.class.getDeclaredField("previewAt");
        timestamp.setAccessible(true);
        timestamp.set(draft, LocalDateTime.now().minusYears(1));
        assertEquals(preview.fingerprint(), matching.fingerprint(state, preview.from(), preview.to()));
        assertEquals(Status.SAFE, preview.find("PAY-2026-000196").status());
        DemoOperationState unchanged = new DemoOperationState(data.originalFixtures());
        matching.loadTestBatch(unchanged);
        var unchangedPreview = matching.preview(unchanged, LocalDate.of(2026, 10, 12),
                LocalDate.of(2026, 10, 12), false);
        DemoMatchingDraft oldDraft = new DemoMatchingDraft();
        oldDraft.save(unchangedPreview, "Аудит");
        timestamp.set(oldDraft, LocalDateTime.now().minusYears(1));
        assertEquals(1, matching.apply(unchanged, oldDraft.preview(),
                Set.of("PAY-2026-000196"), "Аудит").applied());
        state.unlink("PAY-2026-000184");
        int audits = state.auditEvents().size();
        assertNotEquals(preview.fingerprint(), matching.fingerprint(state, preview.from(), preview.to()));
        assertThrows(IllegalStateException.class, () -> matching.apply(state, preview,
                Set.of("PAY-2026-000196"), "Аудит"));
        assertEquals(audits, state.auditEvents().size());
        assertTrue(state.batchRuns().isEmpty());
    }

    @Test
    void missingRelatedEntitiesRemainUnresolvedWithoutFalseMutation() {
        Operation template = data.originalFixtures().stream()
                .filter(operation -> operation.id().equals("PAY-2026-000184")).findFirst().orElseThrow();
        Operation broken = new Operation("PAY-2026-990001", template.timestamp(), template.payer(),
                template.recipient(), BigDecimal.TEN, "Проверочный платёж", "AG-MISSING", "Вручную", "", List.of());
        DemoOperationState state = new DemoOperationState(List.of(broken));
        assertNull(data.contract(state.match(broken.id()).contractId()));
        assertThrows(IllegalArgumentException.class, () -> state.link(broken.id(), "AG-MISSING",
                "Вручную", data.contracts(), "Проверены реквизиты"));
        assertEquals("AG-MISSING", state.match(broken.id()).contractId());
        assertTrue(state.auditEvents().isEmpty());

        var missingClientContract = new DemoDataService.Contract("AG-QA", "Проверочный договор",
                "12.10.2026", template.payer().name(), template.recipient().name(), "Поставка", null,
                "CL-MISSING", "CL-MISSING-2");
        DemoDataService isolated = new DemoDataService() {
            @Override public List<DemoDataService.Contract> contracts() { return List.of(missingClientContract); }
        };
        assertNull(isolated.client(missingClientContract.payerId(), state));
        assertNull(isolated.client(missingClientContract.recipientId(), state));
        var result = new DemoContractService(isolated).search(
                new DemoContractService.Query("AG-QA", "", "id", false), 0, 10, state);
        assertEquals(1, result.total());
        assertEquals("AG-QA", result.rows().getFirst().id());
        assertTrue(state.auditEvents().isEmpty());
    }

    private List<Operation> mixedOperations() {
        Operation template = data.originalFixtures().stream()
                .filter(operation -> operation.id().equals("PAY-2026-000184")).findFirst().orElseThrow();
        List<Operation> operations = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            String id = "PAY-2026-%06d".formatted(800000 + index);
            LocalDateTime time = LocalDateTime.of(2026, 10, index < 70 ? 12 : 13, 10, index % 60);
            int kind = index % 5;
            operations.add(new Operation(id, time, template.payer(), template.recipient(),
                    BigDecimal.valueOf(10000 + index),
                    kind == 0 ? "Оплата по договору AG-2026-014" : "Оплата услуг",
                    kind == 4 ? "AG-2026-014" : "", kind == 4 ? "Автоматически" : "",
                    kind == 3 ? "Недостаточно данных" : "",
                    switch (kind) {
                        case 0, 3, 4 -> List.of("AG-2026-014");
                        case 1 -> List.of("AG-2026-014", "AG-2026-021");
                        default -> List.of();
                    }));
        }
        return operations;
    }
}
