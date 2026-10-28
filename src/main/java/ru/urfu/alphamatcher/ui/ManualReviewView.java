package ru.urfu.alphamatcher.ui;

import java.util.stream.Stream;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoManualReviewService;
import ru.urfu.alphamatcher.demo.DemoOperationState;

@Route(value = "section/manual", layout = MainLayout.class)
@PageTitle("Очередь ручного разбора - А.Выгодоприобретатель")
public class ManualReviewView extends Div {
    private final DemoManualReviewService review;
    private final DemoOperationState state;
    private final TextField search = new TextField();
    private final Select<String> reason = new Select<>();
    private final Span count = UiParts.text("manual-count", "");
    private final Icon warningIcon = UiParts.icon("inline-icon", VaadinIcon.EXCLAMATION_CIRCLE_O);
    private final Div rows = UiParts.div("manual-rows");
    private final AppPagination pagination = new AppPagination(target -> { page = target; refresh(); });
    private int page;

    public ManualReviewView(DemoDataService data, DemoManualReviewService review) {
        this.review = review;
        this.state = data.state();
        setClassName("manual-review-page app-page");
        search.setPlaceholder("Поиск по референсу, сторонам или назначению...");
        search.setAriaLabel("Поиск в очереди ручного разбора");
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(e -> refreshFromStart());
        reason.setItems(Stream.concat(Stream.of("Все причины разбора"), review.reasons(state).stream()).toList());
        reason.setValue("Все причины разбора");
        reason.setAriaLabel("Причина разбора");
        reason.addValueChangeListener(e -> refreshFromStart());
        Button reset = UiParts.iconButton("registry-reset", "", VaadinIcon.ROTATE_LEFT, false, () -> {
            search.clear(); reason.setValue("Все причины разбора"); refreshFromStart();
        });
        reset.getElement().setAttribute("aria-label", "Сбросить поиск и фильтр");
        Div filters = UiParts.div("registry-filters manual-filters",
                UiParts.div("registry-topline", UiParts.div("manual-title-copy",
                        UiParts.div("registry-title", warningIcon,
                                UiParts.text("", "Очередь ручного разбора")),
                        UiParts.text("registry-count", "Операции, требующие проверки сотрудником перед привязкой к договору.")), count),
                UiParts.div("manual-filter-row app-filter-controls", search, reason, reset));
        Div header = UiParts.div("manual-grid manual-header",
                UiParts.text("", "Операция"), UiParts.text("", "Дата"),
                UiParts.text("", "Плательщик → Получатель"), UiParts.text("", "Сумма"),
                UiParts.text("", "Причина разбора"), UiParts.text("", "Кандидаты"),
                UiParts.text("", "Действие"));
        Div table = UiParts.div("registry-table-card manual-table-card",
                UiParts.div("manual-table-viewport", header, rows),
                pagination);
        add(filters, table);
        refresh();
    }

    private void refreshFromStart() { page = 0; refresh(); }
    private void refresh() {
        String selectedReason = "Все причины разбора".equals(reason.getValue()) ? "" : reason.getValue();
        var result = review.search(search.getValue(), selectedReason, page, AppPagination.PAGE_SIZE, state);
        page = result.page();
        int total = review.pendingCount(state);
        warningIcon.setVisible(total > 0);
        count.setClassName(total > 0 ? "manual-count" : "manual-count is-empty");
        count.setText(total > 0 ? "Ожидает проверки: " + total : "Нет операций для разбора");
        pagination.update(result.total(), page);
        rows.removeAll();
        if (result.rows().isEmpty()) rows.add(UiParts.text("registry-empty", total == 0
                ? "Нет операций, ожидающих ручного разбора" : "Операции по заданным условиям не найдены"));
        for (var operation : result.rows()) rows.add(row(operation));
    }

    private Div row(DemoDataService.Operation operation) {
        RouterLink id = OperationNavigation.link(operation.id(), operation.id(), "manual");
        id.setClassName("registry-reference");
        Span explanation = UiParts.text("manual-reason-explanation", review.explanation(operation, state));
        explanation.getElement().setAttribute("title", review.explanation(operation, state));
        Button decide = UiParts.iconButton("app-action app-action--success manual-decide", "Разобрать",
                VaadinIcon.CHECK, false,
                () -> UI.getCurrent().navigate(ManualReviewDetailView.class,
                        new RouteParameters("id", operation.id())));
        RouterLink card = OperationNavigation.link("Карточка", operation.id(), "manual");
        card.setClassName("manual-card-link");
        return UiParts.div("manual-grid manual-row", id, UiParts.text("registry-date", operation.date()),
                UiParts.div("manual-parties", clipped(operation.payer().name()),
                        UiParts.text("muted", "→ " + operation.recipient().name())),
                UiParts.text("registry-amount", operation.amountText()),
                UiParts.div("manual-reason", UiParts.text("manual-reason-chip", review.reason(operation, state)), explanation),
                UiParts.text("manual-candidate-count", String.valueOf(review.candidates(operation, state).size())),
                UiParts.div("manual-row-actions", decide, card));
    }

    private Span clipped(String value) {
        Span span = UiParts.text("manual-party-name", value);
        span.getElement().setAttribute("title", value);
        return span;
    }
}
