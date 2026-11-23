package ru.urfu.alphamatcher.ui;

import java.util.List;
import java.util.Map;
import java.time.format.DateTimeFormatter;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState;
import ru.urfu.alphamatcher.demo.DemoPermissions;
import ru.urfu.alphamatcher.demo.DemoSession;
import ru.urfu.alphamatcher.demo.DemoPermissions.Action;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Route(value = "operation/:id", layout = MainLayout.class)
@PageTitle("Карточка операции - А.Выгодоприобретатель")
public class OperationDetailView extends Div implements BeforeEnterObserver {
    private final DemoDataService data;
    private final DemoOperationState state;
    private final boolean demoMode;
    private DemoDataService.Operation operation;
    private String returnSource;
    private long analysisGeneration;
    private Thread analysisThread;

    public OperationDetailView(DemoDataService data, Environment environment) {
        this.data = data;
        state = data.state();
        DemoStateUpdates.bind(this, state, this::refreshPermissions);
        addDetachListener(event -> cancelAnalysis(event.getUI()));
        demoMode = environment.acceptsProfiles(Profiles.of("demo", "jdbc-demo"));
        setClassName("operation-detail-page app-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        getUI().ifPresent(this::cancelAnalysis);
        operation = state.operation(event.getRouteParameters().get("id").orElse(""));
        if (operation == null) { event.rerouteTo(NotFoundView.class); return; }
        returnSource = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("from", List.of()).stream().findFirst().orElse(null);
        render();
    }

    void refreshPermissions() { if (operation != null) render(); }

    private void render() {
        removeAll();
        getUI().ifPresent(ui -> ui.getChildren().filter(MainLayout.class::isInstance)
                .map(MainLayout.class::cast).forEach(MainLayout::refreshDemoCounts));
        DemoOperationState.Match match = state.match(operation.id());
        RouterLink back = OperationNavigation.back(returnSource);
        back.add(VaadinIcon.ARROW_LEFT.create());
        back.setClassName("detail-back");
        back.getElement().setAttribute("aria-label", "Вернуться на предыдущую страницу приложения");
        Div title = UiParts.div("detail-heading-copy", UiParts.text("detail-reference", operation.id()),
                UiParts.div("detail-badges",
                        UiParts.text(match == null ? "status unprocessed" : "status processed",
                                match == null ? "Не обработана" : "Обработана"),
                        UiParts.text("detail-method", match == null ? "Нет привязки" : match.method())),
                UiParts.div("detail-meta", UiParts.text("", operation.date()),
                        UiParts.text("detail-dot", "·"), UiParts.text("detail-sum", operation.amountText())));
        Div actions = UiParts.div("detail-actions");
        if (match == null) {
            actions.add(UiParts.iconButton("detail-action app-action app-action--warning", "Перейти к ручному разбору",
                    VaadinIcon.EXCLAMATION_CIRCLE_O, false,
                    () -> getUI().ifPresent(ui -> ui.navigate(ManualReviewDetailView.class,
                            new RouteParameters("id", operation.id())))));
        } else {
            var change = UiParts.button("detail-action app-action app-action--secondary", "Изменить договор", this::changeContract);
            var unlink = UiParts.button("detail-action app-action app-action--danger", "Отвязать договор", this::unlinkContract);
            boolean allowed = DemoPermissions.allowsCurrent(Action.CHANGE_LINK);
            change.setEnabled(allowed);
            unlink.setEnabled(allowed);
            if (!allowed) {
                change.getElement().setAttribute("title", "Недостаточно полномочий");
                unlink.getElement().setAttribute("title", "Недостаточно полномочий");
            }
            actions.add(change, unlink);
            if (!allowed) actions.add(UiParts.div("permission-note",
                    UiParts.text("detail-label-strong", "Недостаточно полномочий"),
                    UiParts.text("muted", DemoPermissions.confirmedLinkDenialReason())));
        }
        Div purpose = UiParts.div("detail-purpose", UiParts.text("detail-label-strong", "Назначение платежа:"),
                UiParts.text("detail-purpose-text", operation.purpose()));
        add(UiParts.div("detail-hero", UiParts.div("detail-hero-line", back, title, actions), purpose),
                UiParts.div("detail-parties", partyCard("ПЛАТЕЛЬЩИК", operation.payer()),
                        partyCard("ПОЛУЧАТЕЛЬ", operation.recipient())), resultCard(match), historyCard());
    }

    private Div partyCard(String title, DemoDataService.Party party) {
        Div header = UiParts.div("detail-party-header", UiParts.text("", title));
        if (!party.id().isBlank() && data.client(party.id(), state) != null)
            header.add(contextLink("clients", party.id(), "Карточка участника (" + party.id() + ") ↗"));
        return UiParts.div("detail-party", header,
                UiParts.div("detail-party-name", field("Наименование / ФИО:", party.name())),
                UiParts.div("detail-field-pair", field("Тип участника:", party.type()), field("ИНН:", party.inn())),
                UiParts.div("detail-field-pair", field("Расчетный счет:", party.account()), field("БИК / Код отделения:", party.bic())),
                UiParts.div("detail-field-pair", field("PIN клиента:", party.pin()), field("ID клиента:", party.id())));
    }

    private Div field(String label, String value) {
        return UiParts.div("detail-field", UiParts.text("detail-field-label", label),
                UiParts.text("detail-field-value", value == null || value.isBlank() ? "Не указано" : value));
    }

    private Div resultCard(DemoOperationState.Match match) {
        Div result = UiParts.div("detail-result");
        result.add(UiParts.div("detail-result-header", UiParts.text("detail-result-title", "Результат сопоставления"),
                UiParts.text("", "Способ: " + (match == null ? "Нет привязки" : match.method()))));
        if (match == null) {
            Div warning = UiParts.div("detail-warning",
                    UiParts.text("detail-warning-title", "⚠  Договор в данный момент не привязан к операции"),
                    UiParts.text("", "Причина ручного разбора: " +
                            (state.manualReason(operation.id()) != null ? state.manualReason(operation.id())
                                    : operation.reason().isBlank() ? "Не указана" : operation.reason())));
            result.add(warning);
            if (candidates().isEmpty()) {
                result.add(UiParts.text("detail-result-note", "Подходящие договоры не найдены."));
            } else {
                String label = operation.candidates().isEmpty() && state.previousContract(operation.id()) != null
                        ? "Ранее привязанный договор:" : "Найденные кандидаты:";
                Div candidates = UiParts.div("detail-candidates", UiParts.text("detail-label-strong", label));
                for (String id : candidates()) candidates.add(contextLink("contracts", id, id + " ↗"));
                result.add(candidates);
                if (candidates().size() > 1)
                    result.add(UiParts.text("detail-result-note", "Требуется выбор сотрудника: найденный кандидат не означает подтвержденную привязку."));
            }
        } else {
            DemoDataService.Contract contract = data.contract(match.contractId());
            Div contractInfo = UiParts.div("detail-linked-info",
                    UiParts.div("detail-contract-line", UiParts.text("", "Привязанный договор:"),
                            contextLink("contracts", match.contractId(), match.contractId() + " ↗"),
                            UiParts.text("detail-contract-date", "Дата договора: " + (contract == null ? "Не указано" : contract.date()))),
                    UiParts.text("", contract == null ? "Название договора не указано" : contract.name()));
            if (contract != null) contractInfo.add(UiParts.div("detail-contract-parties",
                    field("Плательщик:", contract.payer()), field("Получатель:", contract.recipient()),
                    beneficiaryField(contract.beneficiary())));
            result.add(contractInfo);
            if ("Автоматически".equals(match.method()) && !match.automaticReason().isBlank())
                result.add(UiParts.div("detail-decision",
                        field("Основание автоматической привязки", match.automaticReason())));
            if ("Вручную".equals(match.method())) result.add(UiParts.div("detail-decision",
                    field("Обоснование ручной привязки", match.justification()),
                    field("Сотрудник", match.actor()),
                    field("Дата решения", match.decisionAt() == null ? "" : match.decisionAt().format(
                            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))),
                    field("Первоначальная причина разбора", match.reviewReason())));
        }
        result.add(UiParts.text("muted", "Статус операции в источнике: " + data.operationStatus(operation.id())),
                EngineDecisionDetails.render(state.analysis(operation.id()), data));
        var analyze = UiParts.button("app-action app-action--secondary", "Выполнить полный анализ", () -> analyze(false));
        analyze.setEnabled(analysisThread == null);
        result.add(analyze);
        if (data.hasMatchingSource()) {
            var repeat = UiParts.button("app-action app-action--secondary", "Повторный анализ", () -> analyze(true));
            repeat.setEnabled(analysisThread == null);
            result.add(repeat);
            if (state.repeatedAnalysis(operation.id()))
                result.add(UiParts.text("muted", "Повторный анализ выполнен для пересмотра. Исходный статус и текущая привязка сохранены."));
        }
        return result;
    }

