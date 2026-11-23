package ru.urfu.alphamatcher.ui;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoMatchingDraft;
import ru.urfu.alphamatcher.demo.DemoMatchingService;
import ru.urfu.alphamatcher.demo.DemoMatchingService.Preview;
import ru.urfu.alphamatcher.demo.DemoMatchingService.Proposal;
import ru.urfu.alphamatcher.demo.DemoMatchingService.Status;
import ru.urfu.alphamatcher.demo.DemoOperationState;
import ru.urfu.alphamatcher.demo.DemoSession;

@Route(value = "section/matching", layout = MainLayout.class)
@PageTitle("Автопривязка - А.Выгодоприобретатель")
public class MatchingView extends Div implements BeforeEnterObserver {
    private final DemoDataService data;
    private final DemoMatchingService matching;
    private final DemoOperationState state;
    private final DemoMatchingDraft draft;
    private final DatePicker from = new DatePicker("Начало периода");
    private final DatePicker to = new DatePicker("Окончание периода");
    private final Checkbox includeProcessed = new Checkbox("Показать уже обработанные");
    private final Checkbox includeManual = new Checkbox("Показывать операции на ручном разборе");
    private final Checkbox selectAll = new Checkbox();
    private int page;
    private final AppPagination pagination;
    private final Div rows = UiParts.div("matching-rows");
    private final Div results = UiParts.div("matching-results");
    private final Span selectionLabel = UiParts.text("matching-selection-label", "Выбрано: 0");
    private final Span selectionHint = UiParts.text("matching-selection-hint muted", "");
    private final Span initiatorLabel = UiParts.text("matching-initiator", "");
    private final Button applyButton;
    private final Button manualButton;
    private final Button clearButton;
    private Button selectResultsButton;
    private final Set<String> selected;
    private Preview preview;
    private boolean rendering;
    private boolean busy;
    private Thread analysisThread;
    private long analysisGeneration;
    private long selectionEmployee;
    private final Span analysisStatus = UiParts.text("muted", "");
    private final Button cancelAnalysis = UiParts.button("app-action app-action--secondary", "Отменить поиск", this::cancelAnalysis);

    private String repeatSource;
    private String selectionNotice;

