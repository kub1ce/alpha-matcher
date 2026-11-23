package ru.urfu.alphamatcher.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import ru.urfu.alphamatcher.parsing.EnginePaymentPurposeParser;
import ru.urfu.alphamatcher.parsing.ParseResult;
import ru.urfu.alphamatcher.parsing.PaymentPurposeParser;

@Route(value = "section/parsing", layout = MainLayout.class)
@PageTitle("Проверка парсинга назначений платежа - А.Выгодоприобретатель")
public class ParsingView extends Div {
    private static final Map<String, String> EXAMPLES = examples();
    private final PaymentPurposeParser parser;
    private final TextArea input = new TextArea("Текст назначения платежа");
    private final Select<String> example = new Select<>();
    private final Span error = UiParts.text("parsing-error", "");
    private final Div results = UiParts.div("parsing-results");
    private final Div diagnostics = UiParts.div("parsing-diagnostics");
    private final Div output = UiParts.div("parsing-output");

    public ParsingView(PaymentPurposeParser parser) {
        this.parser = parser;
        setClassName("parsing-page app-page");
        input.setWidthFull();
        input.setMaxLength(EnginePaymentPurposeParser.MAX_LENGTH);
        input.setPlaceholder("Введите назначение платежа или загрузите пример");
        input.setValueChangeMode(ValueChangeMode.EAGER);
        input.addValueChangeListener(event -> output.setVisible(false));
        example.setLabel("Пример назначения");
        example.setItems(EXAMPLES.keySet());
        example.setPlaceholder("Выберите пример");
        example.addValueChangeListener(event -> {
            if (event.getValue() != null) {
                input.setValue(EXAMPLES.get(event.getValue()));
                error.setText("");
            }
        });
        var check = UiParts.button("app-action app-action--primary", "Проверить", this::check);
        var clear = UiParts.button("app-action app-action--secondary", "Очистить", () -> {
            input.clear();
            example.clear();
            error.setText("");
            output.setVisible(false);
        });
        add(UiParts.div("registry-filters parsing-input",
                UiParts.text("registry-title", "Проверка парсинга назначений платежа"),
                input,
                UiParts.div("parsing-actions", example, check, clear), error));
        output.add(UiParts.div("report-card parsing-result-card",
                        UiParts.text("report-section-title", "Результат разбора"), results),
                UiParts.div("report-card parsing-diagnostic-card",
                        UiParts.text("report-section-title", "Диагностика"), diagnostics));
        output.setVisible(false);
        add(output);
    }

    private void check() {
        try {
            ParseResult result = parser.parse(input.getValue());
            error.setText("");
            results.removeAll();
            results.add(field("ФИО физического лица", result.fullNames()),
                    field("ИНН", result.inns()),
                    field("Номер договора", result.contracts()),
                    field("Возможные организации", result.organizations()),
                    UiParts.div("parsing-result-item",
                            UiParts.text("parsing-result-label", "Упоминание платежа за третье лицо"),
                            badge(result.thirdPartyMentioned() ? ParseResult.Status.FOUND : ParseResult.Status.NOT_FOUND),
                            UiParts.text("parsing-result-value", result.thirdPartyMentioned() ? (ru.urfu.alphamatcher.experiment.MentionContexts.thirdPartyContext(result.features()) == ru.urfu.alphamatcher.experiment.MentionContexts.Context.NEGATED ? "Упоминание отрицается" : "Есть текстовое упоминание, не подтверждённый факт") : "Не упоминается")));
            diagnostics.removeAll();
            for (ParseResult.Diagnostic item : result.diagnostics())
                diagnostics.add(UiParts.text("parsing-diagnostic " + item.kind().name().toLowerCase(), item.message()));
            var features = result.features();
            Div details = UiParts.div("parsing-diagnostics",
                    UiParts.text("parsing-result-label", "Нормализованный текст"),
                    UiParts.text("parsing-result-value", features.normalized()));
            details.add(UiParts.text("parsing-result-value", "Контекст упоминания третьего лица: " + features.thirdPartyAssertion() + "; подтверждённый факт: false"));
            for (var reference : features.contractReferences())
                details.add(UiParts.text("parsing-result-value", "Ссылка: " + reference.surface()
                        + "; варианты: " + reference.alternatives() + "; полнота: " + reference.completeness()
                        + "; основания: " + reference.reasons()));
            for (var name : features.names())
                details.add(UiParts.text("parsing-result-value", "ФИО: " + name.surface()
                        + "; ключ: " + name.key() + "; распознано: " + name.recognized()));
            for (var claim : features.roleClaims())
                details.add(UiParts.text("parsing-result-value", "Роль: " + claim.role() + "; " + claim.kind() + ": " + claim.value()));
            for (var mention : features.mentions())
                details.add(UiParts.text("parsing-result-value", mention.toString()));
            for (String issue : features.issues())
                details.add(UiParts.text("parsing-result-value", issue));
            diagnostics.add(new com.vaadin.flow.component.details.Details("Признаки и интерпретации", details));
            output.setVisible(true);
        } catch (IllegalArgumentException exception) {
            error.setText(exception.getMessage());
            output.setVisible(false);
        }
    }

    private Div field(String label, ParseResult.Field field) {
        Div item = UiParts.div("parsing-result-item",
                UiParts.text("parsing-result-label", label), badge(field.status()));
        if (field.values().isEmpty() && field.invalidValues().isEmpty())
            item.add(UiParts.text("parsing-result-value muted", "Не найдено"));
        for (String value : field.values()) item.add(UiParts.text("parsing-result-value", value));
        for (String value : field.invalidValues()) item.add(UiParts.text("parsing-result-value invalid", value + " - некорректно"));
        return item;
    }

    private Span badge(ParseResult.Status status) {
        String caption = switch (status) {
            case FOUND -> "Найдено";
            case MULTIPLE -> "Несколько значений";
            case NOT_FOUND -> "Не найдено";
            case AMBIGUOUS -> "Неоднозначно";
            case INVALID -> "Некорректно";
        };
        return UiParts.text("parsing-status " + status.name().toLowerCase(), caption);
    }

    private static Map<String, String> examples() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ФИО, ИНН и договор", "Оплата за Иванова Ивана Ивановича, ИНН 123456789012, по договору AG-2026-031");
        values.put("Оплата организации", "Оплата ООО «Ромашка» по договору № AG-2026-014 за поставку оборудования");
        values.put("Платёж за третье лицо", "Платёж за третье лицо по дог. № AG-2026-031");
        values.put("Несколько ИНН", "ИНН 7707083893; ИНН 000012345627. Оплата услуг.");
        values.put("Несколько договоров", "Оплата по договору AG-2026-014 и по договору AG-2026-021");
        values.put("Без реквизитов", "Оплата консультационных услуг за октябрь");
        values.put("Некорректный ИНН", "ИНН 1234567890, оплата по договору № AG-2026-044");
        values.put("Пробелы и сокращения", "Оплата\u00a0по дог.\u00a0№ 45/26\nФИО: Петров Пётр Петрович");
        return Collections.unmodifiableMap(values);
    }
}
