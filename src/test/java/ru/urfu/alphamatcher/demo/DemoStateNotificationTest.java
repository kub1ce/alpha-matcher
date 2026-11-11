package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoStateNotificationTest {
    @Test
    void notificationsFollowCompletedMutationsAndRemovalStopsDelivery() {
        var data = new DemoDataService();
        var state = new DemoOperationState(data.fixtures());
        List<Long> revisions = new ArrayList<>();
        var listener = state.onChange(() -> {
            revisions.add(state.revision());
            assertFalse(state.auditEvents().isEmpty());
        });
        String id = "PAY-2026-000184";
        var old = state.match(id);
        state.unlinkIfUnchanged(id, old);
        assertEquals(List.of(1L), revisions);
        assertThrows(IllegalStateException.class, () -> state.unlinkIfUnchanged(id, old));
        assertFalse(state.queueForManual(id));
        assertEquals(List.of(1L), revisions);
        state.link(id, "AG-2026-014", "Вручную", data.contracts(), "Проверены документы");
        assertEquals(List.of(1L, 2L), revisions);
        listener.remove();
        listener.remove();
        state.unlink(id);
        assertEquals(List.of(1L, 2L), revisions);
    }
}
