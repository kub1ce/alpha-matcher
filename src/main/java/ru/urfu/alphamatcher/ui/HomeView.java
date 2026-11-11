package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoSession;
import ru.urfu.alphamatcher.demo.DemoUser;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Главная - А.Выгодоприобретатель")
public class HomeView extends Div {
    private final Span greetingTitle = UiParts.text("greeting-title", "");

    public HomeView(DemoDataService data) {
        setClassName("dashboard app-page");
        DemoStateUpdates.bind(this, data.state(), () -> refresh(data));
        refresh(data);
    }

    private void refresh(DemoDataService data) {
        removeAll();
        refreshRole();
        var recent = data.operations();
        var dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy 'г.'",
                java.util.Locale.forLanguageTag("ru"));
        String currentDate = recent.isEmpty() ? "" : recent.getFirst().timestamp().format(dateFormat);
        String period = recent.isEmpty() ? "" : "Операции за период с "
                + recent.getLast().timestamp().toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                + " по " + recent.getFirst().timestamp().toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        Div greeting = UiParts.div("greeting",
                UiParts.div("greeting-copy",
                        UiParts.text("eyebrow", "Управление операционной поддержкой корпоративного бизнеса  ·  " + currentDate),
                        greetingTitle,
                        UiParts.text("muted", "Сводка обработки банковских операций и текущее состояние очереди договоров")),
                UiParts.div("greeting-actions",
                        UiParts.iconButton("app-action app-action--neutral", "Открыть операции", VaadinIcon.ARROW_RIGHT, true,
                                () -> UI.getCurrent().navigate(OperationsView.class)),
                        UiParts.iconButton("app-action app-action--warning", "Ручной разбор (" + data.manualItems().size() + ")", VaadinIcon.EXCLAMATION_CIRCLE_O,
                                false, () -> go("manual")),
                        UiParts.iconButton("app-action app-action--primary", "Запустить автопривязку", VaadinIcon.PLAY_CIRCLE_O,
                                false, () -> go("matching"))));
        Div table = UiParts.div("panel operation-panel");
        table.add(UiParts.div("panel-heading",
                UiParts.div("heading-copy",
                        UiParts.text("panel-title", "Последние расчетные операции"),
                        UiParts.text("muted", period)),
                UiParts.iconButton("text-link red-text", "Все операции", VaadinIcon.ARROW_RIGHT, true,
                        () -> UI.getCurrent().navigate(OperationsView.class))));
        Div tableScroll = UiParts.div("table-scroll");
        tableScroll.add(UiParts.div("table-head table-grid",
                UiParts.text("", "Операция"), UiParts.text("", "Дата / Время"),
                UiParts.text("", "Плательщик → Получатель"), UiParts.text("align-right", "Сумма"),
                UiParts.text("", "Договор"), UiParts.text("", "Состояние")));
        for (var op : recent) {
            var match = data.state().match(op.id());
            RouterLink operationLink = OperationNavigation.link(op.id(), op.id(), "home");
            operationLink.setClassName("operation-id");
            tableScroll.add(UiParts.div("table-row table-grid",
                    operationLink,
                    UiParts.text("muted", op.date()),
                    UiParts.div("party-cell", UiParts.text("", op.payer().name()), UiParts.text("muted small", "→ " + op.recipient().name())),
                    UiParts.text("amount align-right", op.amountText()),
                    UiParts.text(match == null ? "muted italic" : "", match == null ? "Не привязан" : match.contractId()),
                    UiParts.text(match == null ? "status unprocessed" : "status processed",
                            match == null ? "Не обработана" : "Обработана")));
        }
        table.add(tableScroll);
        Div manual = UiParts.div("panel manual-panel");
        manual.add(UiParts.div("side-panel-heading",
                UiParts.iconText("side-panel-title warning-text", VaadinIcon.EXCLAMATION_CIRCLE_O,
                        "Очередь ручного разбора (" + data.manualItems().size() + ")"),
                UiParts.iconButton("text-link brown-text", "Перейти", VaadinIcon.ARROW_RIGHT, true,
                        () -> go("manual"))));
        for (var item : data.manualItems().subList(0, Math.min(4, data.manualItems().size()))) {
            RouterLink manualLink = OperationNavigation.link(item.operationId(), item.operationId(), "home");
            manualLink.setClassName("operation-id");
            manual.add(UiParts.div("manual-item",
                    UiParts.div("manual-line", manualLink,
                            UiParts.text("amount", item.amount())),
                    UiParts.text("muted", item.payer()),
                    UiParts.text("reason", item.reason())));
        }
        Div runs = UiParts.div("panel runs-panel");
        runs.add(UiParts.div("side-panel-heading",
                UiParts.iconText("side-panel-title", VaadinIcon.CLOCK, "Последние пакетные действия"),
                UiParts.iconButton("text-link", "История", VaadinIcon.ARROW_RIGHT, true,
                        () -> go("runs"))));
        if (data.runs().isEmpty()) runs.add(UiParts.text("muted run-item", "Подтверждённых пакетных действий пока нет."));
        for (var run : data.runs().stream().limit(3).toList()) {
            runs.add(UiParts.div("run-item",
                    UiParts.div("manual-line", UiParts.text("operation-id", run.id()),
                            UiParts.text("muted small", run.date())),
                    UiParts.div("run-stats", UiParts.text("", "Выбрано: " + run.total()),
                            UiParts.text("success-text", "Привязано: " + run.matched()),
                            UiParts.text("brown-text", "Разбор: " + run.manual()))));
        }
        add(greeting, UiParts.div("dashboard-columns", table, UiParts.div("right-column", manual, runs)));
    }

    public void refreshRole() {
        DemoUser user = DemoSession.user();
        greetingTitle.setText("Здравствуйте, " + (user == null ? "сотрудник" : user.firstName()) + "!");
    }

    private void go(String key) {
        if ("manual".equals(key)) UI.getCurrent().navigate(ManualReviewView.class);
        else if ("matching".equals(key)) UI.getCurrent().navigate(MatchingView.class);
        else if ("runs".equals(key)) UI.getCurrent().navigate(RunHistoryView.class);
        else UI.getCurrent().navigate(SectionView.class, new RouteParameters("key", key));
    }
}
