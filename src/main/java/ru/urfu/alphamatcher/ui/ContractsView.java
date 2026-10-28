package ru.urfu.alphamatcher.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import com.vaadin.flow.component.button.Button;
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
import ru.urfu.alphamatcher.demo.DemoContractService;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState;

@Route(value = "section/contracts", layout = MainLayout.class)
@PageTitle("Реестр договоров - А.Выгодоприобретатель")
public class ContractsView extends Div {
    private final DemoDataService data;
    private final DemoContractService contracts;
    private final DemoOperationState state;
    private final TextField search = new TextField();
    private final Select<String> type = new Select<>();
    private final Span count = UiParts.text("registry-count", "");
    private final Div rows = UiParts.div("contracts-rows");
    private final AppPagination pagination = new AppPagination(target -> { page = target; refresh(); });
    private final Map<String, Button> sortButtons = new LinkedHashMap<>();
    private int page;
    private String sort = "id";
    private boolean descending;

    public ContractsView(DemoDataService data, DemoContractService contracts) {
        this.data = data;
        this.contracts = contracts;
        this.state = data.state();
        setClassName("contracts-page app-page");
        search.setPlaceholder("Поиск по номеру договора, стороне или ИНН...");
        search.setAriaLabel("Поиск договоров");
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(e -> refreshFromStart());
        type.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of("Все типы договоров"),
                contracts.types().stream()).toList());
        type.setValue("Все типы договоров");
        type.setAriaLabel("Тип договора");
        type.addValueChangeListener(e -> refreshFromStart());
        Button reset = UiParts.iconButton("registry-reset", "", VaadinIcon.ROTATE_LEFT, false, () -> {
            search.clear(); type.setValue("Все типы договоров"); refreshFromStart();
        });
        reset.getElement().setAttribute("aria-label", "Сбросить поиск и фильтр");
        Div filters = UiParts.div("registry-filters contracts-filters",
                UiParts.div("registry-topline", UiParts.div("registry-title-block",
                        UiParts.text("registry-title", "Реестр договоров"), count)),
                UiParts.div("contracts-filter-row app-filter-controls", search, type, reset));
        Div header = UiParts.div("contracts-grid contracts-header",
                sortButton("Номер договора", "id"), sortButton("Дата", "date"),
                sortButton("Предмет договора", "subject"), sortButton("Плательщик", "payer"),
                sortButton("Получатель", "recipient"), UiParts.text("", "Выгодоприобретатель"),
                sortButton("Операций", "operations"));
        Div table = UiParts.div("registry-table-card contracts-table-card",
                UiParts.div("contracts-table-viewport", header, rows),
                pagination);
        add(filters, table);
        refresh();
    }

    private Button sortButton(String label, String key) {
        Button button = UiParts.button("registry-sort", label, () -> {
            if (sort.equals(key)) descending = !descending;
            else { sort = key; descending = false; }
            refreshFromStart();
        });
        button.setIconAfterText(true);
        sortButtons.put(key, button);
        return button;
    }

    private void refreshFromStart() { page = 0; refresh(); }
    private void refresh() {
        String selectedType = "Все типы договоров".equals(type.getValue()) ? "" : type.getValue();
        var result = contracts.search(new DemoContractService.Query(search.getValue(), selectedType, sort, descending),
                page, AppPagination.PAGE_SIZE, state);
        page = result.page();
        count.setText("Всего договоров: " + data.contracts().size() + (result.total() == data.contracts().size()
                ? "" : " · Найдено: " + result.total()));
        pagination.update(result.total(), page);
        sortButtons.forEach((key, button) -> {
            boolean active = sort.equals(key);
            button.setIcon(SortIcons.icon(active, descending));
            button.getElement().setAttribute("data-sort-state", active ? (descending ? "desc" : "asc") : "none");
            button.getElement().setAttribute("aria-label", button.getText() + ": "
                    + (active ? (descending ? "по убыванию" : "по возрастанию") : "без сортировки"));
        });
        rows.removeAll();
        if (result.rows().isEmpty()) rows.add(UiParts.text("registry-empty", "Договоры по заданным условиям не найдены"));
        for (var contract : result.rows()) rows.add(row(contract));
    }

    private Div row(DemoDataService.Contract contract) {
        RouterLink id = new RouterLink(contract.id(), ContractDetailView.class, new RouteParameters("id", contract.id()));
        id.setClassName("registry-reference");
        Span subject = clipped("contracts-subject", contract.name());
        DemoDataService.Party payer = data.party(contract.payer());
        DemoDataService.Party recipient = data.party(contract.recipient());
        DemoDataService.Party beneficiary = contract.beneficiary();
        Div row = UiParts.div("contracts-grid contracts-row", id, UiParts.text("contracts-date", contract.date()),
                subject, party(contract.payer(), payer), party(contract.recipient(), recipient),
                beneficiary == null ? UiParts.text("contracts-absent", "Не указан")
                        : UiParts.div("contracts-party", UiParts.beneficiaryBadge(beneficiary.name()),
                                UiParts.text("contracts-inn", "ИНН: " + (beneficiary.inn().isBlank()
                                        ? "Не указан" : beneficiary.inn()))),
                UiParts.text("contracts-operation-count", String.valueOf(contracts.linkedOperations(contract.id(), state).size())));
        row.getElement().setAttribute("data-contract-id", contract.id());
        return row;
    }

    private Div party(String name, DemoDataService.Party value) {
        return UiParts.div("contracts-party", clipped("contracts-party-name", name),
                UiParts.text("contracts-inn", "ИНН: " + (value == null || value.inn().isBlank() ? "Не указан" : value.inn())));
    }

    private Span clipped(String css, String value) {
        Span span = UiParts.text(css, value);
        span.getElement().setAttribute("title", value);
        return span;
    }
}
