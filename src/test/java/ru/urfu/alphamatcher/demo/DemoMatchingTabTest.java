package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoMatchingTabTest {
    private final UI tabA = new UI();
    private final UI tabB = new UI();
    private final DemoDataService data = new ru.urfu.alphamatcher.demo.EngineStubDataService();
    private final DemoMatchingService matching = new DemoMatchingService(data);
    private final DemoOperationState state = new DemoOperationState(data.fixtures());

    @BeforeEach
    void login() {
        var session = mock(VaadinSession.class);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
        when(session.getUIs()).thenReturn(List.of(tabA, tabB));
        VaadinSession.setCurrent(session);
        DemoSession.login(DemoRole.DECISION_MAKER);
        matching.loadTestBatch(state);
    }

    @AfterEach
    void clear() { UI.setCurrent(null); VaadinSession.setCurrent(null); }

    private DemoMatchingDraft draft(UI ui) { UI.setCurrent(ui); return DemoSession.matchingDraft(); }

    private DemoMatchingService.Preview preview() {
        return matching.preview(state, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), false);
    }

    @Test
    void otherTabsSelectionIsAbsentFromConfirmationAndApplication() {
        var a = draft(tabA);
        a.save(preview(), "Вкладка A");
        a.selected().add("PAY-2026-000196");
        var b = draft(tabB);
        b.save(preview(), "Вкладка B");
        b.selected().add("PAY-2026-000197");
        assertNotSame(a, b);
        assertEquals(Set.of("PAY-2026-000196"), a.selected());
        var confirmed = Set.copyOf(a.selected());
        b.selectResults(b.preview().rows());
        var result = matching.apply(state, a.preview(), confirmed, "Вкладка A");
        assertEquals(1, result.applied());
        assertTrue(state.isLinked("PAY-2026-000196"));
        assertFalse(state.isLinked("PAY-2026-000197"));
        assertEquals(List.of("PAY-2026-000196"), state.batchRuns().getLast().operationIds());
        assertThrows(IllegalStateException.class, () -> matching.apply(
                state, b.preview(), Set.of("PAY-2026-000197"), "Вкладка B"));
    }

    @Test
    void navigationRetainsOnlyCurrentTabsDraftAndLogoutClearsBoth() {
        var a = draft(tabA);
        a.save(preview(), "Вкладка A");
        a.selected().add("PAY-2026-000196");
        a.page(2);
        var b = draft(tabB);
        assertNull(b.preview());
        assertTrue(b.selected().isEmpty());
        assertSame(a, draft(tabA));
        assertEquals(2, draft(tabA).page());
        assertEquals(Set.of("PAY-2026-000196"), draft(tabA).selected());
        DemoSession.logout();
        assertNotSame(a, draft(tabA));
        assertTrue(draft(tabA).selected().isEmpty());
        assertNotSame(b, draft(tabB));
    }
}