    private void analyze(boolean repeat) {
        if (analysisThread != null) return;
        var ui = UI.getCurrent();
        long generation = ++analysisGeneration;
        var shown = operation;
        String id = shown.id();
        ui.setPollInterval(1000);
        analysisThread = Thread.ofVirtual().name("operation-analysis").start(() -> {
            ru.urfu.alphamatcher.experiment.MatchingModel.Decision decision = null;
            RuntimeException failure = null;
            try { decision = repeat ? data.reanalyze(shown) : data.analyze(shown); }
            catch (RuntimeException error) { failure = error; }
            var result = decision;
            var error = failure;
            try {
                ui.access(() -> {
                    if (generation != analysisGeneration || !isAttached() || !operation.id().equals(id)) return;
                    analysisThread = null;
                    ui.setPollInterval(-1);
                    if (error != null) AppNotifications.warning("Анализ недоступен", error.getMessage());
                    else try {
                        data.requireCurrentOperation(shown);
                        state.analysis(id, result, repeat);
                    } catch (IllegalStateException changed) {
                        AppNotifications.warning("Анализ устарел", changed.getMessage());
                    }
                    render();
                });
            } catch (UIDetachedException ignored) { }
        });
        render();
    }

    private void cancelAnalysis(UI ui) {
        if (analysisThread == null) return;
        analysisGeneration++;
        analysisThread.interrupt();
        analysisThread = null;
        ui.setPollInterval(-1);
    }

