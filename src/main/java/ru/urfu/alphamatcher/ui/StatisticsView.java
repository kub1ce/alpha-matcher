package ru.urfu.alphamatcher.ui;

import java.util.Locale;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoReportingService;
import ru.urfu.alphamatcher.demo.DemoReportingService.Statistics;

@Route(value = "section/statistics", layout = MainLayout.class)
@PageTitle("Статистика - А.Выгодоприобретатель")
public class StatisticsView extends Div {
    public StatisticsView(DemoDataService data, DemoReportingService reporting) {
        setClassName("statistics-page app-page");
        DemoStateUpdates.bind(this, data.state(), () -> refresh(data, reporting));
        refresh(data, reporting);
    }

    private void refresh(DemoDataService data, DemoReportingService reporting) {
        removeAll();
        Statistics stats = reporting.statistics(data.state());
        add(UiParts.div("registry-filters report-intro",
                UiParts.text("registry-title", "Аналитическая статистика сопоставления"),
                UiParts.text("registry-count", "Подтверждённые связи и текущая очередь. Предпросмотры не учитываются.")));
        Div kpis = UiParts.div("statistics-kpis",
                kpi("Всего операций", stats.total(), "В текущем реестре", ""),
                kpi("Автоматически привязано", stats.automatic(),
                        "Доля: " + stats.autoShare() + "% от " + stats.processed() + " подтверждённо обработанных", "safe"),
                kpi("Вручную привязано", stats.manual(), "Подтверждённые решения сотрудников", "manual"),
                kpi("Не обработано", stats.pending() + stats.other(),
                        "Ожидает разбора: " + stats.pending() + " · вне очереди: " + stats.other(), "pending"));
        add(kpis);

        Div distribution = UiParts.div("report-card",
                UiParts.text("report-section-title", "РАСПРЕДЕЛЕНИЕ СПОСОБОВ ОБРАБОТКИ (" + stats.total() + " операций)"));
        if (stats.total() == 0) distribution.add(UiParts.text("report-empty", "Операций пока нет."));
        else {
            Div bar = UiParts.div("statistics-distribution-bar");
            segment(bar, "automatic", stats.automatic(), stats.total());
            segment(bar, "manual", stats.manual(), stats.total());
            segment(bar, "pending", stats.pending(), stats.total());
            segment(bar, "other", stats.other(), stats.total());
            distribution.add(bar, UiParts.div("statistics-legend",
                    legend("automatic", "Автоматически", stats.automatic(), stats.percentOfTotal(stats.automatic())),
                    legend("manual", "Вручную", stats.manual(), stats.percentOfTotal(stats.manual())),
                    legend("pending", "Ожидает решения", stats.pending(), stats.percentOfTotal(stats.pending())),
                    legend("other", "Без привязки, вне очереди", stats.other(), stats.percentOfTotal(stats.other()))));
        }
        add(distribution);
        Div reasons = UiParts.div("report-card",
                UiParts.text("report-section-title", "ПРИЧИНЫ ТЕКУЩЕГО РУЧНОГО РАЗБОРА"),
                UiParts.text("muted", "Каждая операция учтена один раз по основной причине. Всего в очереди: " + stats.pending()));
        if (stats.pending() == 0) reasons.add(UiParts.text("report-empty", "Нет операций, ожидающих ручного разбора."));
        else stats.reasons().forEach((reason, count) -> {
            Div track = UiParts.div("statistics-reason-track");
            Div fill = UiParts.div("statistics-reason-fill");
            fill.getStyle().set("width", width(count, stats.pending()));
            track.add(fill);
            reasons.add(UiParts.div("statistics-reason",
                    UiParts.div("statistics-reason-label", UiParts.text("", reason),
                            UiParts.text("", count + " оп. (" + stats.percentOfQueue(count) + "%)")), track));
        });
        add(reasons);
    }

    private Div kpi(String title, int value, String note, String tone) {
        return UiParts.div("report-kpi " + tone, UiParts.text("report-kpi-label", title),
                UiParts.text("report-kpi-value", String.valueOf(value)), UiParts.text("muted", note));
    }
    private Div legend(String tone, String title, int count, int percent) {
        return UiParts.div("statistics-legend-item", UiParts.div("statistics-legend-dot " + tone),
                UiParts.text("", title + ": " + count + " (" + percent + "%)"));
    }
    private void segment(Div bar, String tone, int count, int total) {
        if (count == 0) return;
        Div segment = UiParts.div("statistics-segment " + tone);
        segment.getStyle().set("width", width(count, total));
        segment.getElement().setAttribute("title", count + " из " + total);
        bar.add(segment);
    }
    private String width(int part, int whole) {
        return whole == 0 ? "0%" : String.format(Locale.ROOT, "%.4f%%", 100.0 * part / whole);
    }
}
