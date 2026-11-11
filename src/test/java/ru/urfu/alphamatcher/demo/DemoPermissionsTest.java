package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DemoPermissionsTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoManualReviewService review = new DemoManualReviewService(data);
    private final DemoMatchingService matching = new DemoMatchingService(data);
    private final DemoOperationState state = new DemoOperationState(data.originalFixtures());

    private void as(DemoRole role) {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        VaadinSession.setCurrent(session);
        DemoSession.login(role);
    }

    @AfterEach
    void clearSession() { VaadinSession.setCurrent(null); }

    @ParameterizedTest
    @EnumSource(DemoRole.class)
    void safeManualDecisionAndBatchAreAllowedForBothUsers(DemoRole role) {
        as(role);
        review.confirm("PAY-2026-000185", "AG-2026-031", "Подтверждены стороны и основание платежа", true, state);
        assertEquals(DemoUser.forRole(role).fullName(), state.match("PAY-2026-000185").actor());
        matching.loadTestBatch(state);
        var preview = matching.preview(state, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), false);
        var safe = preview.rows().stream().filter(p -> p.status() == DemoMatchingService.Status.SAFE)
                .map(DemoMatchingService.Proposal::operationId).findFirst().orElseThrow();
        var result = matching.apply(state, preview, Set.of(safe), "Подставной исполнитель");
        assertEquals(1, result.applied());
        assertEquals(DemoUser.forRole(role).fullName(), state.match(safe).actor());
        assertEquals(DemoUser.forRole(role).fullName(), state.batchRuns().getLast().initiator());
        assertEquals(DemoUser.forRole(role).fullName(), state.auditEvents().getLast().actor());
        var manualPreview = matching.preview(state, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), false);
        var manual = matching.sendToManual(state, manualPreview, Set.of("PAY-2026-000198"), "Подставной исполнитель");
        assertEquals(1, manual.sentManual());
        assertTrue(state.isManualQueued("PAY-2026-000198"));
        assertEquals(DemoUser.forRole(role).fullName(), state.batchRuns().getLast().initiator());
    }

    @ParameterizedTest
    @EnumSource(DemoRole.class)
    void complexManualDecisionDependsOnRole(DemoRole role) {
        as(role);
        int audits = state.auditEvents().size();
        int pending = review.pendingCount(state);
        if (role == DemoRole.OPERATIONS) {
            var error = assertThrows(IllegalStateException.class, () -> review.confirm("PAY-2026-000187",
                    "AG-2026-044", "Проверены основания и стороны", true, state));
            assertEquals("Недостаточно полномочий", error.getMessage());
            assertFalse(state.isLinked("PAY-2026-000187"));
            assertEquals(audits, state.auditEvents().size());
            assertEquals(pending, review.pendingCount(state));
        } else {
            review.confirm("PAY-2026-000187", "AG-2026-044", "Проверены основания и стороны", true, state);
            assertEquals(DemoUser.ROZHKOV.fullName(), state.match("PAY-2026-000187").actor());
        }
    }

    @Test
    void directAutomaticLinkCannotDisguiseAmbiguousManualDecision() {
        as(DemoRole.OPERATIONS);
        String id = "PAY-2026-000187";
        assertEquals(DemoPermissions.Action.MANUAL_COMPLEX,
                DemoPermissions.manualAction(state.operation(id), data.contract("AG-2026-044"), state));
        int queued = review.pendingCount(state);
        long revision = state.revision();
        assertThrows(IllegalStateException.class, () -> state.link(id, "AG-2026-044",
                "Автоматически", data.contracts(), ""));
        assertFalse(state.isLinked(id));
        assertTrue(state.isManualQueued(id));
        assertEquals(queued, review.pendingCount(state));
        assertEquals(revision, state.revision());
        assertTrue(state.auditEvents().isEmpty());
        assertTrue(state.batchRuns().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(DemoRole.class)
    void confirmedLinkChangesAndUnlinkDependOnRole(DemoRole role) {
        as(role);
        String id = "PAY-2026-000184";
        int audits = state.auditEvents().size();
        if (role == DemoRole.OPERATIONS) {
            assertThrows(IllegalStateException.class, () -> state.link(id, "AG-2026-021", "Вручную",
                    data.contracts(), "Проверено решение"));
            assertThrows(IllegalStateException.class, () -> state.unlink(id));
            assertEquals("AG-2026-014", state.match(id).contractId());
            assertEquals(audits, state.auditEvents().size());
        } else {
            state.link(id, "AG-2026-021", "Вручную", data.contracts(), "Проверено решение");
            assertEquals(DemoUser.ROZHKOV.fullName(), state.match(id).actor());
            state.unlink(id);
            assertFalse(state.isLinked(id));
            assertEquals(DemoUser.ROZHKOV.fullName(), state.auditEvents().getLast().actor());
        }
    }

    @Test
    void switchingUserBeforeConfirmationDeniesWithoutSideEffects() {
        as(DemoRole.DECISION_MAKER);
        var preview = matching.preview(state, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), false);
        int audits = state.auditEvents().size();
        int runs = state.batchRuns().size();
        DemoSession.login(DemoRole.OPERATIONS);
        assertThrows(IllegalStateException.class, () -> review.confirm("PAY-2026-000187",
                "AG-2026-044", "Проверены основания и стороны", true, state));
        assertThrows(IllegalStateException.class, () -> state.unlink("PAY-2026-000184"));
        assertEquals(audits, state.auditEvents().size());
        assertEquals(runs, state.batchRuns().size());
        assertEquals("AG-2026-014", state.match("PAY-2026-000184").contractId());
        assertFalse(state.isLinked("PAY-2026-000187"));
        assertNotNull(preview);
    }

    @Test
    void guardedConfirmationsRecheckPermissionsBeforeMutation() {
        as(DemoRole.DECISION_MAKER);
        String id = "PAY-2026-000184";
        var expected = state.match(id);
        DemoSession.login(DemoRole.OPERATIONS);
        assertThrows(IllegalStateException.class, () -> state.unlinkIfUnchanged(id, expected));
        assertThrows(IllegalStateException.class, () -> state.changeLinkIfUnchanged(
                id, expected, "AG-2026-021", data.contracts(), "Проверены документы"));
        assertEquals(expected, state.match(id));
        assertTrue(state.auditEvents().isEmpty());
        DemoSession.login(DemoRole.DECISION_MAKER);
        state.changeLinkIfUnchanged(id, expected, "AG-2026-021", data.contracts(), "Проверены документы");
        assertEquals(DemoUser.ROZHKOV.fullName(), state.match(id).actor());
    }

    @Test
    void switchingUsersDoesNotRewriteHistoricalAuthors() {
        as(DemoRole.DECISION_MAKER);
        review.confirm("PAY-2026-000185", "AG-2026-031", "Подтверждены сведения о плательщике", true, state);
        String originalAuthor = state.match("PAY-2026-000185").actor();
        DemoSession.login(DemoRole.OPERATIONS);
        assertEquals(DemoUser.ROZHKOV.fullName(), originalAuthor);
        assertEquals(originalAuthor, state.match("PAY-2026-000185").actor());
        assertEquals(originalAuthor, state.auditEvents().getLast().actor());
    }

    @ParameterizedTest
    @EnumSource(DemoRole.class)
    void auditRoutePolicyDependsOnRole(DemoRole role) {
        as(role);
        assertEquals(role == DemoRole.DECISION_MAKER,
                DemoPermissions.allowsCurrent(DemoPermissions.Action.VIEW_AUDIT));
    }

    @ParameterizedTest
    @EnumSource(DemoRole.class)
    void manualChoiceExplainsComplexCaseWithoutChangingPermissions(DemoRole role) {
        as(role);
        var ambiguous = state.operation("PAY-2026-000187");
        var conflict = state.operation("PAY-2026-000190");
        var clear = state.operation("PAY-2026-000185");
        assertEquals(role == DemoRole.DECISION_MAKER, DemoPermissions.allowsCurrent(
                DemoPermissions.manualAction(ambiguous, data.contract("AG-2026-044"), state)));
        assertTrue(DemoPermissions.manualDenialReason(ambiguous, data.contract("AG-2026-044"), state)
                .contains("Найдено несколько подходящих договоров"));
        assertTrue(DemoPermissions.manualDenialReason(conflict, data.contract("AG-2026-014"), state)
                .contains("ИНН"));
        assertTrue(DemoPermissions.allowsCurrent(
                DemoPermissions.manualAction(clear, data.contract("AG-2026-031"), state)));
    }
}
