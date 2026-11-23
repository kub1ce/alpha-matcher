package ru.urfu.alphamatcher.ui;

import java.time.format.DateTimeFormatter;
import com.vaadin.flow.component.UI;
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
import ru.urfu.alphamatcher.demo.DemoOperationState.BatchRun;
import ru.urfu.alphamatcher.demo.DemoReportingService;

@Route(value = "section/runs", layout = MainLayout.class)
@PageTitle("История запусков - А.Выгодоприобретатель")
public class RunHistoryView extends Div {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final DemoOperationState state;
    private final DemoReportingService reporting;
    private final TextField search = new TextField();
    private final Select<String> type = new Select<>();
    private final Div rows = UiParts.div("report-table-body");
    private final Span count = UiParts.text("registry-count", "");
    private final AppPagination pagination = new AppPagination(target -> { page = target; refresh(); });
    private final Button dateSort;
    private int page;
    private boolean descending = true;

    public RunHistoryView(DemoDataService data, DemoReportingService reporting) {
        this.state = data.state();
        DemoStateUpdates.bind(this, state, this::refresh);
        this.reporting = reporting;
        setClassName("run-history-page app-page");
        search.setPlaceholder("Поиск по ID, инициатору или периоду...");
        search.setAriaLabel("Поиск пакетных действий");
        search.setValueChangeMode(ValueChangeMode.EAGER);
        type.setItems("Все действия", "Пакетная автопривязка", "Передача на ручной разбор");
        type.setValue("Все действия");
        type.setAriaLabel("Тип пакетного действия");
        Button reset = UiParts.iconButton("registry-reset", "", VaadinIcon.ROTATE_LEFT, false, () -> {
            search.clear(); type.setValue("Все действия"); refreshFromStart();
        });
        reset.getElement().setAttribute("aria-label", "Сбросить фильтры истории");
        add(UiParts.div("registry-filters report-filters",
                UiParts.div("registry-topline", UiParts.div("",
                        UiParts.text("registry-title", "История пакетных действий"),
                        UiParts.text("registry-count", "Подтверждённые пакетные действия."))),
                UiParts.div("report-filter-row app-filter-controls", search, type, reset)));
        if (data instanceof ru.urfu.alphamatcher.matching.JdbcDemoDataService)
            add(new RouterLink("Долговременные пакетные задания", BulkPreviewView.class));
        dateSort = UiParts.button("registry-sort", "Дата и время", () -> {
            descending = !descending; refreshFromStart();
        });
        dateSort.setIconAfterText(true);
        Div header = UiParts.div("report-run-grid report-table-header",
                UiParts.text("", "ID действия"), dateSort, UiParts.text("", "Период"),
                UiParts.text("", "Инициатор"), UiParts.text("report-run-volume", "Объём / выбор / допуск"),
                UiParts.text("", "Привязано"), UiParts.text("", "В разбор"),
                UiParts.text("", "Пропущено"), UiParts.text("", "Статус"),
                UiParts.text("", "Действия"));
        header.getChildren().skip(4).findFirst().ifPresent(component -> component.getElement()
                .setAttribute("title", "Рассмотрено в предпросмотре / выбрано сотрудником / допустимо к действию"));
        add(UiParts.div("registry-table-card report-table-card",
                UiParts.div("report-table-viewport", header, rows),
                pagination));
        search.addValueChangeListener(event -> refreshFromStart());
        type.addValueChangeListener(event -> refreshFromStart());
        refresh();
    }

