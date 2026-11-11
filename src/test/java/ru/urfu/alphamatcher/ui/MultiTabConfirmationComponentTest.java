package ru.urfu.alphamatcher.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.browserless.internal.Routes;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.urfu.alphamatcher.demo.*;

/** Real view callbacks/dialogs in two UIs sharing one VaadinSession; no browser or database. */
class MultiTabConfirmationComponentTest extends BrowserlessTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoMatchingService matching = new DemoMatchingService(data);
    private DemoOperationState state;
    private UI tabA;
    private UI tabB;

    @Override
    protected Routes discoverRoutes() { return new Routes(); }

    @BeforeEach
    void prepareTabs() {
        discoverRoutes(Set.of("ru.urfu.alphamatcher.ui")).register(VaadinService.getCurrent().getContext());
        tabA = UI.getCurrent();
        tabB = new UI();
        tabB.getInternals().setSession(VaadinSession.getCurrent());
        DemoSession.login(DemoRole.DECISION_MAKER);
        state = data.state();
        matching.loadTestBatch(state);
    }

    private void use(UI ui) { UI.setCurrent(ui); }

    private OperationDetailView detail(UI ui) {
        use(ui);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("demo");
        var view = new OperationDetailView(data, environment);
        var layout = new MainLayout(data, new DemoManualReviewService(data));
        ui.add(layout);
        layout.setContent(view);
        view.beforeEnter(event("operation/PAY-2026-000184", new RouteParameters("id", "PAY-2026-000184")));
        return view;
    }

    private MatchingView matching(UI ui) {
        use(ui);
        var draft = DemoSession.matchingDraft();
        draft.parameters(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), false, true);
        draft.save(matching.preview(state, draft.from(), draft.to(), false, true), "Тест");
        var view = new MatchingView(data, matching);
        ui.add(view);
        view.beforeEnter(event("section/matching", new RouteParameters()));
        return view;
    }

    private static BeforeEnterEvent event(String path, RouteParameters parameters) {
        var event = mock(BeforeEnterEvent.class);
        when(event.getRouteParameters()).thenReturn(parameters);
        when(event.getLocation()).thenReturn(new Location(path));
        return event;
    }

    @Test
    void staleUnlinkDialogKeepsOtherTabsNewContractAndReportsStaleness() {
        var a = detail(tabA);
        button(a, "Отвязать договор").click();
        var confirmation = dialog(tabA);
        var b = detail(tabB);
        change(b, "AG-2026-021");
        var current = state.match("PAY-2026-000184");
        use(tabA);
        button(confirmation, "Отвязать").click();
        assertEquals(current, state.match("PAY-2026-000184"));
        assertEquals(1, state.auditEvents().size());
        assertTrue(find(Span.class, tabA).withTextContaining("Подтверждение устарело").exists());
    }

    @Test
    void staleChangeDialogCannotOverwriteOtherTabsNewDecision() {
        var a = detail(tabA);
        button(a, "Изменить договор").click();
        var confirmation = dialog(tabA);
        all(confirmation).filter(TextArea.class::isInstance).map(TextArea.class::cast)
                .findFirst().orElseThrow().setValue("Устаревшее решение вкладки A");
        var b = detail(tabB);
        change(b, "AG-2026-021");
        var current = state.match("PAY-2026-000184");
        use(tabA);
        button(confirmation, "Подтвердить выбор").click();
        assertEquals(current, state.match("PAY-2026-000184"));
        assertEquals(1, state.auditEvents().size());
        assertTrue(find(Span.class, tabA).withTextContaining("Подтверждение устарело").exists());
    }

    @Test
    void confirmationAppliesOnlyCurrentTabsSelectionCapturedWhenDialogOpened() {
        var a = matching(tabA);
        select(a, "PAY-2026-000196");
        var b = matching(tabB);
        select(b, "PAY-2026-000197");
        use(tabA);
        button(a, "Привязать выбранные").click();
        var confirmation = dialog(tabA);
        assertTrue(text(confirmation).contains("Всего выбрано: 1"));
        select(a, "PAY-2026-000197");
        button(confirmation, "Подтвердить").click();
        assertTrue(state.isLinked("PAY-2026-000196"));
        assertFalse(state.isLinked("PAY-2026-000197"));
        assertEquals(List.of("PAY-2026-000196"), state.batchRuns().getLast().operationIds());
    }

    @Test
    void recalculatingViewCannotMakeAnOpenStaleConfirmationCurrentAgain() {
        var a = matching(tabA);
        select(a, "PAY-2026-000196");
        button(a, "Привязать выбранные").click();
        var confirmation = dialog(tabA);
        var b = matching(tabB);
        select(b, "PAY-2026-000197");
        button(b, "Привязать выбранные").click();
        button(dialog(tabB), "Подтвердить").click();
        use(tabA);
        // A queued callback may refresh the view while its original dialog still exists.
        button(a, "Найти привязки").click();
        button(confirmation, "Подтвердить").click();
        assertFalse(state.isLinked("PAY-2026-000196"));
        assertTrue(state.isLinked("PAY-2026-000197"));
        assertEquals(1, state.batchRuns().size());
    }

    @Test
    void liveUpdateKeepsPreviewPeriodWhileDateFieldsAreBeingEdited() {
        var view = matching(tabA);
        var draft = DemoSession.matchingDraft();
        var original = draft.preview();
        var from = all(view).filter(com.vaadin.flow.component.datepicker.DatePicker.class::isInstance)
                .map(com.vaadin.flow.component.datepicker.DatePicker.class::cast).findFirst().orElseThrow();
        from.clear();
        use(tabB);
        state.unlink("PAY-2026-000184");
        use(tabA);
        VaadinSession session = VaadinSession.getCurrent();
        session.getService().runPendingAccessTasks(session);
        tabA.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        roundTrip();
        assertNull(from.getValue());
        assertEquals(original.from(), draft.preview().from());
        assertEquals(original.to(), draft.preview().to());
        assertEquals(matching.fingerprint(state, original.from(), original.to()), draft.preview().fingerprint());
    }

    @Test
    void navigationWithNewPeriodStillUsesQueryParametersWhenBusinessStateChanged() {
        var view = matching(tabA);
        var draft = DemoSession.matchingDraft();
        use(tabB);
        state.unlink("PAY-2026-000184");
        use(tabA);
        var event = event("section/matching", new RouteParameters());
        when(event.getLocation()).thenReturn(new Location("section/matching",
                com.vaadin.flow.router.QueryParameters.simple(java.util.Map.of("from", "2026-10-10", "to", "2026-10-12"))));
        view.beforeEnter(event);
        assertEquals(LocalDate.of(2026, 10, 10), draft.preview().from());
        assertEquals(LocalDate.of(2026, 10, 12), draft.preview().to());
    }

    private void change(OperationDetailView view, String contractId) {
        button(view, "Изменить договор").click();
        var confirmation = dialog(UI.getCurrent());
        @SuppressWarnings("unchecked")
        var select = (Select<String>) all(confirmation).filter(Select.class::isInstance).findFirst().orElseThrow();
        select.setValue(contractId);
        all(confirmation).filter(TextArea.class::isInstance).map(TextArea.class::cast)
                .findFirst().orElseThrow().setValue("Новое решение вкладки B");
        button(confirmation, "Подтвердить выбор").click();
    }

    private static void select(MatchingView view, String id) {
        var row = all(view).filter(c -> id.equals(c.getElement().getAttribute("data-operation-id")))
                .findFirst().orElseThrow();
        all(row).filter(Checkbox.class::isInstance).map(Checkbox.class::cast).findFirst().orElseThrow().setValue(true);
    }

    private Dialog dialog(UI ui) {
        roundTrip();
        return find(Dialog.class, ui).withCondition(Dialog::isOpened).single();
    }

    private Button button(Component root, String caption) {
        return find(Button.class, root).withText(caption).single();
    }

    private static Stream<Component> all(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(MultiTabConfirmationComponentTest::all));
    }

    private static String text(Component root) {
        return all(root).map(c -> c.getElement().getText()).reduce("", (a, b) -> a + b);
    }
}
