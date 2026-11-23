package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemoNotificationServiceTest {
    @Test
    void repeatedUnreadEventKeepsOneBellEntry() {
        DemoNotificationService state = new DemoNotificationService();

        var first = state.registerEvent(DemoNotificationService.Kind.INFO, "Пакет загружен", "RUN-019");
        state.registerEvent(DemoNotificationService.Kind.WARNING, "Требуется разбор", "PAY-019");
        var repeated = state.registerEvent(DemoNotificationService.Kind.INFO, "Пакет загружен", "RUN-019");

        assertEquals(first.id(), repeated.id());
        assertEquals(2, state.unreadCount());
        state.markRead(first.id());
        assertFalse(state.unreadItems().stream().anyMatch(item -> item.id().equals(first.id())));
    }

    @Test
    void eventSeverityIsPreservedAndLegacyActionConfirmationsAreRemoved() {
        DemoNotificationService state = new DemoNotificationService();
        for (DemoNotificationService.Kind kind : DemoNotificationService.Kind.values()) {
            state.registerEvent(kind, "Фоновое событие " + kind, "RUN-019");
        }
        state.registerEvent(DemoNotificationService.Kind.SUCCESS, "Роль обновлена", "Сотрудник по операциям");

        state.removeLegacyActionMessages();

        assertEquals(4, state.items().size());
        assertEquals(4, state.unreadCount());
        assertFalse(state.items().stream().anyMatch(item -> item.title().equals("Роль обновлена")));
        for (DemoNotificationService.Kind kind : DemoNotificationService.Kind.values()) {
            assertTrue(state.items().stream().anyMatch(item -> item.kind() == kind));
        }
    }

    @Test
    void readingHidesNotificationsWithoutDeletingHistory() {
        DemoNotificationService state = new DemoNotificationService();

        assertEquals(0, state.unreadCount());
        assertTrue(state.items().isEmpty());
        var first = state.registerEvent(DemoNotificationService.Kind.WARNING, "Событие", "Проверить операцию");
        assertEquals(1, state.unreadCount());
        state.markRead(first.id());
        assertEquals(0, state.unreadCount());
        assertTrue(state.items().getFirst().read());

        state.registerEvent(DemoNotificationService.Kind.INFO, "Пакет завершён", "Проверен результат");
        state.markAllRead();
        assertEquals(0, state.unreadCount());
        assertTrue(state.unreadItems().isEmpty());
        assertEquals(2, state.items().size());
        assertTrue(state.items().stream().allMatch(DemoNotificationService.Notice::read));
    }
}
