package ru.urfu.alphamatcher.ui;

import static org.junit.jupiter.api.Assertions.*;
import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.browserless.internal.Routes;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.demo.*;

class DemoStateUpdatesComponentTest extends BrowserlessTest {
    private final DemoDataService data = new ru.urfu.alphamatcher.demo.EngineStubDataService();
    private final DemoMatchingService matching = new DemoMatchingService(data);
    private UI tabA;
    private UI tabB;
    private DemoOperationState state;

    @Override protected Routes discoverRoutes() { return new Routes(); }

    @BeforeEach void prepare() {
        discoverRoutes(Set.of("ru.urfu.alphamatcher.ui")).register(VaadinService.getCurrent().getContext());
        tabA = UI.getCurrent();
        tabB = new UI();
        tabB.getInternals().setSession(VaadinSession.getCurrent());
        DemoSession.login(DemoRole.DECISION_MAKER);
        state = data.state();
    }

    private void deliver(UI ui) {
        UI.setCurrent(ui);
        VaadinSession session = VaadinSession.getCurrent();
        session.getService().runPendingAccessTasks(session);
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
    }

    @Test void openStatisticsQueueRegistryAndBadgeFollowOtherTabsMutation() {
        var reporting = new DemoReportingService();
        UI.setCurrent(tabB);
        var layout = new MainLayout(data, new DemoManualReviewService(data));
        var stats = new StatisticsView(data, reporting);
        var queue = new ManualReviewView(data, new DemoManualReviewService(data));
        var operations = new OperationsView(data);
        tabB.add(layout, queue, operations);
        layout.setContent(stats);
        deliver(tabB);
        String before = text(stats);
        int pending = reporting.statistics(state).pending();
        UI.setCurrent(tabA);
        state.unlinkIfUnchanged("PAY-2026-000184", state.match("PAY-2026-000184"));
        deliver(tabB);
        assertNotEquals(before, text(stats));
        assertTrue(text(stats).contains("Всего в очереди: " + (pending + 1)));
        assertTrue(text(queue).contains("Ожидает проверки: " + (pending + 1)));
        assertTrue(all(layout).filter(c -> c.hasClassName("nav-badge"))
                .anyMatch(c -> c.getElement().getText().equals(String.valueOf(pending + 1))));
        assertTrue(text(operations).contains("PAY-2026-000184"));
        var row = all(operations).filter(c -> c.hasClassName("registry-row"))
                .filter(c -> text(c).contains("PAY-2026-000184")).findFirst().orElseThrow();
        assertTrue(text(row).contains("Не обработана"));
        assertEquals(1, state.auditEvents().size());
    }

    @Test void newlyRegisteredNoticeShowsIndicatorAndCanBeMarkedRead() {
        var layout = new MainLayout(data, new DemoManualReviewService(data));
        tabA.add(layout);
        var indicator = all(layout).filter(c -> c.hasClassName("bell-dot")).findFirst().orElseThrow();
        assertFalse(indicator.isVisible());
        assertEquals(0, DemoNotificationService.current().unreadCount());
        var bell = all(layout).filter(c -> c.hasClassName("bell-button")).map(Button.class::cast)
                .findFirst().orElseThrow();
        bell.click();
        assertTrue(text(layout).contains("Уведомлений нет"));

        AppNotifications.registerEvent(DemoNotificationService.Kind.WARNING, "Проверить новую операцию", "QA");
        assertTrue(indicator.isVisible());
        assertEquals(1, DemoNotificationService.current().unreadCount());
        assertTrue(text(layout).contains("Проверить новую операцию"));
        all(layout).filter(c -> c.hasClassName("notice-action")).map(Button.class::cast)
                .findFirst().orElseThrow().click();
        assertFalse(indicator.isVisible());
        assertEquals(0, DemoNotificationService.current().unreadCount());
        assertTrue(DemoNotificationService.current().items().getFirst().read());
    }

    @Test void batchDeliveryIsCoalescedUsesTargetUiAndSeesFinalRun() {
        UI.setCurrent(tabB);
        Div target = new Div();
        AtomicInteger refreshes = new AtomicInteger();
        DemoStateUpdates.bind(target, state, () -> {
            assertSame(tabB, UI.getCurrent());
            assertTrue(VaadinSession.getCurrent().hasLock());
            if (state.operation("PAY-2026-000196") != null) {
                assertEquals(2, state.batchRuns().getLast().applied());
                assertEquals(2, state.auditEvents().size());
            }
            refreshes.incrementAndGet();
        });
        tabB.add(target);
        deliver(tabB);
        refreshes.set(0);
        UI.setCurrent(tabA);
        matching.loadTestBatch(state);
        var preview = matching.preview(state, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), false);
        matching.apply(state, preview, Set.of("PAY-2026-000196", "PAY-2026-000197"), "Тест");
        target.addAttachListener(event -> fail("Обновление не должно переподключать компонент"));
        deliver(tabB);
        assertEquals(1, refreshes.get());
        assertEquals(2, state.batchRuns().getLast().applied());
        deliver(tabB);
        assertEquals(1, refreshes.get());
    }

    @Test void detachCancelsQueuedDeliveryAndReattachRegistersOnlyOnce() {
        UI.setCurrent(tabB);
        Div target = new Div();
        AtomicInteger refreshes = new AtomicInteger();
        DemoStateUpdates.bind(target, state, refreshes::incrementAndGet);
        tabB.add(target);
        deliver(tabB);
        refreshes.set(0);
        UI.setCurrent(tabA);
        state.unlink("PAY-2026-000184");
        UI.setCurrent(tabB);
        tabB.remove(target);
        deliver(tabB);
        assertEquals(0, refreshes.get());
        state.unlink("PAY-2026-000188");
        deliver(tabB);
        assertEquals(0, refreshes.get());
        tabB.add(target);
        deliver(tabB);
        assertEquals(1, refreshes.get());
        state.unlink("PAY-2026-000189");
        deliver(tabB);
        assertEquals(2, refreshes.get());
    }

    @Test void notifyingWhileSessionIsLockedDoesNotBlockStateMutation() throws Exception {
        UI.setCurrent(tabB);
        Div target = new Div();
        AtomicInteger refreshes = new AtomicInteger();
        DemoStateUpdates.bind(target, state, refreshes::incrementAndGet);
        tabB.add(target);
        deliver(tabB);
        refreshes.set(0);
        assertTrue(VaadinSession.getCurrent().hasLock());
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            // The worker holds the state monitor while this thread owns the session lock.
            // Delivery must queue without waiting for that lock.
            worker.submit(() -> state.unlink("PAY-2026-000184"))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(0, refreshes.get());
        deliver(tabB);
        assertEquals(1, refreshes.get());
    }

    private static Stream<Component> all(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(DemoStateUpdatesComponentTest::all));
    }
    private static String text(Component root) {
        return all(root).map(c -> c.getElement().getText()).reduce("", String::concat);
    }
}
