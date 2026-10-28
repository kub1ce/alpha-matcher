package ru.urfu.alphamatcher.ui;

import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;
import com.vaadin.flow.component.Component;
import java.util.List;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState;
import ru.urfu.alphamatcher.demo.DemoOperationState.AuditEvent;
import ru.urfu.alphamatcher.demo.DemoReportingService;
import ru.urfu.alphamatcher.demo.DemoPermissions;

@Route(value = "section/audit", layout = MainLayout.class)
@PageTitle("Журнал действий - А.Выгодоприобретатель")
public class AuditLogView extends Div {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final DemoOperationState state;
    private final DemoReportingService reporting;
    private final TextField search = new TextField();
    private final Select<String> actor = new Select<>();
    private final Select<String> action = new Select<>();
    private final Div rows = UiParts.div("report-table-body");
    private final Span count = UiParts.text("registry-count", "");
    private final AppPagination pagination = new AppPagination(target -> { page = target; refresh(); });
    private Button dateSort;
    private Dialog activeDetails;
    private boolean accessDenied;
    private List<Component> protectedContent = List.of();
    private int page;
    private boolean descending = true;

    public AuditLogView(DemoDataService data, DemoReportingService reporting) {
        this.state = data.state();
        this.reporting = reporting;
        setClassName("audit-log-page app-page");
        if (!ensureAuditAccess()) return;
        initialize();
    }

    private void initialize() {
        search.setPlaceholder("Поиск по операции, действию или значению...");
        search.setAriaLabel("Поиск в журнале действий");
        search.setValueChangeMode(ValueChangeMode.EAGER);
        actor.setItems(Stream.concat(Stream.of("Все исполнители"), reporting.actors(state).stream()).toList());
        actor.setValue("Все исполнители");
        actor.setAriaLabel("Исполнитель");
        action.setItems(Stream.concat(Stream.of("Все действия"), reporting.actions(state).stream()).toList());
        action.setValue("Все действия");
        action.setAriaLabel("Тип действия");
        Button reset = UiParts.iconButton("registry-reset", "", VaadinIcon.ROTATE_LEFT, false, () -> {
            search.clear(); actor.setValue("Все исполнители"); action.setValue("Все действия"); refreshFromStart();
        });
        reset.getElement().setAttribute("aria-label", "Сбросить фильтры журнала");
        add(UiParts.div("registry-filters report-filters",
                UiParts.div("registry-topline", UiParts.div("",
                        UiParts.text("registry-title", "Журнал действий"),
                        UiParts.text("registry-count", "Подтверждённые изменения операций. Предпросмотры в журнал не входят."))),
                UiParts.div("report-filter-row audit-filter-row app-filter-controls", search, actor, action, reset)));
        dateSort = UiParts.button("registry-sort", "Дата и время", () -> {
            descending = !descending; refreshFromStart();
        });
        dateSort.setIconAfterText(true);
        Div header = UiParts.div("report-audit-grid report-table-header",
                dateSort, UiParts.text("", "Операция"), UiParts.text("", "Действие"),
                UiParts.text("", "Предыдущее значение"), UiParts.text("", "Новое значение"),
                UiParts.text("", "Исполнитель"), UiParts.text("", "Способ"));
        add(UiParts.div("registry-table-card report-table-card",
                UiParts.div("report-table-viewport", header, rows),
                pagination));
        search.addValueChangeListener(event -> refreshFromStart());
        actor.addValueChangeListener(event -> refreshFromStart());
        action.addValueChangeListener(event -> refreshFromStart());
        refresh();
    }

    private void refreshFromStart() { page = 0; refresh(); }
    private void refresh() {
        if (!ensureAuditAccess()) return;
        String selectedActor = "Все исполнители".equals(actor.getValue()) ? "" : actor.getValue();
        String selectedAction = "Все действия".equals(action.getValue()) ? "" : action.getValue();
        var result = reporting.audit(state, search.getValue(), selectedActor, selectedAction,
                descending, page, AppPagination.PAGE_SIZE);
        page = result.page();
        rows.removeAll();
        if (result.total() == 0) rows.add(UiParts.div("report-empty", UiParts.text("",
                state.auditEvents().isEmpty() ? "Изменений пока нет. Просмотры и поиск не записываются в журнал."
                        : "По заданным условиям записи не найдены.")));
        else for (AuditEvent event : result.rows()) rows.add(row(event));
        count.setText("Найдено событий: " + result.total());
        pagination.update(result.total(), page);
        dateSort.setText("Дата и время");
        dateSort.setIcon(SortIcons.icon(true, descending));
        dateSort.getElement().setAttribute("data-sort-state", descending ? "desc" : "asc");
        dateSort.getElement().setAttribute("aria-label", "Дата и время: "
                + (descending ? "по убыванию" : "по возрастанию"));
    }
    private Div row(AuditEvent event) {
        Component object = event.operationId() == null || event.operationId().isBlank()
                ? UiParts.text("muted", "Не связано с операцией")
                : OperationNavigation.link(event.operationId(), event.operationId(), "audit");
        if (object instanceof RouterLink link) link.setClassName("registry-reference");
        Button actionText = UiParts.button("report-action-link", event.action(), () -> showDetails(event));
        actionText.getElement().setAttribute("title", "Подробнее о действии: " + event.action());
        Div actionCell = UiParts.div("report-action-cell", actionText);
        if (event.runId() != null) actionCell.add(UiParts.text("muted small", event.runId()));
        return UiParts.div("report-audit-grid report-table-row",
                UiParts.text("muted", event.time().format(DATE_TIME)), object, actionCell,
                cell(event.previous(), "report-truncate muted"), cell(event.current(), "report-truncate"),
                cell(event.actor(), "report-truncate"),
                UiParts.text("report-method " + ("Автоматически".equals(event.method()) ? "automatic" : "manual"),
                        event.method()));
    }
    private Span cell(String value, String css) {
        Span span = UiParts.text(css, value);
        span.getElement().setAttribute("title", value);
        return span;
    }

