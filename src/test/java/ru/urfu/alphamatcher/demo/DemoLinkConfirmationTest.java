package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DemoLinkConfirmationTest {
    private static final String ID = "PAY-2026-000184";
    private final DemoDataService data = new DemoDataService();
    private final DemoOperationState state = new DemoOperationState(data.fixtures());

    @Test
    void staleUnlinkDoesNotRemoveNewLinkOrChangeHistory() {
        var expected = state.match(ID);
        state.changeLinkIfUnchanged(ID, expected, "AG-2026-021", data.contracts(), "Решение другой вкладки");
        var current = state.match(ID);
        long revision = state.revision();
        var audit = state.auditEvents();
        var error = assertThrows(IllegalStateException.class, () -> state.unlinkIfUnchanged(ID, expected));
        assertTrue(error.getMessage().contains("Подтверждение устарело"));
        assertEquals(current, state.match(ID));
        assertEquals(revision, state.revision());
        assertEquals(audit, state.auditEvents());
        assertFalse(state.isManualQueued(ID));
        assertNull(state.previousContract(ID));
    }

    @Test
    void staleChangeCannotOverwriteNewDecisionEvenWhenContractIdIsUnchanged() {
        var expected = state.match(ID);
        state.changeLinkIfUnchanged(ID, expected, expected.contractId(), data.contracts(), "Новое обоснование другой вкладки");
        var current = state.match(ID);
        long revision = state.revision();
        var audit = state.auditEvents();
        assertThrows(IllegalStateException.class, () -> state.changeLinkIfUnchanged(
                ID, expected, "AG-2026-021", data.contracts(), "Устаревшее решение"));
        assertEquals(current, state.match(ID));
        assertEquals(revision, state.revision());
        assertEquals(audit, state.auditEvents());
    }

    @Test
    void currentConfirmationAllowsChangeAndUnlinkButCannotBeReplayed() {
        state.changeLinkIfUnchanged(ID, state.match(ID), "AG-2026-021", data.contracts(), "Проверены документы");
        var expected = state.match(ID);
        assertEquals("AG-2026-021", expected.contractId());
        state.unlinkIfUnchanged(ID, expected);
        assertNull(state.match(ID));
        assertTrue(state.isManualQueued(ID));
        assertEquals("AG-2026-021", state.previousContract(ID));
        assertEquals(2, state.auditEvents().size());
        assertThrows(IllegalStateException.class, () -> state.unlinkIfUnchanged(ID, expected));
        assertThrows(IllegalStateException.class, () -> state.changeLinkIfUnchanged(
                ID, expected, "AG-2026-014", data.contracts(), "Повторное решение"));
        assertEquals(2, state.auditEvents().size());
    }

    @Test
    void sameNamesWithDifferentPartyIdsCannotReplaceAnExistingLink() {
        var operation = state.operation(ID);
        var wrong = new DemoDataService.Contract("QA-WRONG", "Другой договор", "2026-01-01",
                operation.payer().name(), operation.recipient().name(), "Поставка", null,
                "OTHER-CLIENT", operation.recipient().id());
        var expected = state.match(ID);
        assertThrows(IllegalArgumentException.class,
                () -> state.changeLinkIfUnchanged(ID, expected, wrong.id(), java.util.List.of(wrong), "Проверены документы"));
        assertEquals(expected, state.match(ID));
        assertTrue(state.auditEvents().isEmpty());
    }

    @Test
    void simultaneousConfirmationsOfOneDecisionAllowOnlyOneMutation() throws Exception {
        var expected = state.match(ID);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var change = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    state.changeLinkIfUnchanged(ID, expected, "AG-2026-021", data.contracts(), "Проверены документы");
                    return true;
                } catch (IllegalStateException stale) { return false; }
            });
            var unlink = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    state.unlinkIfUnchanged(ID, expected);
                    return true;
                } catch (IllegalStateException stale) { return false; }
            });
            start.countDown();
            assertNotEquals(change.get(5, TimeUnit.SECONDS), unlink.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, state.auditEvents().size());
        assertEquals(1, state.revision());
    }
}
