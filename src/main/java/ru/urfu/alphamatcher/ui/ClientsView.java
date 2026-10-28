package ru.urfu.alphamatcher.ui;

import java.io.Serializable;
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
import com.vaadin.flow.server.VaadinSession;
import ru.urfu.alphamatcher.demo.DemoClientService;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState;

@Route(value = "section/clients", layout = MainLayout.class)
@PageTitle("Клиенты и контрагенты - А.Выгодоприобретатель")
public class ClientsView extends Div {
    private static final String SESSION_KEY = "client-registry-view";
    private static final String ALL_TYPES = "Все типы участников";
    private static final String ALL_PARTIES = "Все участники";
    private static final String BANK = "Клиенты банка";
    private static final String EXTERNAL = "Внешние контрагенты";

    static final class RegistryState implements Serializable {
        String search = "";
        String type = ALL_TYPES;
        String affiliation = ALL_PARTIES;
        String sort = "id";
        boolean descending;
        int page;
    }

    private final DemoDataService data;
    private final DemoClientService clients;
    private final DemoOperationState state;
    private final RegistryState saved;
    private final TextField search = new TextField();
    private final Select<String> type = new Select<>();
    private final Select<String> affiliation = new Select<>();
    private final Span count = UiParts.text("registry-count", "");
    private final Div rows = UiParts.div("clients-rows");
    private final AppPagination pagination;
    private final Map<String, Button> sortButtons = new LinkedHashMap<>();

    public ClientsView(DemoDataService data, DemoClientService clients) {
        this.data = data;
        this.clients = clients;
        this.state = data.state();
        RegistryState current = (RegistryState) VaadinSession.getCurrent().getAttribute(SESSION_KEY);
        if (current == null) {
            current = new RegistryState();
            VaadinSession.getCurrent().setAttribute(SESSION_KEY, current);
        }
        saved = current;
        pagination = new AppPagination(target -> { saved.page = target; refresh(); });
        setClassName("clients-page app-page");

        search.setPlaceholder("Поиск по названию, ИНН или ID клиента...");
        search.setAriaLabel("Поиск клиентов");
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.setValue(saved.search);
        search.addValueChangeListener(event -> { saved.search = event.getValue(); refreshFromStart(); });
        type.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of(ALL_TYPES),
                clients.types(state).stream()).toList());
        type.setValue(saved.type);
        type.setAriaLabel("Тип участника");
        type.addValueChangeListener(event -> { saved.type = event.getValue(); refreshFromStart(); });
        affiliation.setItems(ALL_PARTIES, BANK, EXTERNAL);
        affiliation.setValue(saved.affiliation);
        affiliation.setAriaLabel("Принадлежность к клиентам банка");
        affiliation.addValueChangeListener(event -> { saved.affiliation = event.getValue(); refreshFromStart(); });
        Button reset = UiParts.iconButton("registry-reset", "", VaadinIcon.ROTATE_LEFT, false, () -> {
            search.clear(); type.setValue(ALL_TYPES); affiliation.setValue(ALL_PARTIES); refreshFromStart();
        });
        reset.getElement().setAttribute("aria-label", "Сбросить поиск и фильтры");
        Div filters = UiParts.div("registry-filters",
                UiParts.div("registry-topline", UiParts.div("registry-title-block",
                        UiParts.text("registry-title", "Справочник клиентов"), count)),
                UiParts.div("clients-filter-row app-filter-controls", search, type, affiliation, reset));
        Div header = UiParts.div("clients-grid contracts-header",
                sortButton("ID клиента", "id"), sortButton("Наименование / ФИО", "name"),
                sortButton("Тип участника", "type"), sortButton("ИНН", "inn"),
                sortButton("Клиент банка", "bank"), sortButton("PIN", "pin"),
                sortButton("Договоров", "contracts"), listedHeader());
        Div table = UiParts.div("registry-table-card",
                UiParts.div("clients-table-viewport", header, rows), pagination);
        add(filters, table);
        refresh();
    }

    private Button sortButton(String label, String key) {
        Button button = UiParts.button("registry-sort", label, () -> {
            if (saved.sort.equals(key)) saved.descending = !saved.descending;
            else { saved.sort = key; saved.descending = false; }
            refreshFromStart();
        });
        button.setIconAfterText(true);
        sortButtons.put(key, button);
        return button;
    }

    private void refreshFromStart() { saved.page = 0; refresh(); }

    private void refresh() {
        String affiliationKey = BANK.equals(saved.affiliation) ? "bank"
                : EXTERNAL.equals(saved.affiliation) ? "external" : "";
        DemoClientService.Page result = clients.search(new DemoClientService.Query(saved.search,
                ALL_TYPES.equals(saved.type) ? "" : saved.type, affiliationKey,
                saved.sort, saved.descending), saved.page, AppPagination.PAGE_SIZE, state);
        saved.page = result.page();
        int allCount = data.parties(state).size();
        count.setText("Всего участников: " + allCount
                + (result.total() == allCount ? "" : " · Найдено: " + result.total()));
        pagination.update(result.total(), result.page());
        sortButtons.forEach((key, button) -> {
            boolean active = saved.sort.equals(key);
            button.setIcon(SortIcons.icon(active, saved.descending));
            button.getElement().setAttribute("data-sort-state", active
                    ? (saved.descending ? "desc" : "asc") : "none");
            button.getElement().setAttribute("aria-label", button.getText() + ": "
                    + (active ? (saved.descending ? "по убыванию" : "по возрастанию") : "без сортировки"));
        });
        rows.removeAll();
        if (result.rows().isEmpty()) rows.add(UiParts.text("registry-empty",
                "Клиенты по заданным условиям не найдены"));
        for (DemoDataService.Party party : result.rows()) rows.add(row(party));
    }

    private Div row(DemoDataService.Party party) {
        RouterLink id = link(party.id(), party.id());
        RouterLink name = link(party.id(), party.name());
        name.addClassName("clients-name");
        name.getElement().setAttribute("title", party.name());
        Span type = UiParts.text("contract-id-chip clients-type", party.type());
        type.getElement().setAttribute("title", party.type());
        return UiParts.div("clients-grid clients-row", id, name,
                type,
                UiParts.text("", party.inn().isBlank() ? "Не указан" : party.inn()),
                party.bankClient() ? UiParts.text("status processed", "Да")
                        : UiParts.text("contracts-absent", "Внешний"),
                UiParts.text(party.pin().isBlank() ? "contracts-absent" : "contracts-date",
                        party.pin().isBlank() ? "Не указан" : party.pin()),
                UiParts.text("contracts-operation-count",
                        String.valueOf(clients.contracts(party.id(), state).size())),
                listedMark(party));
    }

    private Span listedHeader() {
        Span header = UiParts.text("clients-list-header", "Э/Т");
        header.getElement().setAttribute("title", "В перечне экстремистов и террористов");
        return header;
    }

    private Div listedMark(DemoDataService.Party party) {
        Div mark = UiParts.div("clients-list-mark");
        mark.getElement().setAttribute("role", "img");
        mark.getElement().setAttribute("aria-label", party.terroristOrExtremistListed()
                ? "В перечне экстремистов и террористов" : "Не в перечне экстремистов и террористов");
        if (party.terroristOrExtremistListed()) mark.add(VaadinIcon.CHECK.create());
        return mark;
    }

    private RouterLink link(String id, String caption) {
        RouterLink link = new RouterLink(caption, ClientDetailView.class, new RouteParameters("id", id));
        link.setClassName("registry-reference");
        return link;
    }
}
