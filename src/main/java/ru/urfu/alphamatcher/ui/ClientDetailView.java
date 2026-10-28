package ru.urfu.alphamatcher.ui;

import java.util.List;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoClientService;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState;

@Route(value = "client/:id", layout = MainLayout.class)
@PageTitle("Карточка клиента - А.Выгодоприобретатель")
public class ClientDetailView extends Div implements BeforeEnterObserver {
    private final DemoDataService data;
    private final DemoClientService clients;
    private final DemoOperationState state;
    private final Div operationRows = UiParts.div("client-operation-rows");
    private final AppPagination pagination = new AppPagination(this::showOperationPage);
    private List<DemoClientService.RelatedOperation> linkedOperations = List.of();
    private int operationPage;
    private String clientId;

    public ClientDetailView(DemoDataService data, DemoClientService clients) {
        this.data = data;
        this.clients = clients;
        this.state = data.state();
        setClassName("client-detail-page app-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String id = event.getRouteParameters().get("id").orElse("");
        String source = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("from", List.of()).stream().findFirst().orElse(null);
        removeAll();
        DemoDataService.Party party = data.client(id, state);
        if (party == null) {
            add(UiParts.div("client-hero", back(source),
                    UiParts.div("client-heading", UiParts.text("registry-title", "Клиент не найден"),
                            UiParts.text("muted", "Участник с ID " + id + " не найден."))));
            return;
        }
        clientId = id;
        linkedOperations = clients.operations(id, state);
        operationPage = 0;
        add(hero(party, source), contractsSection(party), operationsSection());
    }

    private Div hero(DemoDataService.Party party, String source) {
        Div title = UiParts.div("client-title-line", UiParts.text("client-title", party.name()),
                UiParts.text("contract-id-chip", party.id()),
                UiParts.text("contract-id-chip", party.type()));
        Div status = UiParts.div("client-status-line", UiParts.text(party.bankClient()
                ? "status processed" : "client-external-badge",
                party.bankClient() ? "Клиент банка" : "Внешний контрагент"));
        Div summary = UiParts.div("client-heading", title, status,
                UiParts.div("client-identity", UiParts.text("muted", "ИНН:"),
                        UiParts.text("client-inn", value(party.inn()))));
        if (party.bankClient() && !party.pin().isBlank()) summary.add(UiParts.div("client-identity",
                UiParts.text("muted", "PIN:"), UiParts.text("", party.pin())));
        Div details = UiParts.div("client-requisites");
        if (!party.account().isBlank()) details.add(requisite("Расчётный счёт", party.account()));
        if (!party.bic().isBlank()) details.add(requisite("БИК / Код отделения", party.bic()));
        com.vaadin.flow.component.checkbox.Checkbox listed = new com.vaadin.flow.component.checkbox.Checkbox(
                "В перечне экстремистов и террористов");
        listed.setValue(party.terroristOrExtremistListed());
        listed.setReadOnly(true);
        details.add(listed);
        return UiParts.div("client-hero", UiParts.div("client-hero-top", back(source), summary), details);
    }

    private Div requisite(String label, String value) {
        return UiParts.div("client-requisite", UiParts.text("muted", label), UiParts.text("", value));
    }

    private RouterLink back(String source) {
        RouterLink link = ClientNavigation.back(source);
        link.add(VaadinIcon.ARROW_LEFT.create());
        link.setClassName("detail-back");
        link.getElement().setAttribute("aria-label", "Вернуться на предыдущую страницу приложения");
        return link;
    }

    private Div contractsSection(DemoDataService.Party party) {
        List<DemoClientService.RelatedContract> related = clients.contracts(party.id(), state);
        Div body = UiParts.div("client-contract-list");
        if (related.isEmpty()) body.add(UiParts.text("contract-empty", "Связанных договоров пока нет."));
        for (DemoClientService.RelatedContract item : related) {
            DemoDataService.Contract contract = item.contract();
            RouterLink link = new RouterLink(contract.id(), ContractDetailView.class,
                    new RouteParameters("id", contract.id()));
            link.setQueryParameters(QueryParameters.simple(
                    java.util.Map.of("from", "client/" + party.id())));
            link.setClassName("registry-reference");
            body.add(UiParts.div("client-contract-card",
                    UiParts.div("client-contract-top", link, UiParts.text("contracts-date", contract.date())),
                    UiParts.text("client-contract-subject", contract.name()),
                    UiParts.div("client-contract-meta", UiParts.text("", "Роль: " + String.join(", ", item.roles())),
                            UiParts.text("", "Плат.: " + contract.payer() + " · Получ.: " + contract.recipient()))));
        }
        return UiParts.div("contract-section", UiParts.div("contract-section-header",
                UiParts.text("contract-section-title", "Договоры участника (" + related.size() + ")")), body);
    }

    private Div operationsSection() {
        Div header = UiParts.div("client-operation-grid contracts-header",
                UiParts.text("", "Референс"), UiParts.text("", "Дата"),
                UiParts.text("", "Плательщик → Получатель"), UiParts.text("", "Роль"),
                UiParts.text("", "Сумма"), UiParts.text("", "Договор"),
                UiParts.text("", "Состояние"));
        Div table = UiParts.div("client-operations-table",
                UiParts.div("client-operation-viewport", header, operationRows), pagination);
        showOperationPage(0);
        return UiParts.div("contract-section", UiParts.div("contract-section-header",
                UiParts.text("contract-section-title", "Операции с участием клиента ("
                        + linkedOperations.size() + ")")), table);
    }

    private void showOperationPage(int requested) {
        AppPagination.Window window = AppPagination.window(linkedOperations.size(), requested);
        operationPage = window.page();
        operationRows.removeAll();
        if (linkedOperations.isEmpty()) operationRows.add(UiParts.text("contract-empty",
                "Операций с участием клиента пока нет."));
        for (DemoClientService.RelatedOperation item : linkedOperations.subList(
                linkedOperations.isEmpty() ? 0 : window.start() - 1, window.end()))
            operationRows.add(operationRow(item));
        pagination.update(linkedOperations.size(), operationPage);
    }

    private Div operationRow(DemoClientService.RelatedOperation item) {
        DemoDataService.Operation operation = item.operation();
        RouterLink link = OperationNavigation.link(operation.id(), operation.id(),
                "client/" + clientId);
        link.setClassName("registry-reference");
        DemoOperationState.Match match = state.match(operation.id());
        RouterLink contract = match == null ? null : new RouterLink(match.contractId(),
                ContractDetailView.class, new RouteParameters("id", match.contractId()));
        if (contract != null) {
            contract.setClassName("registry-contract-link");
            contract.setQueryParameters(QueryParameters.simple(
                    java.util.Map.of("from", "client/" + clientId)));
        }
        return UiParts.div("client-operation-grid client-operation-row", link,
                UiParts.text("contracts-date", operation.date()),
                UiParts.div("clients-operation-parties", UiParts.text("", operation.payer().name()),
                        UiParts.text("muted", "→ " + operation.recipient().name())),
                UiParts.text("client-role", String.join(", ", item.roles())),
                UiParts.text("registry-amount", operation.amountText()),
                contract == null ? UiParts.text("registry-no-contract", "Не привязан") : contract,
                UiParts.text(match == null ? "status unprocessed" : "status processed",
                        match == null ? "Не обработана" : "Обработана"));
    }

    private static String value(String text) { return text == null || text.isBlank() ? "Не указано" : text; }
}
