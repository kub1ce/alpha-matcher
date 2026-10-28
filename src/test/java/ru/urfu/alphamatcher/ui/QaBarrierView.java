package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoManualReviewService;

/** Isolated QA route; the actual business service runs inside a Vaadin UI callback. */
@Route(value = "qa/barrier-view", layout = MainLayout.class)
public class QaBarrierView extends Div {
    public QaBarrierView(DemoDataService data, DemoManualReviewService review) {
        setClassName("app-page");
        Div status = new Div();
        status.setId("qa-barrier-status");
        status.setText("Ожидает запуска");
        Button start = new Button("Выполнить действие с барьером", event -> {
            QaBarrierController.awaitRelease();
            review.confirm("PAY-2026-000187", "AG-2026-044",
                    "Проверены стороны и предмет договора", true, data.state());
            QaBarrierController.record(data.state());
            status.setText("Действие завершено");
        });
        add(start, status);
    }
}
