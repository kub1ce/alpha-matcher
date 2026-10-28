package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.UI;
import ru.urfu.alphamatcher.demo.DemoNotificationService;
import ru.urfu.alphamatcher.demo.DemoNotificationService.Kind;

/** One entry point for action feedback and attention-worthy demo events. */
public final class AppNotifications {
    private AppNotifications() { }

    public static void success(String title, String details) { showToast(Kind.SUCCESS, title, details); }
    public static void info(String title, String details) { showToast(Kind.INFO, title, details); }
    public static void warning(String title, String details) { showToast(Kind.WARNING, title, details); }
    public static void error(String title, String details) { showToast(Kind.ERROR, title, details); }

    public static void showToast(Kind kind, String title, String details) {
        UI.getCurrent().getChildren().filter(MainLayout.class::isInstance)
                .map(MainLayout.class::cast).forEach(layout -> layout.showToast(kind, title, details));
    }

    public static void registerEvent(Kind kind, String title, String details) {
        DemoNotificationService.current().registerEvent(kind, title, details);
        UI.getCurrent().getChildren().filter(MainLayout.class::isInstance)
                .map(MainLayout.class::cast).forEach(MainLayout::refreshNotificationCenter);
    }

    public static void registerEventWithToast(Kind kind, String title, String details) {
        registerEvent(kind, title, details);
        showToast(kind, title, details);
    }
}
