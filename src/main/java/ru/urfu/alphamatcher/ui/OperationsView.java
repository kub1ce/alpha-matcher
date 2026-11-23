package ru.urfu.alphamatcher.ui;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState;
import ru.urfu.alphamatcher.demo.DemoSession;
import ru.urfu.alphamatcher.matching.JdbcDemoDataService;

@Route(value = "section/operations", layout = MainLayout.class)
@PageTitle("Реестр операций - А.Выгодоприобретатель")
public class OperationsView extends Div {
    static final class RegistryState implements Serializable {
        String search = "";
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 10, 12);
        String status = "Все состояния";
        String method = "Все способы";
        String sort = "date";
        boolean descending = true;
        int page;
    }

    private final DemoDataService data;
    private final DemoOperationState state;
    private final RegistryState saved;
    private final TextField search = new TextField();
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final Select<String> status = new Select<>();
    private final Select<String> method = new Select<>();
    private final Span count = UiParts.text("", "");
    private final AppPagination pagination;
    private final Div rows = UiParts.div("registry-rows");
    private final Div contextMenu = UiParts.div("operation-context-menu");
    private String contextOperationId;
    private final Map<String, com.vaadin.flow.component.button.Button> sortButtons = new LinkedHashMap<>();
    public OperationsView(DemoDataService data) {
        this.data = data;
        state = data.state();
        DemoStateUpdates.bind(this, state, this::refresh);
        UI ui = UI.getCurrent();
        RegistryState current = ComponentUtil.getData(ui, RegistryState.class);
        if (current == null) {
            current = new RegistryState();
            ComponentUtil.setData(ui, RegistryState.class, current);
        }
        saved = current;
        pagination = new AppPagination(target -> { saved.page = target; refresh(); });
        setClassName("operations-page app-page");
        Div filterCard = UiParts.div("registry-filters");
        count.addClassName("registry-count");
        Div title = UiParts.div("registry-title-block", UiParts.text("registry-title", "Реестр операций"), count);
        var matching = UiParts.iconButton("registry-matching app-action app-action--primary",
                "Автопривязка за период", VaadinIcon.PLAY_CIRCLE_O, false, () -> {
            String dates = "?from=" + (from.getValue() == null ? "" : from.getValue())
                    + "&to=" + (to.getValue() == null ? "" : to.getValue());
            UI.getCurrent().navigate("section/matching" + dates);
        });
        Div actions = UiParts.div("registry-actions", matching);
        if (data instanceof JdbcDemoDataService) {
            actions.add(UiParts.button("app-action app-action--secondary", "Предпросмотр по фильтру", () -> {
                ComponentUtil.setData(ui, BulkPreviewView.RegistryFilter.class,
                        new BulkPreviewView.RegistryFilter(registryQuery(), DemoSession.user()));
                ui.navigate(BulkPreviewView.class);
            }));
        }
        filterCard.add(UiParts.div("registry-topline", title, actions));
        search.setPlaceholder("Поиск по референсу, ФИО, ИНН или назначению...");
        search.setAriaLabel("Поиск операций");
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.setValue(saved.search);
        search.addValueChangeListener(e -> { saved.search = e.getValue(); refreshFromStart(); });
        from.setPlaceholder("Начало периода");
        from.setAriaLabel("Дата с");
        from.setI18n(russianDates());
        to.setPlaceholder("Конец периода");
        to.setAriaLabel("Дата по");
        to.setI18n(russianDates());
        from.setValue(saved.from);
        to.setValue(saved.to);
        from.addValueChangeListener(e -> { saved.from = e.getValue(); refreshFromStart(); });
        to.addValueChangeListener(e -> { saved.to = e.getValue(); refreshFromStart(); });
        status.setItems("Все состояния", "Обработана", "Не обработана");
        status.setValue(saved.status);
        status.setAriaLabel("Состояние обработки");
        status.addValueChangeListener(e -> { saved.status = e.getValue(); refreshFromStart(); });
        method.setItems("Все способы", "Автоматически", "Вручную");
        method.setValue(saved.method);
        method.setAriaLabel("Способ привязки");
        method.addValueChangeListener(e -> { saved.method = e.getValue(); refreshFromStart(); });
        var reset = UiParts.button("registry-reset", "↶", () -> {
            search.clear(); from.clear(); to.clear();
            status.setValue("Все состояния"); method.setValue("Все способы");
            refreshFromStart();
        });
        reset.getElement().setAttribute("aria-label", "Сбросить фильтры");
        filterCard.add(UiParts.div("registry-filter-row app-filter-controls", search, from, UiParts.text("registry-dash", "-"),
                to, status, method, reset));

        Div tableCard = UiParts.div("registry-table-card");
        tableCard.add(UiParts.div("registry-table-viewport",
                UiParts.div("registry-grid registry-header",
                        sortButton("Операция", "id"), sortButton("Дата и время", "date"),
                        sortButton("Плательщик", "payer"), sortButton("Получатель", "recipient"),
                        UiParts.text("", "ИНН сторон"), sortButton("Сумма", "amount"),
                        UiParts.text("", "Назначение платежа"), UiParts.text("", "Договор"),
                        UiParts.text("", "Состояние")), rows));
        tableCard.add(pagination);
        contextMenu.add(UiParts.button("operation-context-action", "Перейти к ручной привязке", () -> {
            contextMenu.setVisible(false);
            if (contextOperationId != null) UI.getCurrent().navigate(ManualReviewDetailView.class,
                    new RouteParameters("id", contextOperationId));
        }));
        contextMenu.setVisible(false);
        add(filterCard, tableCard, contextMenu);
        getElement().addEventListener("click", event -> contextMenu.setVisible(false));
        refresh();
    }

    private DatePicker.DatePickerI18n russianDates() {
        return new DatePicker.DatePickerI18n().setDateFormat("dd.MM.yyyy").setFirstDayOfWeek(1)
                .setMonthNames(java.util.List.of("январь", "февраль", "март", "апрель", "май", "июнь",
                        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"))
                .setWeekdays(java.util.List.of("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота"))
                .setWeekdaysShort(java.util.List.of("вс", "пн", "вт", "ср", "чт", "пт", "сб"))
                .setToday("Сегодня").setCancel("Отмена");
    }

    private com.vaadin.flow.component.button.Button sortButton(String label, String key) {
        var button = UiParts.button("registry-sort", label, () -> {
            if (saved.sort.equals(key)) saved.descending = !saved.descending;
            else { saved.sort = key; saved.descending = key.equals("date"); }
            refreshFromStart();
        });
        button.setIconAfterText(true);
        button.getElement().setAttribute("data-sort", key);
        sortButtons.put(key, button);
        return button;
    }

    private void refreshFromStart() { saved.page = 0; refresh(); }
    private DemoOperationState.Query registryQuery() {
        String selectedStatus = "Все состояния".equals(status.getValue()) ? "" : status.getValue();
        String selectedMethod = "Все способы".equals(method.getValue()) ? "" : method.getValue();
        return new DemoOperationState.Query(saved.search, saved.from, saved.to, selectedStatus,
                selectedMethod, saved.sort, saved.descending);
    }

    private void refresh() {
        var registry = state.registry(registryQuery(), saved.page, AppPagination.PAGE_SIZE);
        var result = registry.page();
        saved.page = result.page();
        count.setText("Найдено операций: " + result.total() + " из " + registry.catalogTotal());
        pagination.update(result.total(), saved.page);
        sortButtons.forEach((key, button) -> {
            String label = switch (key) {
                case "id" -> "Операция";
                case "date" -> "Дата и время";
                case "payer" -> "Плательщик";
                case "recipient" -> "Получатель";
                default -> "Сумма";
            };
            boolean active = saved.sort.equals(key);
            button.setText(label);
            button.setIcon(SortIcons.icon(active, saved.descending));
            button.getElement().setAttribute("data-sort-state", active ? (saved.descending ? "desc" : "asc") : "none");
            button.getElement().setAttribute("aria-label", label + ": "
                    + (active ? (saved.descending ? "по убыванию" : "по возрастанию") : "без сортировки"));
        });
        rows.removeAll();
        if (result.rows().isEmpty()) rows.add(UiParts.text("registry-empty", "Операции по заданным условиям не найдены"));
        for (DemoDataService.Operation op : result.rows()) rows.add(row(op));
    }

    private Div row(DemoDataService.Operation op) {
        DemoOperationState.Match match = state.match(op.id());
        String linked = op.sourceVersion().isEmpty() ? match == null ? "" : match.contractId() : op.initialContract();
        RouterLink id = OperationNavigation.link(op.id(), op.id(), "operations");
        id.setClassName("registry-reference");
        id.getElement().setAttribute("title", op.id());
        Span payer = UiParts.text("registry-party-name", op.payer().name());
        payer.getElement().setAttribute("title", op.payer().name());
        Span recipient = UiParts.text("registry-party-name", op.recipient().name());
        recipient.getElement().setAttribute("title", op.recipient().name());
        Div inns = UiParts.div("registry-inns",
                inn("Плат.:", op.payer().inn()), inn("Получ.:", op.recipient().inn()));
        Span purpose = UiParts.text("registry-purpose", op.purpose());
        purpose.getElement().setAttribute("title", op.purpose());
        Div row = UiParts.div("registry-grid registry-row", id,
                UiParts.text("registry-date", op.date()), payer, recipient, inns,
                UiParts.text("registry-amount", op.amountText()),
                purpose,
                linked.isEmpty() ? UiParts.text("registry-no-contract", "Нет договора")
                        : contextLink("contracts", linked, linked),
                UiParts.text(linked.isEmpty() ? "status unprocessed" : "status processed",
                        linked.isEmpty() ? "Не обработана" : "Обработана"));
        row.getElement().setAttribute("data-operation-id", op.id());
        row.getElement().executeJs("this.addEventListener('contextmenu', event => event.preventDefault())");
        row.getElement().addEventListener("contextmenu", event -> {
            contextOperationId = op.id();
            double x = event.getEventData().get("event.clientX").asDouble();
            double y = event.getEventData().get("event.clientY").asDouble();
            double viewportWidth = event.getEventData().get("window.innerWidth").asDouble();
            double viewportHeight = event.getEventData().get("window.innerHeight").asDouble();
            contextMenu.getStyle().set("left", Math.max(0, Math.min(x, viewportWidth - 230)) / 0.8 + "px");
            contextMenu.getStyle().set("top", Math.max(0, Math.min(y, viewportHeight - 48)) / 0.8 + "px");
            contextMenu.setVisible(true);
        }).addEventData("event.clientX").addEventData("event.clientY")
                .addEventData("window.innerWidth").addEventData("window.innerHeight");
        return row;
    }

    private Div inn(String label, String number) {
        Span innValue = UiParts.text("registry-inn-value", value(number));
        innValue.getElement().setAttribute("title", value(number));
        return UiParts.div("registry-inn-line", UiParts.text("registry-inn-label", label), innValue);
    }

    private RouterLink contextLink(String key, String id, String text) {
        RouterLink link = "contracts".equals(key)
                ? new RouterLink(text, ContractDetailView.class, new RouteParameters("id", id))
                : new RouterLink(text, ContextPlaceholderView.class,
                        new RouteParameters(java.util.Map.of("key", key, "id", id)));
        link.setClassName("registry-contract-link");
        return link;
    }
    private String value(String text) { return text.isBlank() ? "Не указан" : text; }
}