    public MatchingView(DemoDataService data, DemoMatchingService matching) {
        this.data = data;
        this.matching = matching;
        this.state = data.state();
        DemoStateUpdates.bind(this, state, this::refreshBusinessState);
        this.draft = DemoSession.matchingDraft();
        this.pagination = new AppPagination(target -> {
            page = target;
            draft.page(page);
            renderResults();
        });
        this.selected = draft.selected();
        this.selectionEmployee = DemoSession.employeeGeneration();
        this.preview = draft.preview();
        this.page = draft.page();
        setClassName("matching-page app-page");
        from.setI18n(new DatePicker.DatePickerI18n().setDateFormat("dd.MM.yyyy"));
        to.setI18n(new DatePicker.DatePickerI18n().setDateFormat("dd.MM.yyyy"));
        from.setValue(draft.from());
        to.setValue(draft.to());
        from.setRequiredIndicatorVisible(true);
        to.setRequiredIndicatorVisible(true);
        includeProcessed.setValue(draft.includeProcessed());
        includeManual.setValue(draft.includeManual());
        from.addValueChangeListener(e -> dateChanged());
        to.addValueChangeListener(e -> dateChanged());
        includeProcessed.addValueChangeListener(e -> { saveParameters(); if (!rendering && preview != null) refreshPreview(); });
        includeManual.addValueChangeListener(e -> { saveParameters(); if (!rendering && preview != null) refreshPreview(); });
        Div intro = UiParts.div("matching-intro",
                UiParts.text("registry-title", "Автоматическое сопоставление"),
                UiParts.text("registry-count", "Поиск подходящих договоров и подтверждение привязок"));
        Div fields = UiParts.div("matching-parameters app-filter-controls", from, to,
                includeProcessed, includeManual);
        refreshUser();
        Div controls = UiParts.div("matching-controls",
                initiatorLabel,
                UiParts.iconButton("app-action app-action--primary", "Найти привязки", VaadinIcon.SEARCH,
                        false, this::find));
        Div setup = UiParts.div("registry-filters matching-setup", intro, fields, controls);
        selectAll.setAriaLabel("Выбрать все операции текущей страницы");
        selectAll.addValueChangeListener(e -> {
            if (rendering || preview == null) return;
            draft.selectPage(pageRows(), e.getValue());
            rendering = true;
            rows.getChildren().filter(Div.class::isInstance).map(Div.class::cast).forEach(row ->
                    row.getChildren().filter(Checkbox.class::isInstance).map(Checkbox.class::cast)
                            .forEach(box -> box.setValue(e.getValue())));
            rendering = false;
            updateSelection();
        });
        applyButton = UiParts.iconButton("app-action app-action--primary", "Привязать выбранные",
                VaadinIcon.LINK, false, () -> confirm(false));
        manualButton = UiParts.iconButton("app-action app-action--warning", "На ручной разбор",
                VaadinIcon.EXCLAMATION_CIRCLE_O, false, () -> confirm(true));
        clearButton = UiParts.button("matching-clear", "Сбросить выбор", () -> {
            rendering = true;
            selected.clear();
            rows.getChildren().filter(Div.class::isInstance).map(Div.class::cast).forEach(row ->
                    row.getChildren().filter(Checkbox.class::isInstance).map(Checkbox.class::cast)
                            .forEach(box -> box.setValue(false)));
            rendering = false;
            updateSelection();
        });
        results.add(UiParts.text("matching-waiting", "Нажмите «Найти привязки», чтобы увидеть предложения."));
        cancelAnalysis.setVisible(false);
        controls.add(analysisStatus, cancelAnalysis);
        addDetachListener(event -> cancelAnalysis());
        add(setup, results);
        if (data instanceof ru.urfu.alphamatcher.matching.JdbcDemoDataService)
            setup.add(new RouterLink("Долговременный предпросмотр", BulkPreviewView.class));
    }

    public void refreshUser() {
        initiatorLabel.setText("Инициатор нового действия: " + currentInitiator());
        long current = DemoSession.employeeGeneration();
        if (selectionEmployee == current) return;
        selectionEmployee = current;
        DemoSession.matchingDraft();
        if (analysisThread != null) cancelAnalysis();
        if (preview != null) renderResults();
    }
    private void saveParameters() { draft.parameters(from.getValue(), to.getValue(),
            includeProcessed.getValue(), includeManual.getValue()); }

    private void dateChanged() {
        saveParameters();
        if (!rendering && preview != null) renderResults();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var query = event.getLocation().getQueryParameters().getParameters();
        if (query.containsKey("repeat")) {
            String requested = query.get("repeat").getFirst();
            var source = state.batchRuns().stream().filter(run -> run.id().equals(requested))
                    .findFirst().orElse(null);
            if (source != null) {
                rendering = true;
                from.setValue(source.from());
                to.setValue(source.to());
                includeProcessed.setValue(source.includeProcessed());
                includeManual.setValue(source.includeManual());
                rendering = false;
                repeatSource = source.id();
                refreshPreview();
            } else {
                repeatSource = null;
                if (preview != null) renderResults();
            }
        }
        else {
            setDate(from, query.get("from"));
            setDate(to, query.get("to"));
            if (preview != null) {
                if (sourceChanged(preview))
                    refreshFromCurrentState();
                else renderResults();
            }
        }
    }

    private void setDate(DatePicker picker, List<String> values) {
        if (values == null || values.isEmpty() || values.getFirst().isBlank()) return;
        try { picker.setValue(LocalDate.parse(values.getFirst())); }
        catch (RuntimeException ignored) { picker.setInvalid(true); }
    }

    private void find() {
        repeatSource = null;
        refreshPreview();
    }

    private void cancelAnalysis() {
        analysisGeneration++;
        if (analysisThread != null) analysisThread.interrupt();
        analysisThread = null;
        cancelAnalysis.setVisible(false);
        analysisStatus.setText("Поиск отменён. Подтверждённые связи не изменены.");
        getUI().ifPresent(ui -> ui.setPollInterval(-1));
        updateSelection();
    }

