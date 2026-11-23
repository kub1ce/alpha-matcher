package ru.urfu.alphamatcher.demo;

import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import java.util.ArrayList;
import java.util.List;

public final class DemoSession {
    private static final String USER = "demo-user";
    private static final String USER_GENERATION = "demo-user-generation";
    private static final String MATCHING_DRAFT = "demo-matching-draft";
    private static final String USER_LISTENERS = "demo-user-listeners";
    private DemoSession() { }

    public static DemoRole role() {
        DemoUser user = user();
        return user == null ? null : user.role();
    }
    public static DemoUser user() { return (DemoUser) VaadinSession.getCurrent().getAttribute(USER); }

    public static long employeeGeneration() {
        Long current = (Long) VaadinSession.getCurrent().getAttribute(USER_GENERATION);
        return current == null ? 0 : current;
    }

    public static void requireSameEmployeeGeneration(long expected) {
        if (user() == null || employeeGeneration() != expected)
            throw new IllegalStateException("Пользователь сменился. Оформите подтверждение заново.");
    }
    public static DemoMatchingDraft matchingDraft() {
        UI ui = java.util.Objects.requireNonNull(UI.getCurrent(), "Нет текущей вкладки");
        DemoMatchingDraft draft = ComponentUtil.getData(ui, DemoMatchingDraft.class);
        if (draft == null) {
            draft = new DemoMatchingDraft();
            ComponentUtil.setData(ui, DemoMatchingDraft.class, draft);
        }
        draft.bindSelectionToEmployee(employeeGeneration());
        return draft;
    }

    public static void login(DemoRole role) {
        VaadinSession session = VaadinSession.getCurrent();
        DemoUser next = DemoUser.forRole(role);
        if (next.equals(session.getAttribute(USER))) return;
        session.setAttribute(USER_GENERATION, employeeGeneration() + 1);
        session.setAttribute(USER, next);
        notifyUserChanged(session);
    }

    public static void logout() {
        VaadinSession session = VaadinSession.getCurrent();
        session.setAttribute(USER_GENERATION, employeeGeneration() + 1);
        session.setAttribute(USER, null);
        notifyUserChanged(session);
        session.setAttribute(MATCHING_DRAFT, null);
        for (UI ui : session.getUIs()) ComponentUtil.setData(ui, DemoMatchingDraft.class, null);
        DemoNotificationService.clearSession();
        DemoDataService.clearSession();
    }

    /** Listeners belong to this VaadinSession and are removed when their UI detaches. */
    public static Runnable onUserChanged(Runnable listener) {
        VaadinSession session = VaadinSession.getCurrent();
        @SuppressWarnings("unchecked")
        List<Runnable> listeners = (List<Runnable>) session.getAttribute(USER_LISTENERS);
        if (listeners == null) {
            listeners = new ArrayList<>();
            session.setAttribute(USER_LISTENERS, listeners);
        }
        listeners.add(listener);
        List<Runnable> registered = listeners;
        return () -> registered.remove(listener);
    }

    private static void notifyUserChanged(VaadinSession session) {
        @SuppressWarnings("unchecked")
        List<Runnable> listeners = (List<Runnable>) session.getAttribute(USER_LISTENERS);
        if (listeners != null) for (Runnable listener : List.copyOf(listeners)) listener.run();
    }
}
