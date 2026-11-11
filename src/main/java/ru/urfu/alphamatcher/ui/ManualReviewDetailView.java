package ru.urfu.alphamatcher.ui;

import java.util.Map;
import java.util.List;
import java.util.stream.Stream;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoManualReviewService;
import ru.urfu.alphamatcher.demo.DemoOperationState;
import ru.urfu.alphamatcher.demo.DemoPermissions;

@Route(value = "manual/:id", layout = MainLayout.class)
@PageTitle("Ручной разбор операции - А.Выгодоприобретатель")
public class ManualReviewDetailView extends Div implements BeforeEnterObserver {
    private final DemoDataService data;
    private final DemoManualReviewService review;
    private final DemoOperationState state;
    private final boolean demoMode;
    private DemoDataService.Operation operation;
    private String selectedId;
    private final Div selected = UiParts.div("manual-selection");
    private final TextArea justification = new TextArea("Обоснование решения");
    private Button submit;

    public ManualReviewDetailView(DemoDataService data, DemoManualReviewService review, Environment environment) {
        this.data = data;
        this.review = review;
        this.state = data.state();
        DemoStateUpdates.bind(this, state, this::refreshPermissions);
        this.demoMode = environment.acceptsProfiles(Profiles.of("demo"));
        setClassName("manual-detail-page app-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        operation = state.operation(event.getRouteParameters().get("id").orElse(""));
        selectedId = null;
        render();
    }

    void refreshPermissions() {
        selectedId = null;
        render();
    }

    private void render() {
        removeAll();
        RouterLink back = new RouterLink("", ManualReviewView.class);
        back.add(VaadinIcon.ARROW_LEFT.create());
        back.setClassName("detail-back");
        back.getElement().setAttribute("aria-label", "Вернуться к очереди ручного разбора");
        if (operation == null) {
            add(UiParts.div("manual-review-card", back, UiParts.text("registry-title", "Операция не найдена"),
                    UiParts.text("muted", "Проверьте референс или вернитесь к очереди.")));
            return;
        }
        DemoOperationState.Match match = state.match(operation.id());
        Div header = UiParts.div("manual-review-card manual-review-hero",
                UiParts.div("manual-review-heading", back,
                        UiParts.div("", UiParts.text("registry-title", "Ручной разбор · " + operation.id()),
                                UiParts.text("registry-count", operation.date() + " · " + operation.amountText()))),
                UiParts.div("manual-review-summary",
                        summary("Плательщик", operation.payer().name(), operation.payer().inn(), operation.payer().id()),
                        summary("Получатель", operation.recipient().name(), operation.recipient().inn(), operation.recipient().id())),
                UiParts.div("manual-review-purpose", UiParts.text("detail-label-strong", "Назначение платежа"),
                        UiParts.text("", operation.purpose())),
                UiParts.div("manual-review-reason", UiParts.text("manual-reason-chip", review.reason(operation, state)
                        .isBlank() ? "Нет причины разбора" : review.reason(operation, state)),
                        UiParts.text("", review.explanation(operation, state))));
        add(header);
        if (match != null) {
            RouterLink contract = new RouterLink(match.contractId(), ContractDetailView.class,
                    new RouteParameters("id", match.contractId()));
            contract.setClassName("detail-object-link");
            RouterLink card = OperationNavigation.link("Открыть карточку операции", operation.id(),
                    "manual/" + operation.id());
            card.setClassName("app-action app-action--secondary");
            add(UiParts.div("manual-review-card manual-already-linked",
                    UiParts.text("registry-title", "Операция уже обработана"),
                    UiParts.div("", UiParts.text("", "Подтверждённый договор: "), contract),
                    UiParts.text("muted", "Для изменения или отвязки используйте действия в карточке операции."), card));
            return;
        }
        var suggestions = review.candidates(operation, state);
        boolean canChoose = DemoPermissions.allowsCurrent(DemoPermissions.Action.MANUAL_COMPLEX)
                || suggestions.stream().anyMatch(contract -> DemoPermissions.allowsCurrent(
                        DemoPermissions.manualAction(operation, contract, state)));
        Div candidates = UiParts.div("manual-review-card",
                UiParts.text("contract-section-title", "Предложенные договоры"),
                UiParts.text("muted", canChoose
                        ? "Кандидат не является подтверждённой привязкой. Выберите договор явно."
                        : "Кандидат не является подтверждённой привязкой. Договоры доступны для просмотра."));
        Div accessNote = UiParts.div("manual-warning manual-access-note");
        candidates.add(accessNote);
        refreshAccessNote(accessNote, suggestions);
        if (suggestions.isEmpty()) {
            RouterLink operationCard = OperationNavigation.link("Открыть карточку операции", operation.id(),
                    "manual/" + operation.id());
            operationCard.setClassName("detail-object-link");
            candidates.add(UiParts.div("contract-empty",
                    UiParts.text("", "Подходящих договоров в предложениях нет. Найдите договор в реестре ниже или "),
                    operationCard));
        }
        else for (var contract : suggestions) candidates.add(candidateCard(contract, true));
        TextField contractSearch = new TextField();
        contractSearch.setPlaceholder("Найти существующий договор по номеру или сторонам...");
        contractSearch.setAriaLabel("Найти договор для ручной привязки");
        contractSearch.setWidthFull();
        contractSearch.setValueChangeMode(ValueChangeMode.EAGER);
        Div found = UiParts.div("manual-search-results");
        contractSearch.addValueChangeListener(e -> {
            found.removeAll();
            if (e.getValue().isBlank()) { refreshAccessNote(accessNote, suggestions); return; }
            var results = review.findContracts(e.getValue()).stream()
                    .filter(c -> !suggestions.contains(c)).toList();
            refreshAccessNote(accessNote, Stream.concat(suggestions.stream(), results.stream()).toList());
            if (results.isEmpty()) found.add(UiParts.text("contract-empty", "По запросу подходящих договоров не найдено"));
            else for (var contract : results) found.add(candidateCard(contract, false));
        });
        candidates.add(UiParts.div("manual-search-contracts",
                UiParts.text("detail-label-strong", "Поиск по реестру договоров"), contractSearch, found));
        justification.setWidthFull();
        justification.setPlaceholder("Кратко укажите, почему выбран этот договор");
        justification.setVisible(true);
        submit = UiParts.button("app-action app-action--primary", "Подтвердить ручную привязку", this::showConfirmation);
        submit.setEnabled(false);
        Button cancel = UiParts.button("app-action app-action--secondary", "Отмена",
                () -> UI.getCurrent().navigate(ManualReviewView.class));
        selected.removeAll();
        selected.setClassName("manual-selection is-empty");
        selected.add(UiParts.text("muted", "Договор не выбран"));
        add(candidates, UiParts.div("manual-review-card manual-review-decision",
                UiParts.text("contract-section-title", "Решение сотрудника"), selected, justification,
                UiParts.div("manual-decision-actions", submit, cancel)));
    }

    private Div summary(String label, String name, String inn, String id) {
        Div box = UiParts.div("manual-summary-party", UiParts.text("contract-party-label", label),
                UiParts.text("contract-party-name", name),
                UiParts.text("contract-party-type", "ИНН: " + (inn.isBlank() ? "Не указан" : inn)));
        if (!id.isBlank()) {
            RouterLink client = new RouterLink("Карточка клиента (" + id + ") ↗", ContextPlaceholderView.class,
                    new RouteParameters(Map.of("key", "clients", "id", id)));
            client.setClassName("contract-client-link");
            box.add(client);
        }
        return box;
    }

    private Div candidateCard(DemoDataService.Contract contract, boolean proposed) {
        RouterLink link = new RouterLink(contract.id() + " ↗", ContractDetailView.class,
                new RouteParameters("id", contract.id()));
        link.setClassName("registry-reference");
        var beneficiary = contract.beneficiary();
        boolean partiesMatch = contract.payer().equals(operation.payer().name())
                && contract.recipient().equals(operation.recipient().name());
        Button choose = UiParts.button("app-action app-action--secondary manual-select", "Выбрать", () -> select(contract));
        boolean allowed = DemoPermissions.allowsCurrent(DemoPermissions.manualAction(operation, contract, state));
        if (!allowed) {
            choose.setEnabled(false);
            choose.getElement().setAttribute("title", "Недостаточно полномочий");
        }
        Div choice = UiParts.div("manual-candidate-action", choose);
        if (!allowed) choice.getElement().setAttribute("title", "Недостаточно полномочий");
        Div card = UiParts.div("manual-candidate-card", UiParts.div("manual-candidate-top", link, choice),
                UiParts.text("contract-party-name", contract.name()),
                UiParts.text("", "Плательщик: " + contract.payer() + " · Получатель: " + contract.recipient()),
                UiParts.div("manual-beneficiary", UiParts.text("muted", "Выгодоприобретатель:"),
                        beneficiary == null ? UiParts.text("contracts-absent", "Не указан")
                                : UiParts.div("", UiParts.beneficiaryBadge(beneficiary.name()),
                                        UiParts.text("contracts-inn", "ИНН: " + (beneficiary.inn().isBlank()
                                                ? "Не указан" : beneficiary.inn())))),
                UiParts.text(partiesMatch ? "manual-relevance" : "manual-candidate-conflict",
                        proposed ? (partiesMatch ? "Предложен по сторонам или назначению платежа."
                                : "Кандидат из исходного списка, но стороны не совпадают - требуется обоснование.")
                                : "Найден в реестре по запросу. Проверьте основание привязки."));
        card.getElement().setAttribute("data-contract-id", contract.id());
        if (contract.id().equals(selectedId)) card.addClassName("selected");
        return card;
    }

    private void refreshAccessNote(Div note, List<DemoDataService.Contract> contracts) {
        if (contracts.isEmpty() || contracts.stream().anyMatch(contract -> DemoPermissions.allowsCurrent(
                DemoPermissions.manualAction(operation, contract, state)))) {
            note.setVisible(false);
            return;
        }
        boolean ambiguous = operation.candidates().size() > 1
                || "Несколько подходящих договоров".equals(operation.reason());
        note.setText("Выбор договора недоступен. " + (ambiguous
                ? "Найдено несколько подходящих договоров. Решение может принять только сотрудник, принимающий решения"
                : DemoPermissions.manualDenialReason(operation, contracts.getFirst(), state)));
        note.setVisible(true);
    }

    private void select(DemoDataService.Contract contract) {
        if (!DemoPermissions.allowsCurrent(DemoPermissions.manualAction(operation, contract, state))) return;
        selectedId = contract.id();
        submit.setEnabled(true);
        selected.removeAll();
        selected.setClassName("manual-selection");
        selected.add(UiParts.text("detail-label-strong", "Выбран договор: " + contract.id()),
                UiParts.text("", contract.name()));
        var warnings = review.warnings(operation, contract, state);
        for (String warning : warnings) selected.add(UiParts.text("manual-warning", warning));
        justification.setVisible(true);
        getElement().executeJs("this.querySelectorAll('.manual-candidate-card').forEach(card => "
                + "card.classList.toggle('selected', card.dataset.contractId === $0))", selectedId);
    }

    private void showConfirmation() {
        if (!demoMode) { AppNotifications.error("Действие недоступно", "Привязка в текущем режиме недоступна."); return; }
        if (selectedId == null) { AppNotifications.error("Выберите договор", "Сначала укажите договор для операции."); return; }
        DemoDataService.Contract contract = data.contract(selectedId);
        if (contract == null) { AppNotifications.error("Договор не найден", selectedId); return; }
        if (!DemoPermissions.allowsCurrent(DemoPermissions.manualAction(operation, contract, state))) {
            AppNotifications.error("Недостаточно полномочий", "Для этого решения нужен сотрудник, принимающий решения.");
            return;
        }
        var warnings = review.warnings(operation, contract, state);
        if (!DemoOperationState.hasValidManualJustification(justification.getValue())) {
            justification.setInvalid(true);
            justification.setErrorMessage("Укажите обоснование не короче 5 символов");
            AppNotifications.error("Требуется обоснование", "Проверьте предупреждения перед привязкой.");
            return;
        }
        justification.setInvalid(false);
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Подтвердить ручную привязку");
        Div content = UiParts.div("manual-confirm-summary",
                UiParts.text("", "Операция: " + operation.id()),
                UiParts.text("", "Договор: " + contract.id() + " - " + contract.name()),
                UiParts.text("", "Способ: Вручную"));
        for (String warning : warnings) content.add(UiParts.text("manual-warning", warning));
        content.add(UiParts.text("muted", "Обоснование: " + justification.getValue().trim()));
        dialog.add(content);
        dialog.getFooter().add(UiParts.button("app-action app-action--secondary", "Отмена", dialog::close),
                UiParts.button("app-action app-action--primary", "Подтвердить ручную привязку", () -> {
                    try {
                        review.confirm(operation.id(), contract.id(), justification.getValue(), true, state);
                        dialog.close();
                        AppNotifications.success("Ручная привязка выполнена", operation.id() + " · " + contract.id());
                        UI.getCurrent().navigate(ManualReviewView.class);
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        dialog.close();
                        AppNotifications.error("Не удалось привязать договор", ex.getMessage());
                        render();
                    }
                }));
        dialog.open();
    }
}