    private Div historyCard() {
        Div history = UiParts.div("detail-result",
                UiParts.text("detail-result-title", "История действий"));
        List<DemoOperationState.AuditEvent> events = state.auditEventsForOperation(operation.id()).stream()
                .sorted(java.util.Comparator.comparing(DemoOperationState.AuditEvent::time).reversed())
                .toList();
        if (events.isEmpty()) {
            history.add(UiParts.text("detail-history-empty muted", "Действий по этой операции пока нет."));
            return history;
        }
        for (DemoOperationState.AuditEvent event : events) {
            Div entry = UiParts.div("detail-decision",
                    field("Дата и время", event.time().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))),
                    field("Действие", event.action()), field("Исполнитель", event.actor()));
            if (event.runId() != null && !event.runId().isBlank()) entry.add(field("Запуск", event.runId()));
            if (event.automaticReason() != null && !event.automaticReason().isBlank())
                entry.add(field("Основание автоматической привязки", event.automaticReason()));
            if (event.justification() != null && !event.justification().isBlank())
                entry.add(field("Обоснование сотрудника", event.justification()));
            if (event.reviewReason() != null && !event.reviewReason().isBlank())
                entry.add(field("Причина ручного разбора", event.reviewReason()));
            history.add(entry);
        }
        return history;
    }

    private Div beneficiaryField(DemoDataService.Party beneficiary) {
        if (beneficiary == null) return field("Выгодоприобретатель:", "Не указан");
        Div field = UiParts.div("detail-field", UiParts.text("detail-field-label", "Выгодоприобретатель:"),
                UiParts.beneficiaryBadge(beneficiary.name()),
                UiParts.text("detail-field-label", "ИНН: "
                        + (beneficiary.inn().isBlank() ? "Не указан" : beneficiary.inn())));
        if (!beneficiary.id().isBlank() && data.client(beneficiary.id(), state) != null)
            field.add(contextLink("clients", beneficiary.id(), "Карточка участника (" + beneficiary.id() + ") ↗"));
        return field;
    }

    private RouterLink contextLink(String key, String id, String caption) {
        RouterLink link = "contracts".equals(key)
                ? new RouterLink(caption, ContractDetailView.class, new RouteParameters("id", id))
                : ClientNavigation.link(caption, id, "operation/" + operation.id());
        if ("contracts".equals(key)) link.setQueryParameters(
                com.vaadin.flow.router.QueryParameters.simple(Map.of("from", "operation/" + operation.id(),
                        "return", returnSource == null ? "operations" : returnSource)));
        link.setClassName("detail-object-link");
        return link;
    }

    private List<String> candidates() {
        var decision = state.analysis(operation.id());
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(decision == null ? java.util.List.of()
                : decision.candidateIds().stream().map(data::contractReference).toList());
        String previous = state.previousContract(operation.id());
        if (previous != null) ids.add(previous);
        return List.copyOf(ids);
    }

    private void changeContract() {
        if (!demoWritable()) return;
        if (!allowed(Action.CHANGE_LINK)) return;
        DemoOperationState.Match expected = state.match(operation.id());
        if (expected == null) {
            render();
            AppNotifications.warning("Подтверждение устарело", "Привязка операции изменилась. Проверьте текущее состояние.");
            return;
        }
        List<String> ids = state.contractsForLinkChange(operation.id(), data.contracts());
        if (ids.isEmpty()) {
            AppNotifications.warning("Нет подходящих договоров", "Для смены связи проверьте стороны операции и договора.");
            return;
        }
        contractDialog("Изменить договор", ids, expected);
    }
    private void contractDialog(String title, List<String> ids, DemoOperationState.Match expected) {
        var shown = operation;
        String confirmedOperation = operation.id();
        long confirmedEmployee = DemoSession.employeeGeneration();
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        Select<String> select = new Select<>();
        select.setLabel("Договор");
        select.setItems(ids);
        select.setValue(ids.getFirst());
        select.setItemLabelGenerator(id -> {
            var contract = data.contract(id);
            return id + (contract == null ? "" : " - " + contract.name());
        });
        TextArea justification = new TextArea("Обоснование ручной привязки");
        justification.setWidthFull();
        justification.setPlaceholder("Укажите, почему выбран этот договор");
        dialog.add(UiParts.div("detail-dialog-text", UiParts.text("", "Операция " + operation.id()),
                UiParts.text("", "Текущий договор: " + expected.contractId()),
                select, justification));
        dialog.getFooter().add(UiParts.button("app-action app-action--secondary", "Отмена", dialog::close),
                UiParts.button("app-action app-action--primary", "Подтвердить выбор", () -> {
                    if (!dialog.isOpened()) return;
                    if (!DemoOperationState.hasValidManualJustification(justification.getValue())) {
                        justification.setInvalid(true);
                        justification.setErrorMessage("Укажите обоснование не короче 5 символов");
                        return;
                    }
                    try {
                        requireConfirmationContext(confirmedOperation, confirmedEmployee);
                        data.confirmAtomically(state, () -> {
                            data.requireCurrentOperation(shown);
                            state.changeLinkIfUnchanged(confirmedOperation, expected, select.getValue(), data.contracts(),
                                    justification.getValue());
                            return null;
                        });
                        operation = state.operation(confirmedOperation);
                        dialog.close(); render();
                        AppNotifications.success("Договор привязан", operation.id() + " · " + select.getValue());
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        dialog.close(); render();
                        AppNotifications.error("Не удалось изменить договор", ex.getMessage());
                    }
                }));
        UiParts.openDialog(this, dialog);
    }
    private void unlinkContract() {
        if (!demoWritable()) return;
        if (!allowed(Action.UNLINK)) return;
        DemoOperationState.Match expected = state.match(operation.id());
        if (expected == null) {
            render();
            AppNotifications.warning("Подтверждение устарело", "Привязка операции изменилась. Проверьте текущее состояние.");
            return;
        }
        String id = expected.contractId();
        var shown = operation;
        String confirmedOperation = operation.id();
        long confirmedEmployee = DemoSession.employeeGeneration();
        confirmation("Отвязать договор", "Убрать привязку " + id + " от операции " + operation.id() + "?",
                "Отвязать", () -> {
                    try {
                        requireConfirmationContext(confirmedOperation, confirmedEmployee);
                        data.confirmAtomically(state, () -> {
                            data.requireCurrentOperation(shown);
                            state.unlinkIfUnchanged(confirmedOperation, expected);
                            return null;
                        });
                        operation = state.operation(confirmedOperation);
                        render();
                        AppNotifications.info("Договор отвязан", operation.id() + " · " + id);
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        render();
                        AppNotifications.error("Не удалось отвязать договор", ex.getMessage());
                    }
                });
    }
    private void requireConfirmationContext(String confirmedOperation, long confirmedEmployee) {
        DemoSession.requireSameEmployeeGeneration(confirmedEmployee);
        if (operation == null || !confirmedOperation.equals(operation.id()))
            throw new IllegalStateException("Подтверждение устарело. Открыта другая операция.");
    }
    private void confirmation(String title, String message, String action, Runnable confirmed) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.add(UiParts.text("detail-dialog-text", message));
        dialog.getFooter().add(UiParts.button("app-action app-action--secondary", "Отмена", dialog::close),
                UiParts.button("app-action app-action--primary", action, () -> {
                    if (!dialog.isOpened()) return;
                    dialog.close();
                    confirmed.run();
                }));
        UiParts.openDialog(this, dialog);
    }
    private boolean demoWritable() {
        if (demoMode) return true;
        AppNotifications.error("Действие недоступно", "Изменение данных в текущем режиме недоступно.");
        return false;
    }
    private boolean allowed(Action action) {
        if (DemoPermissions.allowsCurrent(action)) return true;
        AppNotifications.error("Недостаточно полномочий", "Действие доступно сотруднику, принимающему решения.");
        return false;
    }
}