    private void refreshPreview() {
        startPreview(from.getValue(), to.getValue(), includeProcessed.getValue(), includeManual.getValue(), false);
    }

    boolean analysisRunning() { return analysisThread != null; }

    private void startPreview(LocalDate start, LocalDate end, boolean processed, boolean manual, boolean retainSelection) {
        var ui = UI.getCurrent();
        if (start == null || end == null || start.isAfter(end)) {
            AppNotifications.warning("Проверьте период", "Укажите корректные начало и окончание периода.");
            return;
        }
        if (analysisThread != null) analysisThread.interrupt();
        long generation = ++analysisGeneration;
        String author = currentInitiator();
        analysisStatus.setText("Выполняется анализ Matching Engine...");
        cancelAnalysis.setVisible(true);
        ui.setPollInterval(1000);
        analysisThread = Thread.ofVirtual().name("matching-preview").start(() -> {
            Preview computed = null;
            RuntimeException failure = null;
            try { computed = matching.preview(state, start, end, processed, manual); }
            catch (RuntimeException e) { failure = e; }
            Preview result = computed;
            RuntimeException error = failure;
            if (!ui.isAttached()) return;
            try { ui.access(() -> {
                if (generation != analysisGeneration || !isAttached()) return;
                analysisThread = null;
                cancelAnalysis.setVisible(false);
                ui.setPollInterval(-1);
                if (error != null) {
                    analysisStatus.setText("Анализ не завершён. Решения не опубликованы.");
                    updateSelection();
                    AppNotifications.warning("Анализ недоступен", error.getMessage());
                    return;
                }
                if (sourceChanged(result)) {
                    analysisStatus.setText("Данные изменились во время анализа. Повторите поиск.");
                    updateSelection();
                    return;
                }
                if (retainSelection) refreshFromCurrentState(result);
                else { preview = result; draft.save(preview, author); page = 0; }
                analysisStatus.setText("Анализ завершён. Связи ожидают подтверждения.");
                renderResults();
            }); } catch (com.vaadin.flow.component.UIDetachedException ignored) { }
        });
        updateSelection();
    }

    private void refreshBusinessState() {
        if (preview == null || analysisThread != null) return;
        if (sourceChanged(preview))
            startPreview(preview.from(), preview.to(), preview.includeProcessed(), preview.includeManual(), true);
        else renderResults();
    }

    private void refreshFromCurrentState() { refreshPreview(); }

    private void refreshFromCurrentState(Preview refreshed) {
        Set<String> previousSelection = Set.copyOf(selected);
        int previousPage = page;
        preview = refreshed;
        draft.save(preview, currentInitiator());
        selected.addAll(previousSelection);
        draft.retainResults(preview.rows());
        page = previousPage;
        selectionNotice = "Данные изменились: предпросмотр обновлён. Выбор сохранён только для операций, которые остались в результатах.";
        renderResults();
    }

