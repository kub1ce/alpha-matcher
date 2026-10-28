package ru.urfu.alphamatcher.ui;

import java.util.Map;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

@Route(value = "section/:key", layout = MainLayout.class)
@PageTitle("Раздел - А.Выгодоприобретатель")
public class SectionView extends Div implements BeforeEnterObserver {
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("statistics", "Статистика"), Map.entry("reports", "Отчёты"),
            Map.entry("directories", "Справочники"), Map.entry("operations", "Операции"),
            Map.entry("contracts", "Договоры"), Map.entry("manual", "Ручной разбор"),
            Map.entry("matching", "Автопривязка"), Map.entry("clients", "Клиенты"),
            Map.entry("runs", "История запусков"), Map.entry("audit", "Журнал действий"),
            Map.entry("parsing", "Проверка парсинга назначений платежа"));

    public SectionView() {
        setClassName("placeholder-page app-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String key = event.getRouteParameters().get("key").orElse("");
        String title = TITLES.get(key);
        if ("matching".equals(key)) { event.rerouteTo(MatchingView.class); return; }
        if (title == null) {
            event.rerouteTo(NotFoundView.class);
            return;
        }
        removeAll();
        RouterLink home = new RouterLink("На главную", HomeView.class);
        home.setClassName("app-action app-action--secondary");
        var card = UiParts.div("placeholder-card",
                UiParts.text("eyebrow", title),
                UiParts.text("placeholder-title", "Раздел недоступен"),
                home);
        if ("matching".equals(key)) {
            var params = event.getLocation().getQueryParameters().getParameters();
            String from = params.getOrDefault("from", java.util.List.of("")).getFirst();
            String to = params.getOrDefault("to", java.util.List.of("")).getFirst();
            if (!from.isBlank() || !to.isBlank())
                card.add(UiParts.text("", "Выбранный период: " + (from.isBlank() ? "не задано" : from)
                        + " - " + (to.isBlank() ? "не задано" : to)));
        }
        add(card);
    }
}
