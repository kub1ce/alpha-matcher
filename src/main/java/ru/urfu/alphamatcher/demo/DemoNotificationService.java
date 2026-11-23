package ru.urfu.alphamatcher.demo;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.vaadin.flow.server.VaadinSession;

/** Mutable notification state belonging to one demo Vaadin session. */
public final class DemoNotificationService {
    private static final String SESSION_KEY = "demo-notifications";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");

    public enum Kind { SUCCESS, INFO, WARNING, ERROR }

    public record Notice(String id, Kind kind, String title, String details, String timestamp, boolean read) {
        Notice asRead() {
            return new Notice(id, kind, title, details, timestamp, true);
        }
    }

    private final List<Notice> notices = new ArrayList<>();
    private long nextEventId;

    public static DemoNotificationService current() {
        VaadinSession session = VaadinSession.getCurrent();
        DemoNotificationService state = (DemoNotificationService) session.getAttribute(SESSION_KEY);
        if (state == null) {
            state = new DemoNotificationService();
            session.setAttribute(SESSION_KEY, state);
        }
        state.removeLegacyActionMessages();
        return state;
    }

    public static void clearSession() {
        VaadinSession.getCurrent().setAttribute(SESSION_KEY, null);
    }

    public List<Notice> items() {
        return List.copyOf(notices);
    }

    /** Register an attention-worthy event in the session notification center. */
    public Notice registerEvent(Kind kind, String title, String details) {
        for (Notice existing : notices) {
            if (!existing.read() && existing.kind() == kind && existing.title().equals(title)
                    && existing.details().equals(details)) return existing;
        }
        Notice notice = new Notice("event-" + ++nextEventId, kind, title, details,
                LocalDateTime.now().format(TIME_FORMAT), false);
        notices.addFirst(notice);
        return notice;
    }

    /** Remove confirmations stored by older demo UI versions from an existing session. */
    void removeLegacyActionMessages() {
        notices.removeIf(notice -> notice.id().equals("run-018") || switch (notice.title()) {
            case "Роль обновлена", "Договор привязан", "Договор отвязан", "Действие недоступно" -> true;
            default -> false;
        });
    }

    public List<Notice> unreadItems() {
        return notices.stream().filter(notice -> !notice.read()).toList();
    }

    public long unreadCount() {
        return notices.stream().filter(notice -> !notice.read()).count();
    }

    public void markRead(String id) {
        for (int index = 0; index < notices.size(); index++) {
            Notice notice = notices.get(index);
            if (notice.id().equals(id)) {
                notices.set(index, notice.asRead());
                return;
            }
        }
    }

    public void markAllRead() {
        notices.replaceAll(Notice::asRead);
    }

}