    private void refreshFromStart() { page = 0; refresh(); }
    private void refresh() {
        String selectedType = "Все действия".equals(type.getValue()) ? "" : type.getValue();
        var result = reporting.runs(state, search.getValue(), selectedType, descending, page, AppPagination.PAGE_SIZE);
        page = result.page();
        rows.removeAll();
        if (result.total() == 0) rows.add(UiParts.div("report-empty", UiParts.text("", state.batchRuns().isEmpty()
                ? "Подтверждённых пакетных действий пока нет. Предпросмотры не считаются запусками."
                : "По заданным условиям записи не найдены.")));
        else for (BatchRun run : result.rows()) rows.add(row(run));
        count.setText("Найдено действий: " + result.total());
        pagination.update(result.total(), page);
        dateSort.setText("Дата и время");
        dateSort.setIcon(SortIcons.icon(true, descending));
        dateSort.getElement().setAttribute("data-sort-state", descending ? "desc" : "asc");
        dateSort.getElement().setAttribute("aria-label", "Дата и время: "
                + (descending ? "по убыванию" : "по возрастанию"));
    }
    private Div row(BatchRun run) {
        Button details = UiParts.button("report-id-link", run.id(), () -> showDetails(run));
        Button repeat = UiParts.iconButton("app-action app-action--secondary report-repeat", "Повторить",
                VaadinIcon.ROTATE_RIGHT, false, () -> repeat(run));
        Button more = UiParts.button("report-text-link", "Подробнее", () -> showDetails(run));
        String skipped = String.valueOf(run.skipped());
        Span skippedCell = UiParts.text("", skipped);
        skippedCell.getElement().setAttribute("title", "Уже в очереди: " + run.alreadyQueued()
                + "; уже обработаны: " + run.alreadyProcessed()
                + "; без договора среди выбранных: " + run.withoutContract());
        Span initiator = UiParts.text("report-truncate", run.initiator());
        initiator.getElement().setAttribute("title", run.initiator());
        return UiParts.div("report-run-grid report-table-row", details,
                UiParts.text("muted", run.time().format(DATE_TIME)),
                UiParts.text("muted", DemoReportingService.period(run)),
                initiator,
                UiParts.text("", run.considered() + " / " + run.selected() + " / " + run.eligible()),
                UiParts.text("report-positive", String.valueOf(run.applied())),
                UiParts.text("report-warning", String.valueOf(run.manual())), skippedCell,
                UiParts.text(run.skipped() == 0 ? "report-positive" : "report-warning",
                        run.skipped() == 0 ? "Завершено" : "Завершено частично"),
                UiParts.div("report-row-actions", more, repeat));
    }
    private void showDetails(BatchRun run) {
        Dialog dialog = new Dialog();
        dialog.addClassName("report-run-dialog");
        dialog.setHeaderTitle("Сведения о действии " + run.id());
        Div status = detail("Статус", run.skipped() == 0 ? "Завершено" : "Завершено частично");
        Div overview = UiParts.div("report-detail-overview",
                detail("Дата и время", run.time().format(DATE_TIME)),
                detail("Период отбора", DemoReportingService.period(run)),
                initiator(run.initiator()),
                detail("Тип", run.type()),
                status);
        Div results = UiParts.div("report-detail-results",
                result("Рассмотрено в предпросмотре", run.considered(), ""),
                result("Выбрано", run.selected(), ""),
                result("Допустимо к действию", run.eligible(), ""),
                result("Фактически привязано", run.applied(), "safe"),
                result("Фактически в ручной разбор", run.manual(), "warning"),
                result("Без изменения", run.skipped(), ""));
        Div operations = UiParts.div("report-detail-operations",
                UiParts.text("report-section-title", "ИЗМЕНЁННЫЕ ОПЕРАЦИИ"));
        for (String id : run.operationIds()) {
            RouterLink link = OperationNavigation.link(id, id, "runs");
            link.setClassName("registry-reference");
            link.getElement().addEventListener("click", event -> dialog.close());
            operations.add(link);
        }
        dialog.add(overview, results,
                UiParts.text("muted", "Без договора среди выбранных: " + run.withoutContract()
                        + "; уже в очереди: " + run.alreadyQueued()
                        + "; уже обработаны: " + run.alreadyProcessed()
                        + ". Эти признаки не суммируются с итогами действия."), operations);
        dialog.getFooter().add(UiParts.button("app-action app-action--secondary", "Закрыть", dialog::close),
                UiParts.iconButton("app-action app-action--primary", "Новый предпросмотр",
                        VaadinIcon.ROTATE_RIGHT, false, () -> { dialog.close(); repeat(run); }));
        dialog.open();
    }
    private Div detail(String label, String value) {
        return UiParts.div("report-detail-field", UiParts.text("muted", label), UiParts.text("", value));
    }
    private Div initiator(String value) {
        Div field = detail("Инициатор", value);
        field.addClassName("report-detail-full");
        return field;
    }
    private Div result(String label, int value, String tone) {
        return UiParts.div("report-detail-result " + tone, UiParts.text("", label),
                UiParts.text("", String.valueOf(value)));
    }
    private void repeat(BatchRun run) {
        UI.getCurrent().navigate("section/matching?repeat=" + run.id());
    }
}
