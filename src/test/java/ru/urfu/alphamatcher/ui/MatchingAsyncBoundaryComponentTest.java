package ru.urfu.alphamatcher.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.browserless.internal.Routes;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.urfu.alphamatcher.demo.*;
import ru.urfu.alphamatcher.experiment.MatchingModel;

class MatchingAsyncBoundaryComponentTest extends BrowserlessTest {
    @Override protected Routes discoverRoutes() { return new Routes(); }

    @Test void analysisRunsOutsideUiAndCancellationCannotPublishItsResult() throws Exception {
        DemoSession.login(DemoRole.OPERATIONS);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Thread testThread=Thread.currentThread();
        var source=new EngineStubDataService() {
            @Override public MatchingModel.Decision analyze(Operation operation) {
                assertNotSame(testThread,Thread.currentThread());
                entered.countDown();
                try { if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Test source timeout"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Cancelled test source",e);}
                return super.analyze(operation);
            }
        };
        var view=new MatchingView(source,new DemoMatchingService(source));
        UI.getCurrent().add(view);
        button(view,"Найти привязки").click();
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        button(view,"Отменить поиск").click();release.countDown();
        VaadinSession session=VaadinSession.getCurrent();
        session.getService().runPendingAccessTasks(session);roundTrip();
        assertFalse(view.analysisRunning());
        assertNull(DemoSession.matchingDraft().preview());
        assertTrue(source.state().auditEvents().isEmpty());
    }

    @Test void detachedOperationAnalysisCannotStopPollingForTheNextView() throws Exception {
        discoverRoutes(Set.of("ru.urfu.alphamatcher.ui")).register(VaadinService.getCurrent().getContext());
        DemoSession.login(DemoRole.DECISION_MAKER);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var source = new EngineStubDataService() {
            @Override public MatchingModel.Decision analyze(Operation operation) {
                worker.set(Thread.currentThread());
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Source timeout");
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Source cancelled", expected);
                }
                return super.analyze(operation);
            }
        };
        var ui = UI.getCurrent();
        var view = new OperationDetailView(source, new MockEnvironment().withProperty("spring.profiles.active", "demo"));
        ui.add(view);
        String reference = "PAY-2026-000184";
        var event = mock(BeforeEnterEvent.class);
        when(event.getRouteParameters()).thenReturn(new RouteParameters("id", reference));
        when(event.getLocation()).thenReturn(new Location("operation/" + reference));
        view.beforeEnter(event);
        var analyze = button(view, "Выполнить полный анализ");
        analyze.click();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1000, ui.getPollInterval());
            ui.remove(view);
            assertEquals(-1, ui.getPollInterval(), "Detached view kept UI polling active");
            ui.setPollInterval(2000);
        } finally {
            release.countDown();
            if (worker.get() != null) worker.get().join(3000);
        }
        var session = VaadinSession.getCurrent();
        session.getService().runPendingAccessTasks(session);
        roundTrip();
        assertEquals(2000, ui.getPollInterval(), "Old result stopped the new view's polling");
        assertNull(source.state().analysis(reference));
    }
    private static Button button(Component root,String caption) {
        return all(root).filter(Button.class::isInstance).map(Button.class::cast).filter(b->caption.equals(b.getText())).findFirst().orElseThrow();
    }
    private static Stream<Component> all(Component root) { return Stream.concat(Stream.of(root),root.getChildren().flatMap(MatchingAsyncBoundaryComponentTest::all)); }
}