    private void showDetails(AuditEvent event) {
        if (!ensureAuditAccess()) return;
        Dialog dialog = new Dialog();
        activeDetails = dialog;
        dialog.addOpenedChangeListener(change -> {
            if (!change.isOpened() && activeDetails == dialog) activeDetails = null;
        });
        dialog.addClassNames("report-run-dialog", "audit-detail-dialog");
        dialog.setHeaderTitle(event.action() + " · " + event.id());
        Div content = UiParts.div("audit-detail-content");
        Div operation = section("Операция", "audit-detail-grid");
        addIfPresent(operation, "Операция", event.operationId());
        addIfPresent(operation, "Договор", contractId(event));
        content.add(operation);
        Div change = section("Изменение", "audit-detail-grid");
        addIfPresent(change, "Предыдущее значение", event.previous());
        addIfPresent(change, "Новое значение", event.current());
        addIfPresent(change, "Предыдущее состояние", event.previousState(), true);
        ((Div) change.getChildren().skip(1).findFirst().orElseThrow()).addClassName("audit-detail-two-col");
        content.add(change);
        Div author = section("Исполнитель", "audit-detail-grid");
        addIfPresent(author, "Сотрудник", event.actor(), true);
        addIfPresent(author, "Дата и время", event.time().format(DATE_TIME));
        addIfPresent(author, "Способ", event.method());
        addIfPresent(author, "ID запуска", event.runId());
        Div authorGrid = (Div) author.getChildren().skip(1).findFirst().orElseThrow();
        authorGrid.addClassName("audit-detail-two-col");
        content.add(author);
        Div basis = section("Основание", "audit-detail-grid audit-detail-basis");
        switch (event.action()) {
            case "Автоматическая привязка" ->
                    addIfPresent(basis, "Основание автоматической привязки", event.automaticReason(), true);
            case "Ручная привязка" -> {
                addIfPresent(basis, "Первоначальная причина разбора", event.reviewReason());
                addIfPresent(basis, "Обоснование сотрудника", event.justification(), true);
            }
            case "Изменение договора" -> {
                if ("Вручную".equals(event.method())) {
                    addIfPresent(basis, "Обоснование предыдущего решения", event.previousJustification(), true);
                    addIfPresent(basis, "Обоснование сотрудника", event.justification(), true);
                }
            }
            case "Отвязка договора" -> addIfPresent(basis, "Обоснование предыдущего решения",
                    event.previousJustification(), true);
            case "Постановка на ручной разбор" -> addIfPresent(basis, "Причина разбора",
                    event.reviewReason(), true);
            default -> { }
        }
        Div basisGrid = (Div) basis.getChildren().skip(1).findFirst().orElseThrow();
        if (basisGrid.getChildren().findAny().isPresent()) content.add(basis);
        dialog.add(content);
        dialog.getFooter().add(UiParts.button("app-action app-action--secondary", "Закрыть", dialog::close));
        dialog.open();
    }

    private boolean ensureAuditAccess() {
        if (DemoPermissions.allowsCurrent(DemoPermissions.Action.VIEW_AUDIT)) return true;
        if (!accessDenied) protectedContent = getChildren().toList();
        accessDenied = true;
        if (activeDetails != null) {
            activeDetails.close();
            activeDetails = null;
        }
        removeAll();
        add(UiParts.div("registry-filters",
                UiParts.text("registry-title", "Журнал действий"),
                UiParts.text("muted", "Недостаточно полномочий")));
        return false;
    }

    void recheckAccess() {
        if (!ensureAuditAccess()) return;
        if (accessDenied) {
            removeAll();
            accessDenied = false;
            if (protectedContent.isEmpty()) initialize();
            else {
                protectedContent.forEach(this::add);
                protectedContent = List.of();
                refresh();
            }
        } else refresh();
    }

    private String contractId(AuditEvent event) {
        return switch (event.action()) {
            case "Автоматическая привязка", "Ручная привязка", "Изменение договора" -> event.current();
            case "Отвязка договора" -> event.previous();
            default -> null;
        };
    }

    private Div section(String title, String css) {
        return UiParts.div("audit-detail-section", UiParts.text("audit-detail-section-title", title),
                UiParts.div(css));
    }

    private void addIfPresent(Div section, String label, String value) {
        addIfPresent(section, label, value, false);
    }

    private void addIfPresent(Div section, String label, String value, boolean fullWidth) {
        if (value == null || value.isBlank()) return;
        Div grid = (Div) section.getChildren().skip(1).findFirst().orElseThrow();
        Div field = detail(label, value);
        if (fullWidth) field.addClassName("report-detail-full");
        grid.add(field);
    }

    private Div detail(String label, String value) {
        return UiParts.div("report-detail-field", UiParts.text("muted", label),
                UiParts.text("", value == null || value.isBlank() ? "Не указано" : value));
    }
}
