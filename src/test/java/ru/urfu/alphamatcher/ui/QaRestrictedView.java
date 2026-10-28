package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoManualReviewService;

/** Test-only route to send a valid UIDL callback into a restricted production service method. */
@Route(value = "qa/restricted-action", layout = MainLayout.class)
public class QaRestrictedView extends Div {
    public QaRestrictedView(DemoDataService data, DemoManualReviewService review) {
        setClassName("app-page");
        Div result = new Div();
        result.setId("qa-restricted-result");
        result.setText("Ожидает запроса");
        Button attempt = new Button("Попытка неоднозначной привязки", event -> {
            try {
                review.confirm("PAY-2026-000187", "AG-2026-044", "Проверены стороны и договор",
                        true, data.state());
                result.setText("Неожиданно выполнено");
            } catch (IllegalStateException denied) {
                result.setText("Отказ: " + denied.getMessage());
            }
        });
        add(attempt, result);
    }
}