    private void renderResults() {
        results.removeAll();
        if (preview == null) return;
        if (repeatSource != null) results.add(UiParts.div("matching-queue-note",
                UiParts.text("", "Повторный поиск по запуску " + repeatSource
                        + ": создан новый предпросмотр по текущим данным. Прежние связи не изменены; выберите операции и подтвердите действие.")));
        if (selectionNotice != null) results.add(UiParts.div("matching-queue-note",
                UiParts.text("", selectionNotice)));
        if (draft.hidden()) {
            results.add(UiParts.div("matching-collapsed",
                    UiParts.text("panel-title", "Предпросмотр скрыт"),
                    UiParts.text("muted", "Период и выбор сохранены."),
                    UiParts.div("matching-action-buttons",
                            UiParts.button("app-action app-action--secondary", "Показать сохранённый предпросмотр", () -> {
                                draft.restore(); renderResults();
                            }),
                            UiParts.button("app-action app-action--primary", "Найти заново", this::find))));
            return;
        }
        boolean stale = isStale();
        Div heading = UiParts.div("matching-results-heading",
                UiParts.div("", UiParts.text("panel-title", "Предложения по привязке"),
                        UiParts.text("muted", "Период: " + preview.from().format(
                                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " - "
                                + preview.to().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")))),
                UiParts.div("matching-action-buttons",
                        UiParts.button("matching-clear", "Скрыть результаты", () -> { draft.hide(); renderResults(); }),
                        UiParts.button("matching-clear", "Найти заново", this::find)));
        if (preview.rows().isEmpty()) {
            results.add(UiParts.div("matching-summary", heading));
            results.add(UiParts.div("matching-empty", UiParts.text("panel-title", "Нет подходящих операций"),
                    UiParts.text("muted", "По выбранным условиям операции не найдены."),
                    UiParts.button("app-action app-action--secondary", "Изменить параметры", () -> from.focus())));
            return;
        }
        Div stats = UiParts.div("matching-stats",
                stat("Рассмотрено", preview.rows().size(), ""),
                stat("Можно привязать", preview.count(Status.SAFE), "safe"),
                stat("Требуют проверки", preview.count(Status.REVIEW), "review"),
                stat("Нет договора", preview.count(Status.NO_CONTRACT), ""),
                stat("Уже на ручном разборе", preview.count(Status.ALREADY_MANUAL), "review"),
                stat("Уже обработаны", preview.count(Status.SKIPPED), ""));
        results.add(UiParts.div("matching-summary", heading, stats));
        long alreadyQueued = preview.count(Status.ALREADY_MANUAL);
        if (alreadyQueued > 0) results.add(UiParts.div("matching-queue-note",
                UiParts.text("", alreadyQueued + " операций уже находятся на ручном разборе. Повторно они не добавляются; "
                        + "новые проблемные операции можно передать сотруднику даже без найденного договора.")));
        if (!state.batchRuns().isEmpty()) {
            Div history = UiParts.div("matching-recent-actions",
                    UiParts.text("matching-recent-title", "Последние подтверждённые действия"));
            for (var run : state.batchRuns().reversed().stream().limit(2).toList())
                history.add(UiParts.text("", run.id() + " · " + run.initiator() + " · привязано: "
                        + run.applied() + " · вручную: " + run.manual()));
            results.add(history);
        }
        if (stale) results.add(UiParts.div("matching-stale",
                UiParts.text("", staleReason() + " Пересчитайте предложения перед массовым действием."),
                UiParts.button("app-action app-action--warning", "Обновить предложения", this::find)));
        page = Math.min(page, Math.max(0, (preview.rows().size() - 1) / AppPagination.PAGE_SIZE));
        draft.page(page);
        rows.removeAll();
        for (Proposal proposal : pageRows()) rows.add(row(proposal));
        Div header = UiParts.div("matching-grid matching-header", selectAll,
                UiParts.text("", "Операция"), UiParts.text("", "Дата"),
                UiParts.text("", "Участники"), UiParts.text("", "Сумма"),
                UiParts.text("", "Предложенный договор"), UiParts.text("", "Основание"),
                UiParts.text("", "Состояние"));
        Div table = UiParts.div("registry-table-card matching-table",
                UiParts.div("matching-table-viewport", header, rows), pagination);
        pagination.update(preview.rows().size(), page);
        selectResultsButton = new Button("Выбрать все " + preview.rows().size() + " операций");
        selectResultsButton.setClassName("matching-clear");
        selectResultsButton.addClickListener(event -> {
            draft.selectResults(preview.rows());
            rendering = true;
            rows.getChildren().filter(Div.class::isInstance).map(Div.class::cast).forEach(row ->
                    row.getChildren().filter(Checkbox.class::isInstance).map(Checkbox.class::cast)
                            .forEach(box -> box.setValue(true)));
            rendering = false;
            updateSelection();
        });
        selectResultsButton.setEnabled(!stale && selected.size() < preview.rows().size());
        selectAll.getElement().setAttribute("title", "Выбрать только операции текущей страницы");
        selectResultsButton.getElement().setAttribute("title", "Выбрать все результаты на всех страницах");
        Div actions = UiParts.div("matching-actions",
                UiParts.div("matching-selection", selectionLabel, selectionHint),
                UiParts.div("matching-action-buttons", selectResultsButton, clearButton, applyButton, manualButton));
        results.add(table, actions);
        updateSelection();
    }

    private Div stat(String title, long value, String css) {
        return UiParts.div("matching-stat " + css, UiParts.text("matching-stat-title", title),
                UiParts.text("matching-stat-value", String.valueOf(value)));
    }

    private List<Proposal> pageRows() {
        if (preview == null) return List.of();
        int start = Math.min(page * AppPagination.PAGE_SIZE, preview.rows().size());
        return preview.rows().subList(start, Math.min(start + AppPagination.PAGE_SIZE, preview.rows().size()));
    }

    private Div row(Proposal proposal) {
        var op = state.operation(proposal.operationId());
        Checkbox box = new Checkbox();
        box.setValue(selected.contains(op.id()));
        box.setAriaLabel("Выбрать операцию " + op.id());
        box.addValueChangeListener(e -> {
            if (rendering) return;
            if (e.getValue()) selected.add(op.id()); else selected.remove(op.id());
            updateSelection();
        });
        RouterLink ref = OperationNavigation.link(op.id(), op.id(), "matching");
        ref.setClassName("registry-reference");
        Span payer = UiParts.text("matching-party", op.payer().name());
        payer.getElement().setAttribute("title", op.payer().name());
        Span recipient = UiParts.text("matching-party muted", "→ " + op.recipient().name());
        recipient.getElement().setAttribute("title", op.recipient().name());
        Div contract;
        if (proposal.candidates().size() > 1) {
            Button toggle = UiParts.button("matching-candidate-toggle", "Несколько кандидатов (" + proposal.candidates().size() + ")", () -> { });
            Div list = UiParts.div("matching-candidate-list");
            for (String id : proposal.candidates()) {
                var candidate = data.contract(id);
                if (candidate != null) list.add(UiParts.text("", id + " · " + candidate.name()
                        + " - " + (candidate.payer().equals(op.payer().name())
                        && candidate.recipient().equals(op.recipient().name()) ? "стороны совпадают" : "требуется проверка сторон")));
            }
            list.setVisible(false);
            toggle.addClickListener(e -> list.setVisible(!list.isVisible()));
            contract = UiParts.div("matching-contract", toggle, list);
        } else contract = UiParts.div("matching-contract", UiParts.text("",
                proposal.candidates().isEmpty() ? "Нет кандидата" : proposal.candidates().getFirst()));
        String reasonText = displayReason(proposal.reason());
        Span reason = UiParts.text("matching-reason", reasonText);
        reason.getElement().setAttribute("title", reasonText);
        String status = switch (proposal.status()) {
            case SAFE -> "Можно привязать";
            case REVIEW -> "Требует проверки";
            case NO_CONTRACT -> "Нет договора";
            case ALREADY_MANUAL -> "Уже на ручном разборе";
            case SKIPPED -> "Пропущена";
        };
        Div row = UiParts.div("matching-grid matching-row", box, ref,
                UiParts.text("registry-date", op.date()),
                UiParts.div("matching-parties", payer, recipient),
                UiParts.text("registry-amount", op.amountText()), contract, reason,
                UiParts.text("matching-status " + proposal.status().name().toLowerCase(), status));
        row.getElement().setAttribute("data-operation-id", op.id());
        return row;
    }

    private long[] selectedStatusCounts() {
        var counts = new long[Status.values().length];
        draft.selectedRows(preview.rows()).forEach(p -> counts[p.status().ordinal()]++);
        return counts;
    }

    private void updateSelection() {
        if (preview == null || applyButton == null || manualButton == null) return;
        var counts = selectedStatusCounts();
        long safe = counts[Status.SAFE.ordinal()];
        long toManual = safe + counts[Status.REVIEW.ordinal()] + counts[Status.NO_CONTRACT.ordinal()];
        long alreadyManual = counts[Status.ALREADY_MANUAL.ordinal()];
        long processed = counts[Status.SKIPPED.ordinal()];
        selectionLabel.setText("Выбрано: " + selected.size() + " · авто: " + safe
                + " · в ручной разбор: " + toManual + " · уже в очереди: " + alreadyManual
                + " · недоступно: " + processed);
        if (selected.isEmpty()) selectionHint.setText("Выберите строки. Предложения без договора тоже можно передать сотруднику.");
        else if (toManual == 0 && alreadyManual > 0 && processed == 0)
            selectionHint.setText("Все выбранные уже находятся на ручном разборе");
        else if (toManual == 0 && processed > 0)
            selectionHint.setText("Выбранные завершённые операции нельзя отправить повторно без отдельного решения.");
        else if (alreadyManual > 0)
            selectionHint.setText("Новые выбранные операции будут переданы; " + alreadyManual + " уже в очереди и не создадут дубликатов.");
        else selectionHint.setText("Отсутствие договора не мешает направить операцию сотруднику. Автоматически применяются только безопасные предложения.");
        boolean stale = isStale();
        if (stale) selectionHint.setText("Предпросмотр устарел. Нажмите «Обновить предложения»; до этого массовые действия недоступны.");
        selectionHint.getElement().setAttribute("title", !stale && toManual == 0 && alreadyManual > 0 && processed == 0
                ? "Все выбранные операции уже находятся на ручном разборе. Повторная отправка не нужна; открыть очередь можно через меню слева."
                : selectionHint.getText());
        selectionLabel.getElement().setAttribute("title", selectionLabel.getText());
        applyButton.setEnabled(safe > 0 && !busy && !stale && !analysisRunning());
        manualButton.setEnabled(toManual > 0 && !busy && !stale && !analysisRunning());
        clearButton.setEnabled(!selected.isEmpty() && !busy);
        if (selectResultsButton != null)
            selectResultsButton.setEnabled(!busy && !stale && selected.size() < preview.rows().size());
        applyButton.getElement().setAttribute("title", safe == 0 ? "Нет безопасных предложений в выделении" : "Привязать только безопасные предложения");
        manualButton.getElement().setAttribute("title", toManual == 0
                ? alreadyManual > 0 ? "Выбранные операции уже на ручном разборе" : "Нет новых операций для очереди"
                : "Передать новые выбранные операции в очередь");
        long pageSelected = pageRows().stream().filter(p -> selected.contains(p.operationId())).count();
        rendering = true;
        selectAll.setValue(!pageRows().isEmpty() && pageSelected == pageRows().size());
        selectAll.setIndeterminate(pageSelected > 0 && pageSelected < pageRows().size());
        rendering = false;
    }

    private void confirm(boolean manual) {
        refreshUser();
        if (preview == null || selected.isEmpty() || busy || analysisRunning()) return;
        if (isStale()) { AppNotifications.warning("Предпросмотр устарел", "Обновите предложения перед массовым действием."); return; }
        Set<String> chosen = draft.selectionSnapshot();
        Preview confirmedPreview = preview;
        long confirmedEmployee = DemoSession.employeeGeneration();
        var counts = selectedStatusCounts();
        long safe = counts[Status.SAFE.ordinal()];
        long review = counts[Status.REVIEW.ordinal()];
        long noContract = counts[Status.NO_CONTRACT.ordinal()];
        long queued = counts[Status.ALREADY_MANUAL.ordinal()];
        long processed = counts[Status.SKIPPED.ordinal()];
        long manualEligible = safe + review + noContract;
        long applicable = manual ? manualEligible : safe;
        if (applicable == 0) {
            AppNotifications.warning("Действие недоступно", manual
                    ? "Выбранные операции уже обработаны или находятся в очереди"
                    : "Нет безопасных предложений; для остальных используйте ручной разбор");
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(manual ? "Передать на ручной разбор" : "Подтвердить пакетную привязку");
        dialog.add(UiParts.div("matching-confirm",
                UiParts.text("", "Всего выбрано: " + chosen.size()),
                UiParts.text("", "Доступно для автоматической привязки: " + safe),
                UiParts.text("", "Доступно для передачи на ручной разбор: " + manualEligible),
                UiParts.text("", "Уже находится в ручной очереди: " + queued),
                UiParts.text("", "Недоступно для действия: " + (chosen.size() - applicable)),
                UiParts.text("muted", manual
                        ? "Исключены: уже в очереди - " + queued + ", уже обработаны - " + processed + "."
                        : "Исключены: требуют проверки - " + review + ", без договора - " + noContract
                                + ", уже в очереди - " + queued + ", уже обработаны - " + processed + "."),
                UiParts.text("muted", manual ? "Операции без найденного договора тоже будут переданы сотруднику. Существующие ручные задачи не дублируются."
                        : "Только однозначные предложения получат подтверждённую связь."),
                UiParts.text("muted", "Перед применением состояние будет проверено повторно. Отмена ничего не меняет.")));
        AtomicBoolean handled = new AtomicBoolean();
        Button submit = UiParts.button("app-action app-action--primary", "Подтвердить", () -> {
            if (!dialog.isOpened() || busy || !handled.compareAndSet(false, true)) return;
            busy = true;
            updateSelection();
            try {
                DemoSession.requireSameEmployeeGeneration(confirmedEmployee);
                if (confirmedPreview != preview || isStale() || analysisRunning())
                    throw new IllegalStateException("Предпросмотр изменился. Проверьте выбор и подтвердите заново.");
                var result = manual ? matching.sendToManual(state, confirmedPreview, chosen, currentInitiator())
                        : matching.apply(state, confirmedPreview, chosen, currentInitiator());
                dialog.close();
                AppNotifications.success(manual ? "Передано на ручной разбор" : "Автопривязка выполнена",
                        "Операций: " + (manual ? result.sentManual() : result.applied())
                                + "; без изменения: " + result.skipped() + " · " + result.runId());
                UI.getCurrent().getChildren().filter(MainLayout.class::isInstance)
                        .map(MainLayout.class::cast).forEach(MainLayout::refreshDemoCounts);
                refreshPreview();
            } catch (IllegalArgumentException | IllegalStateException ex) {
                dialog.close();
                AppNotifications.warning("Действие не выполнено", ex.getMessage());
            } finally { busy = false; updateSelection(); }
        });
        dialog.getFooter().add(UiParts.button("app-action app-action--secondary", "Отмена", dialog::close), submit);
        UiParts.openDialog(this, dialog);
    }

    private String currentInitiator() {
        return DemoSession.user() == null ? "Сотрудник" : DemoSession.user().fullName();
    }

    private String displayReason(String reason) {
        String displayed = reason.replace("Демо-проверка: ", "")
                .replace(". Не промышленный алгоритм.", ".")
                .replace(" Демонстрационная проверка.", "")
                .replace("Подходящий договор не указан в демонстрационных реквизитах.",
                        "Подходящий договор не найден.");
        return displayed.isEmpty() ? displayed : Character.toUpperCase(displayed.charAt(0)) + displayed.substring(1);
    }
    private boolean isStale() {
        return preview != null && (!java.util.Objects.equals(preview.from(), from.getValue())
                || !java.util.Objects.equals(preview.to(), to.getValue())
                || preview.includeProcessed() != includeProcessed.getValue()
                || preview.includeManual() != includeManual.getValue()
                || preview.revision() != state.revision() && sourceChanged(preview));
    }

    // SQL membership is always rechecked inside the confirmation transaction.
    private boolean sourceChanged(Preview candidate) {
        try {
            return candidate.fingerprint() != matching.fingerprint(state, candidate.from(), candidate.to());
        } catch (IllegalStateException | IllegalArgumentException unavailable) {
            return true;
        }
    }
    private String staleReason() {
        if (!java.util.Objects.equals(preview.from(), from.getValue())
                || !java.util.Objects.equals(preview.to(), to.getValue())
                || preview.includeProcessed() != includeProcessed.getValue()
                || preview.includeManual() != includeManual.getValue())
            return "Параметры поиска изменились после создания предпросмотра.";
        return "Операции, очередь или договорные связи в выбранном периоде изменились.";
    }
}
