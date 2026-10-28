package ru.urfu.alphamatcher.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState;
import ru.urfu.alphamatcher.demo.DemoReportingService;
import ru.urfu.alphamatcher.demo.DemoRole;
import ru.urfu.alphamatcher.demo.DemoSession;

class AuditLogViewAccessTest {
    @AfterEach
    void clearSession() { VaadinSession.setCurrent(null); }

    @Test
    void staleFilterCallbackClearsAuditViewAfterRoleDowngrade() {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        VaadinSession.setCurrent(session);
        DemoSession.login(DemoRole.DECISION_MAKER);
        DemoDataService data = new DemoDataService();
        DemoOperationState state = data.state();
        AuditLogView view = new AuditLogView(data, new DemoReportingService());
        TextField search = (TextField) find(view, "report-filter-row").getChildren()
                .filter(TextField.class::isInstance).findFirst().orElseThrow();
        assertNotNull(find(view, "report-table-card"));

        DemoSession.login(DemoRole.OPERATIONS);
        search.setValue("PAY-2026-000184");
        assertNull(findOrNull(view, "report-table-card"));
        assertEquals("Недостаточно полномочий", ((Span) find(view, "muted")).getText());
        assertTrue(state.auditEvents().isEmpty());
    }

    @Test
    void proactiveRecheckClearsAuditWithoutFilterEvent() {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        VaadinSession.setCurrent(session);
        DemoSession.login(DemoRole.DECISION_MAKER);
        AuditLogView view = new AuditLogView(new DemoDataService(), new DemoReportingService());
        assertNotNull(find(view, "report-table-card"));
        DemoSession.login(DemoRole.OPERATIONS);
        view.recheckAccess();
        assertNull(findOrNull(view, "report-table-card"));
        assertEquals("Недостаточно полномочий", ((Span) find(view, "muted")).getText());
    }

    private static Component find(Component root, String css) {
        Component found = findOrNull(root, css);
        assertNotNull(found, css);
        return found;
    }

    private static Component findOrNull(Component root, String css) {
        if (root.getClassNames().contains(css)) return root;
        return root.getChildren().map(child -> findOrNull(child, css))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }
}
