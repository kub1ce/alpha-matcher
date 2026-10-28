package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Isolated audit probes for state mutations reachable from server code. */
class SecurityAuditInvariantTest {
    private final DemoDataService data = new DemoDataService();
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

    @Test
    void directManualLinkRequiresJustificationAndLeavesAllStateUnchangedOnDenial() {
        as(DemoRole.OPERATIONS);
        String id = "PAY-2026-000185";
        int queue = new DemoManualReviewService(data).pendingCount(state);
        long revision = state.revision();
        int audit = state.auditEvents().size();
        int runs = state.batchRuns().size();

        assertThrows(IllegalArgumentException.class,
                () -> state.link(id, "AG-2026-031", "Вручную", data.contracts(), ""),
                () -> "Observed match=" + state.match(id) + ", queued=" + state.isManualQueued(id)
                        + ", revision=" + state.revision() + ", audits=" + state.auditEvents().size());
        assertFalse(state.isLinked(id));
        assertTrue(state.isManualQueued(id));
        assertEquals(queue, new DemoManualReviewService(data).pendingCount(state));
        assertEquals(revision, state.revision());
        assertEquals(audit, state.auditEvents().size());
        assertEquals(runs, state.batchRuns().size());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n\r", "\u00a0\u2003", "\u200b\ufeff", "ABCD"})
    void everyInvalidManualJustificationLeavesStateUnchanged(String justification) {
        as(DemoRole.OPERATIONS);
        String id = "PAY-2026-000185";
        long revision = state.revision();
        int linked = state.linkedCount();
        int pending = new DemoManualReviewService(data).pendingCount(state);
        int audit = state.auditEvents().size();
        int runs = state.batchRuns().size();

        assertThrows(IllegalArgumentException.class,
                () -> state.link(id, "AG-2026-031", "Вручную", data.contracts(), justification));
        assertNull(state.match(id));
        assertTrue(state.isManualQueued(id));
        assertEquals(linked, state.linkedCount());
        assertEquals(pending, new DemoManualReviewService(data).pendingCount(state));
        assertEquals(revision, state.revision());
        assertEquals(audit, state.auditEvents().size());
        assertEquals(runs, state.batchRuns().size());
    }

    @Test
    void validManualDecisionAfterInvalidAttemptKeepsNormalizedReasonAndAuthor() {
        as(DemoRole.OPERATIONS);
        String id = "PAY-2026-000185";
        assertThrows(IllegalArgumentException.class,
                () -> state.link(id, "AG-2026-031", "Вручную", data.contracts(), "\t\n"));
        state.link(id, "AG-2026-031", "Вручную", data.contracts(),
                "\u00a0Проверены стороны <&>\u2003");
        assertEquals("Проверены стороны <&>", state.match(id).justification());
        assertEquals(DemoSession.user().fullName(), state.match(id).actor());
        assertFalse(state.isManualQueued(id));
        assertEquals(1, state.auditEvents().size());
        assertEquals("Проверены стороны <&>", state.auditEvents().getFirst().justification());
        assertEquals(1, state.revision());
    }

    @Test
    void overloadWithoutJustificationCannotBypassManualRule() {
        as(DemoRole.OPERATIONS);
        long revision = state.revision();
        assertThrows(IllegalArgumentException.class,
                () -> state.link("PAY-2026-000185", "AG-2026-031", "Вручную", data.contracts()));
        assertNull(state.match("PAY-2026-000185"));
        assertTrue(state.isManualQueued("PAY-2026-000185"));
        assertEquals(revision, state.revision());
        assertTrue(state.auditEvents().isEmpty());
    }

    @Test
    void rejectedAutomaticCandidateAndUnknownOperationLeaveStateUnchanged() {
        as(DemoRole.OPERATIONS);
        long revision = state.revision();
        int queue = new DemoManualReviewService(data).pendingCount(state);
        assertThrows(IllegalStateException.class,
                () -> state.link("PAY-2026-000187", "AG-2026-044", "Автоматически", data.contracts()));
        assertThrows(IllegalArgumentException.class,
                () -> state.queueForManual("PAY-2026-DOES-NOT-EXIST"));
        assertFalse(state.isLinked("PAY-2026-000187"));
        assertEquals(queue, new DemoManualReviewService(data).pendingCount(state));
        assertEquals(revision, state.revision());
        assertTrue(state.auditEvents().isEmpty());
        assertTrue(state.batchRuns().isEmpty());
    }

    @Test
    void unknownContractInChangeAndManualDecisionLeavesAllStateUnchanged() {
        as(DemoRole.DECISION_MAKER);
        long revision = state.revision();
        int queue = new DemoManualReviewService(data).pendingCount(state);
        int runs = state.batchRuns().size();
        int audit = state.auditEvents().size();

        assertThrows(IllegalArgumentException.class, () -> state.link("PAY-2026-000184",
                "AG-DOES-NOT-EXIST", "Вручную", data.contracts(), "Проверены стороны и договор"));
        assertThrows(IllegalArgumentException.class, () -> new DemoManualReviewService(data).confirm(
                "PAY-2026-000187", "AG-DOES-NOT-EXIST", "Проверены стороны и договор", true, state));

        assertEquals("AG-2026-014", state.match("PAY-2026-000184").contractId());
        assertNull(state.match("PAY-2026-000187"));
        assertTrue(state.isManualQueued("PAY-2026-000187"));
        assertEquals(revision, state.revision());
        assertEquals(queue, new DemoManualReviewService(data).pendingCount(state));
        assertEquals(runs, state.batchRuns().size());
        assertEquals(audit, state.auditEvents().size());
    }

    @Test
    void loggedOutSessionCannotReuseStateMutationMethods() {
        as(DemoRole.DECISION_MAKER);
        DemoSession.logout();
        long revision = state.revision();
        assertThrows(IllegalStateException.class, () -> state.unlink("PAY-2026-000184"));
        assertThrows(IllegalStateException.class,
                () -> state.link("PAY-2026-000185", "AG-2026-031", "Вручную",
                        data.contracts(), "Подтверждены стороны"));
        assertEquals("AG-2026-014", state.match("PAY-2026-000184").contractId());
        assertFalse(state.isLinked("PAY-2026-000185"));
        assertEquals(revision, state.revision());
        assertTrue(state.auditEvents().isEmpty());
    }
}
