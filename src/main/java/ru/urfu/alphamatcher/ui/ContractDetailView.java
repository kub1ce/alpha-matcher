package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoContractService;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState;

@Route(value = "contract/:id", layout = MainLayout.class)
@PageTitle("Карточка договора - А.Выгодоприобретатель")
public class ContractDetailView extends Div implements BeforeEnterObserver {
    private final DemoDataService data;
    private final DemoContractService contracts;
    private final DemoOperationState state;
    private String returnSource;
    private String operationReturnSource;

    public ContractDetailView(DemoDataService data, DemoContractService contracts) {
        this.data = data;
        this.contracts = contracts;
        this.state = data.state();
        setClassName("contract-detail-page app-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String id = event.getRouteParameters().get("id").orElse("");
        returnSource = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("from", java.util.List.of()).stream().findFirst().orElse(null);
        operationReturnSource = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("return", java.util.List.of()).stream().findFirst().orElse("operations");
        removeAll();
        DemoDataService.Contract contract = contracts.contract(id);
        if (contract == null) {
            add(UiParts.div("contract-hero", back(), UiParts.div("contract-heading",
                    UiParts.text("registry-title", "Договор не найден"),
                    UiParts.text("muted", "Договор «" + id + "» не найден."))));
            return;
        }
        Div heading = UiParts.div("contract-heading", UiParts.div("contract-title-line",
                UiParts.text("contract-title", contract.id()), UiParts.text("contract-id-chip", "ID: " + contract.id())),
                UiParts.text("contract-meta", "Дата заключения: " + contract.date()),
                UiParts.text("contract-subject-full", contract.name()));
        Div hero = UiParts.div("contract-hero", back(), heading);
        Div parties = UiParts.div("contract-section",
                UiParts.div("contract-section-header", UiParts.text("contract-section-title", "Стороны договора")),
                UiParts.div("contract-party-grid",
                        partyCard("ПЛАТЕЛЬЩИК", data.client(contract.payerId(), state), contract.id()),
                        partyCard("ПОЛУЧАТЕЛЬ", data.client(contract.recipientId(), state), contract.id()),
                        partyCard("ВЫГОДОПРИОБРЕТАТЕЛЬ", contract.beneficiary(), contract.id())));
        var linked = contracts.linkedOperations(contract.id(), state);
        Div body = UiParts.div("contract-linked-body");
        if (linked.isEmpty()) body.add(UiParts.text("contract-empty", "К договору пока не привязаны расчётные операции."));
        else {
            body.add(UiParts.div("contract-operation-grid contract-operation-header",
                    UiParts.text("", "Референс"), UiParts.text("", "Дата"),
                    UiParts.text("", "Плательщик"), UiParts.text("", "Сумма"),
                    UiParts.text("", "Способ привязки"), UiParts.text("", "Состояние"),
                    UiParts.text("", "Переход")));
            for (var operation : linked) body.add(operationRow(operation, contract.id()));
        }
        Div operations = UiParts.div("contract-section contract-operations",
                UiParts.div("contract-section-header", UiParts.text("contract-section-title", "Связанные расчётные операции"),
                        UiParts.text("contract-linked-count", "Привязано операций: " + linked.size())), body);
        add(hero, parties, operations);
    }

    private RouterLink back() {
        boolean fromClient = returnSource != null && returnSource.matches("client/CL-[0-9]{3}");
        boolean fromOperation = returnSource != null && returnSource.matches("operation/PAY-2026-[0-9]{6}")
                && state.operation(returnSource.substring("operation/".length())) != null;
        RouterLink link = fromClient
                ? new RouterLink("", ClientDetailView.class,
                        new RouteParameters("id", returnSource.substring("client/".length())))
                : fromOperation
                        ? OperationNavigation.link("", returnSource.substring("operation/".length()), operationReturnSource)
                        : new RouterLink("", ContractsView.class);
        link.add(VaadinIcon.ARROW_LEFT.create());
        link.setClassName("detail-back contract-back");
        link.getElement().setAttribute("aria-label", fromClient ? "Вернуться к карточке клиента"
                : fromOperation
                        ? "Вернуться к карточке операции" : "Вернуться к реестру договоров");
        return link;
    }

    private Div partyCard(String label, DemoDataService.Party party, String contractId) {
        if (party == null) return UiParts.div("contract-party-card",
                UiParts.text("contract-party-label", label),
                UiParts.text("contracts-absent", "Не указан в договоре"));
        Div top = UiParts.div("contract-party-top", UiParts.text("contract-party-label", label));
        if (party.bankClient()) top.add(UiParts.text("contract-client-chip", "Клиент банка"));
        Div card = UiParts.div("contract-party-card", top,
                "ВЫГОДОПРИОБРЕТАТЕЛЬ".equals(label) ? UiParts.beneficiaryBadge(party.name())
                        : UiParts.text("contract-party-name", party.name()),
                UiParts.text("contract-party-type", party.type()),
                UiParts.div("contract-party-inn", UiParts.text("", "ИНН: "),
                        UiParts.text("contract-party-inn-value", party.inn().isBlank() ? "Не указан" : party.inn())));
        if (!party.id().isBlank()) {
            RouterLink client = ClientNavigation.link("Открыть карточку участника (" + party.id() + ") ↗",
                    party.id(), "contract/" + contractId);
            client.setClassName("contract-client-link");
            card.add(client);
        }
        return card;
    }

    private Div operationRow(DemoDataService.Operation operation, String contractId) {
        DemoOperationState.Match match = state.match(operation.id());
        RouterLink id = OperationNavigation.link(operation.id(), operation.id(), "contract/" + contractId);
        id.setClassName("registry-reference");
        RouterLink arrow = OperationNavigation.link("", operation.id(), "contract/" + contractId);
        arrow.add(VaadinIcon.ARROW_RIGHT.create());
        arrow.setClassName("contract-row-arrow");
        arrow.getElement().setAttribute("aria-label", "Открыть операцию " + operation.id());
        return UiParts.div("contract-operation-grid contract-operation-row", id,
                UiParts.text("contracts-date", operation.date()),
                UiParts.text("contracts-party-name", operation.payer().name()),
                UiParts.text("registry-amount", operation.amountText()),
                UiParts.text("contract-method" + (match != null && "Вручную".equals(match.method()) ? " manual" : ""),
                        match == null ? "Не указан" : match.method()),
                UiParts.text("status processed", "Обработана"), arrow);
    }
}
