package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Test-only barrier between the service permission check and the state mutation. */
class InFlightDecisionBarrierTest {
    private final DemoDataService data = new DemoDataService();

    private VaadinSession session(DemoRole role) {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> attributes = java.util.Collections.synchronizedMap(new HashMap<>());
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        VaadinSession.setCurrent(session);
        DemoSession.login(role);
        return session;
    }

    @AfterEach
    void clearSession() { VaadinSession.setCurrent(null); }

    @Test
    void roleRevokedAfterServiceCheckBeforeStateLinkLeavesEveryRecordUntouched() throws Exception {
        VaadinSession session = session(DemoRole.DECISION_MAKER);
        CountDownLatch insideLink = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        DemoOperationState state = new DemoOperationState(data.originalFixtures()) {
            @Override
            public void link(String operationId, String contractId, String method,
                             java.util.List<DemoDataService.Contract> contracts, String justification) {
                insideLink.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                super.link(operationId, contractId, method, contracts, justification);
            }
        };
        long revision = state.revision();
        int pending = new DemoManualReviewService(data).pendingCount(state);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var action = pool.submit(() -> {
                VaadinSession.setCurrent(session);
                try {
                    new DemoManualReviewService(data).confirm("PAY-2026-000187", "AG-2026-044",
                            "Проверены договор и стороны", true, state);
                    return null;
                } catch (RuntimeException error) {
                    return error;
                } finally {
                    VaadinSession.setCurrent(null);
                }
            });
            assertTrue(insideLink.await(5, TimeUnit.SECONDS), "Service must reach state mutation");
            DemoSession.login(DemoRole.OPERATIONS);
            resume.countDown();
            Exception refusal = action.get(5, TimeUnit.SECONDS);
            assertInstanceOf(IllegalStateException.class, refusal);
            assertEquals("Недостаточно полномочий", refusal.getMessage());
        } finally {
            resume.countDown();
        }
        assertNull(state.match("PAY-2026-000187"));
        assertTrue(state.isManualQueued("PAY-2026-000187"));
        assertEquals(pending, new DemoManualReviewService(data).pendingCount(state));
        assertEquals(revision, state.revision());
        assertTrue(state.batchRuns().isEmpty());
        assertTrue(state.auditEvents().isEmpty());
    }

    @Test
    void logoutAfterServiceCheckBeforeMutationAlsoRefusesWithoutPartialState() throws Exception {
        VaadinSession session = session(DemoRole.DECISION_MAKER);
        CountDownLatch atMutation = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        DemoOperationState state = new DemoOperationState(data.originalFixtures()) {
            @Override
            public void link(String operationId, String contractId, String method,
                             java.util.List<DemoDataService.Contract> contracts, String justification) {
                atMutation.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                super.link(operationId, contractId, method, contracts, justification);
            }
        };
        long revision = state.revision();
        int queue = new DemoManualReviewService(data).pendingCount(state);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var action = pool.submit(() -> {
                VaadinSession.setCurrent(session);
                try {
                    new DemoManualReviewService(data).confirm("PAY-2026-000187", "AG-2026-044",
                            "Проверены договор и стороны", true, state);
                    return null;
                } catch (RuntimeException error) {
                    return error;
                } finally { VaadinSession.setCurrent(null); }
            });
            assertTrue(atMutation.await(5, TimeUnit.SECONDS));
            DemoSession.logout();
            resume.countDown();
            assertInstanceOf(IllegalStateException.class, action.get(5, TimeUnit.SECONDS));
        } finally { resume.countDown(); }
        assertNull(state.match("PAY-2026-000187"));
        assertEquals(queue, new DemoManualReviewService(data).pendingCount(state));
        assertEquals(revision, state.revision());
        assertTrue(state.batchRuns().isEmpty());
        assertTrue(state.auditEvents().isEmpty());
    }

    @Test
    void completedDecisionKeepsOriginalAuthorAndReasonAfterRoleChangeLogoutAndRepeat() {
        session(DemoRole.DECISION_MAKER);
        DemoOperationState state = new DemoOperationState(data.originalFixtures());
        String id = "PAY-2026-000187";
        long revision = state.revision();
        int queue = new DemoManualReviewService(data).pendingCount(state);
        String reason = "Проверены договор и стороны";
        new DemoManualReviewService(data).confirm(id, "AG-2026-044", reason, true, state);
        assertEquals("AG-2026-044", state.match(id).contractId());
        assertEquals(DemoUser.ROZHKOV.fullName(), state.match(id).actor());
        assertEquals(reason, state.match(id).justification());
        assertEquals(revision + 1, state.revision());
        assertEquals(queue - 1, new DemoManualReviewService(data).pendingCount(state));
        assertEquals(1, state.auditEventsForOperation(id).size());
        assertTrue(state.batchRuns().isEmpty());
        DemoSession.login(DemoRole.OPERATIONS);
        assertThrows(IllegalStateException.class, () -> new DemoManualReviewService(data)
                .confirm(id, "AG-2026-081", reason, true, state));
        DemoSession.logout();
        assertEquals("AG-2026-044", state.match(id).contractId());
        assertEquals(DemoUser.ROZHKOV.fullName(), state.auditEventsForOperation(id).getFirst().actor());
        assertEquals(revision + 1, state.revision());
        assertEquals(1, state.auditEventsForOperation(id).size());
    }

    @Test
    void actualVaadinSessionLockSerializesWorkForTwoThreads() throws Exception {
        VaadinSession session = new VaadinSession(mock(VaadinService.class)) {
            private final java.util.concurrent.locks.Lock testLock = new java.util.concurrent.locks.ReentrantLock();
            @Override public java.util.concurrent.locks.Lock getLockInstance() { return testLock; }
        };
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        session.lock();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var contender = pool.submit(() -> {
                requested.countDown();
                session.lock();
                try { acquired.countDown(); }
                finally { session.unlock(); }
            });
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            assertFalse(acquired.await(150, TimeUnit.MILLISECONDS));
            session.unlock();
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            contender.get(5, TimeUnit.SECONDS);
        }
    }
}
