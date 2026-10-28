package ru.urfu.alphamatcher.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoManualReviewService;
import ru.urfu.alphamatcher.demo.DemoOperationState;

class FinalBoundaryComponentTest {
    @Test
    void emptyManualViewHasReadableStateAndNoRowsAfterSearchAndReset() {
        DemoOperationState state = new DemoOperationState(List.of());
        DemoDataService data = new DemoDataService() {
            @Override public DemoOperationState state() { return state; }
        };
        ManualReviewView view = new ManualReviewView(data, new DemoManualReviewService(data));
        assertEquals("Нет операций для разбора", ((Span) find(view, "manual-count")).getText());
        assertEquals("Нет операций, ожидающих ручного разбора", ((Span) find(view, "registry-empty")).getText());
        assertEquals(0, ((AppPagination) find(view, "app-pagination")).page());
        assertEquals(1, ((AppPagination) find(view, "app-pagination")).pages());
        assertNull(findOrNull(view, "manual-row"));
        ((TextField) find(view, "manual-filter-row").getChildren()
                .filter(TextField.class::isInstance).findFirst().orElseThrow()).setValue("неизвестный");
        assertEquals("Нет операций, ожидающих ручного разбора", ((Span) find(view, "registry-empty")).getText());
        ((Button) find(view, "registry-reset")).click();
        assertEquals("Нет операций для разбора", ((Span) find(view, "manual-count")).getText());
        assertTrue(state.auditEvents().isEmpty());
    }

    private static Component find(Component component, String css) {
        Component found = findOrNull(component, css);
        assertNotNull(found, "Component with class " + css);
        return found;
    }
    private static Component findOrNull(Component component, String css) {
        if (component.getClassNames().contains(css)) return component;
        return component.getChildren().map(child -> findOrNull(child, css))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }
}
