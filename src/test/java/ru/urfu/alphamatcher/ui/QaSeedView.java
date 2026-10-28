package ru.urfu.alphamatcher.ui;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;
import ru.urfu.alphamatcher.demo.DemoOperationState;
import ru.urfu.alphamatcher.demo.DemoOperationState.AuditEvent;
import ru.urfu.alphamatcher.demo.DemoOperationState.BatchRun;

/** Test-classpath-only route for the isolated 130-row browser acceptance scenario. */
@Route(value = "qa/seed", layout = MainLayout.class)
public class QaSeedView extends Div {
    public QaSeedView(DemoDataService data) {
        setClassName("app-page");
        Div status = new Div();
        status.setId("qa-state-status");
        Button seed = new Button("Создать 130 синтетических операций", event -> {
            Operation template = data.fixtures().stream()
                    .filter(op -> op.id().equals("PAY-2026-000184")).findFirst().orElseThrow();
            List<Operation> operations = new ArrayList<>();
            for (int index = 0; index < 130; index++) {
                int kind = index % 5;
                operations.add(new Operation("PAY-2026-%06d".formatted(800000 + index),
                        LocalDateTime.of(2026, 8, 1, 10, index % 60),
                        template.payer(), template.recipient(), BigDecimal.valueOf(10000 + index),
                        kind == 0 ? "Оплата по договору AG-2026-014" : "Оплата услуг",
                        "", "", "",
                        switch (kind) {
                            case 0, 3, 4 -> List.of("AG-2026-014");
                            case 1 -> List.of("AG-2026-014", "AG-2026-021");
                            default -> List.of();
                        }));
            }
            List<Operation> all = new ArrayList<>(data.fixtures());
            all.addAll(operations);
            VaadinSession.getCurrent().setAttribute("demo-operations", new DemoOperationState(all));
            status.setText("Создано: 130");
        });
        Button audit = new Button("Создать 12 записей аудита", event -> {
            DemoOperationState state = data.state();
            for (int index = 0; index < 12; index++) {
                String id = "PAY-2026-%06d".formatted(800000 + index * 5);
                state.link(id, "AG-2026-014", "Автоматически", data.contracts());
            }
            status.setText("Создано записей аудита: 12");
        });
        Button reports = new Button("Создать изолированную историю для QA", event -> {
            DemoOperationState state = data.state();
            if (!state.batchRuns().isEmpty() || !state.auditEvents().isEmpty()) {
                status.setText("История уже содержит записи. Используйте новую браузерную сессию.");
                return;
            }
            for (int index = 0; index < 23; index++) {
                String runId = state.nextRunId();
                String actor = index % 2 == 0 ? "Рожков Андрей Сергеевич" : "Исаев Савелий Дмитриевич";
                String operationId = index % 2 == 0 ? "PAY-2026-000184" : "PAY-2026-000185";
                state.recordRun(new BatchRun(runId,
                        LocalDateTime.of(2026, 10, 20, 10, 0).plusMinutes(index), actor,
                        java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 10, 12),
                        12, 1, 1, index % 2, (index + 1) % 2, 0, 0, 0, 0,
                        index % 2 == 0 ? "Пакетная автопривязка" : "Передача на ручной разбор",
                        List.of(operationId), false, false));
            }
            try {
                var field = DemoOperationState.class.getDeclaredField("auditEvents");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<AuditEvent> events = (List<AuditEvent>) field.get(state);
                for (int index = 0; index < 23; index++) {
                    String actor = index % 2 == 0 ? "Рожков Андрей Сергеевич" : "Исаев Савелий Дмитриевич";
                    String operationId = index % 2 == 0 ? "PAY-2026-000184" : "PAY-2026-000185";
                    String action = switch (index % 4) {
                        case 0 -> "Автоматическая привязка";
                        case 1 -> "Постановка на ручной разбор";
                        case 2 -> "Ручная привязка";
                        default -> "Отвязка договора";
                    };
                    events.add(new AuditEvent("AUD-%04d".formatted(index + 1),
                            LocalDateTime.of(2026, 10, 20, 10, 0).plusMinutes(index),
                            operationId, action, "Без привязки", "AG-2026-014", actor,
                            index % 2 == 0 ? "Автоматически" : "Вручную",
                            "RUN-%03d".formatted(index + 1),
                            "Тестовое обоснование решения", "Номер договора указан в назначении",
                            "Причина ручной проверки", "", "Не обработана"));
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Не удалось подготовить тестовый журнал", exception);
            }
            status.setText("Создано: 23 запуска и 23 события аудита");
        });
        TextField operationId = new TextField("ID операции для снимка");
        operationId.setValue("PAY-2026-000184");
        Button snapshot = new Button("Снимок состояния", event -> {
            DemoOperationState state = data.state();
            String id = operationId.getValue();
            var match = state.match(id);
            status.setText("id=" + id + ";contract=" + (match == null ? "" : match.contractId())
                    + ";queued=" + state.isManualQueued(id) + ";revision=" + state.revision()
                    + ";runs=" + state.batchRuns().size() + ";audit=" + state.auditEvents().size());
        });
        add(seed, audit, reports, operationId, snapshot, status);
    }
}
