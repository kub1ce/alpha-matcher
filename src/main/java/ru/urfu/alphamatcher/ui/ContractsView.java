package ru.urfu.alphamatcher.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
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
    private static final String ALL_TYPES = "Все типы договоров";
    private static final class RegistryState implements java.io.Serializable {
        String text = "";
        String type = ALL_TYPES;
        String sort;
        boolean descending;
        int page;
    }
    private final DemoDataService data;
    private final DemoContractService contracts;
    private final DemoOperationState state;
    private final TextField search = new TextField();
    private final Select<String> type = new Select<>();
    private final Span count = UiParts.text("registry-count", "");
    private final Div rows = UiParts.div("contracts-rows");
    private final AppPagination pagination;
    private final Map<String, Button> sortButtons = new LinkedHashMap<>();
    private final RegistryState saved;

    public ContractsView(DemoDataService data, DemoContractService contracts) {
        this.data = data;
        this.contracts = contracts;
        this.state = data.state();
        UI ui = UI.getCurrent();
        RegistryState current = ComponentUtil.getData(ui, RegistryState.class);
        if (current == null) {
            current = new RegistryState();
            ComponentUtil.setData(ui, RegistryState.class, current);
        }
        saved = current;
        pagination = new AppPagination(target -> { saved.page = target; refresh(); });
        DemoStateUpdates.bind(this, state, this::refresh);
        setClassName("contracts-page app-page");
        search.setPlaceholder("Поиск по номеру договора, стороне или ИНН...");
        search.setAriaLabel("Поиск договоров");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.setValue(saved.text);
        search.addValueChangeListener(e -> { saved.text = e.getValue(); refreshFromStart(); });
        type.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of(ALL_TYPES),
                contracts.types().stream()).toList());
        type.setValue(saved.type);
        type.setAriaLabel("Тип договора");
        type.addValueChangeListener(e -> { saved.type = e.getValue(); refreshFromStart(); });
        Button reset = UiParts.iconButton("registry-reset", "", VaadinIcon.ROTATE_LEFT, false, () -> {
            search.clear(); type.setValue(ALL_TYPES); refreshFromStart();
        });
        reset.getElement().setAttribute("aria-label", "Сбросить поиск и фильтр");
        Div filters = UiParts.div("registry-filters contracts-filters",
                UiParts.div("registry-topline", UiParts.div("registry-title-block",
                        UiParts.text("registry-title", "Реестр договоров"), count)),
                UiParts.div("contracts-filter-row app-filter-controls", search, type, reset));
        Div header = UiParts.div("contracts-grid contracts-header",
                sortButton("Номер договора", "id"), sortButton("Дата", "date"),
                sortButton("Предмет / тип договора", "subject"), sortButton("Плательщик", "payer"),
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
            if (key.equals(saved.sort)) saved.descending = !saved.descending;
            else { saved.sort = key; saved.descending = false; }
            refreshFromStart();
        });
        button.setIconAfterText(true);
        sortButtons.put(key, button);
        return button;
    }

    private void refreshFromStart() { saved.page = 0; refresh(); }
    private void refresh() {
        String selectedType = ALL_TYPES.equals(saved.type) ? "" : saved.type;
        var result = contracts.search(new DemoContractService.Query(saved.text, selectedType, saved.sort, saved.descending),
                saved.page, AppPagination.PAGE_SIZE, state);
        saved.page = result.page();
        count.setText("Всего договоров: " + data.contracts().size() + (result.total() == data.contracts().size()
                ? "" : " · Найдено: " + result.total()));
        pagination.update(result.total(), saved.page);
        sortButtons.forEach((key, button) -> {
            boolean active = key.equals(saved.sort) || saved.sort == null && saved.text.isBlank() && "id".equals(key);
            button.setIcon(SortIcons.icon(active, saved.descending));
            button.getElement().setAttribute("data-sort-state", active ? (saved.descending ? "desc" : "asc") : "none");
            button.getElement().setAttribute("aria-label", button.getText() + ": "
                    + (active ? (saved.descending ? "по убыванию" : "по возрастанию") : "без сортировки"));
        });
        rows.removeAll();
        if (result.rows().isEmpty()) rows.add(UiParts.text("registry-empty", "Договоры по заданным условиям не найдены"));
        for (var contract : result.rows()) rows.add(row(contract));
    }

    private Div row(DemoDataService.Contract contract) {
        RouterLink id = new RouterLink(contracts.number(contract), ContractDetailView.class,
                new RouteParameters("id", contract.id()));
        id.setClassName("registry-reference");
        Span subject = clipped("contracts-subject", data.contractDescription(contract));
        DemoDataService.Party payer = data.contractParty(contract, true, state);
        DemoDataService.Party recipient = data.contractParty(contract, false, state);
        DemoDataService.Party beneficiary = contract.beneficiary();
        Div row = UiParts.div("contracts-grid contracts-row", id, UiParts.text("contracts-date", contract.date()),
                subject, party(contract.payer(), payer), party(contract.recipient(), recipient),
                beneficiary == null ? UiParts.text("contracts-absent", "Не указан")
                        : UiParts.div("contracts-party", UiParts.beneficiaryBadge(beneficiary.name()),
                                UiParts.text("contracts-inn", "ИНН: " + (beneficiary.inn().isBlank()
                                        ? "Не указан" : beneficiary.inn()))),
                UiParts.text("contracts-operation-count", String.valueOf(contracts.linkedOperationCount(contract.id(), state))));
        row.getElement().setAttribute("data-contract-id", contract.id());
        var match = contracts.match(contract, saved.text, state);
        if (match != null && match != DemoContractService.Match.ALL) {
            id.getElement().setAttribute("title", match.label());
            id.getElement().setAttribute("aria-label", contracts.number(contract) + " · " + match.label());
        }
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
