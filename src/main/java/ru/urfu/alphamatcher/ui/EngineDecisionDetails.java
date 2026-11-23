package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;

/** Presents the recorded outcome and its reasons before the diagnostics. */
final class EngineDecisionDetails {
    private EngineDecisionDetails() { }
    static Div render(Decision decision, DemoDataService data) {
        Div body = UiParts.div("detail-decision");
        if (decision == null) {
            body.add(UiParts.text("muted", "Анализ Matching Engine ещё не выполнен."));
            return body;
        }
        String explanation = switch (decision.status()) {
            case SAFE -> {
                String id = data.contractReference(decision.proposedContractId());
                var contract = data.contract(id);
                yield "Можно предложить договор " + (contract == null ? id : data.contractNumber(contract))
                        + ". Найден один явно указанный договор. Участники, их роли и реквизиты согласованы.";
            }
            case REVIEW -> "Требуется ручная проверка. Для автоматической привязки не хватает подтверждений.";
            case NO_CONTRACT -> "По проверенным реквизитам договор не найден.";
            case ALREADY_MANUAL -> "Операция уже находится в очереди ручного разбора.";
            case SKIPPED -> "Операция пропущена. Текущая привязка сохраняется.";
        };
        body.add(UiParts.text("detail-label-strong", "Решение алгоритма: " + decision.status()),
                UiParts.text("decision-explanation", explanation));
        if (!decision.features().contractReferences().isEmpty())
            body.add(UiParts.text("", "Договорные ссылки в назначении: " + String.join(", ",
                    decision.features().contractReferences().stream().map(r -> r.surface()).distinct().toList())));
        if (!decision.features().inns().isEmpty())
            body.add(UiParts.text("", "ИНН в назначении: " + String.join(", ", decision.features().inns())));
        decision.checks().stream().filter(check -> !check.passed()).forEach(check ->
                body.add(UiParts.text("decision-reason", employeeReason(check.code(), check.explanation()))));
        body.add(
                UiParts.text("", "Предложенный DB ID договора: " + decision.proposedContractId()),
                UiParts.text("", "Все DB ID кандидатов: " + decision.candidateIds()),
                UiParts.text("muted", "Предложение алгоритма не является подтверждённой привязкой. Показаны только выполненные проверки; остальные не считаются пройденными."));
        Div checks = UiParts.div("detail-decision");
        for (var check : decision.checks()) checks.add(UiParts.text("", (check.passed() ? "PASS " : "FAIL ") + check.code() + ": " + check.explanation()));
        body.add(new Details("Проверки и причины", checks),
                new Details("Поиск и полнота", UiParts.text("", decision.searchEvidence().toString())),
                new Details("Признаки парсинга и issues", UiParts.text("", decision.features().toString())));
        return body;
    }

    private static String employeeReason(String code, String explanation) {
        return switch (code) {
            case "CONTRACT_ANALYSIS_COMPLETE" -> "Договорную ссылку не удалось разобрать полностью.";
            case "CANDIDATE_SEARCH_COMPLETE" -> "Поиск завершён не по всем реквизитам. Единственность договора не подтверждена.";
            case "CANDIDATE_ROWS_CONSISTENT" -> "Сведения о найденных договорах противоречивы.";
            case "CANDIDATES_FOUND" -> "Договоров по проверенным реквизитам не найдено.";
            case "EXPLICIT_UNIQUE_CONTRACT" -> "Указанный номер не подтверждает один договор. Проверьте номер и найденные варианты.";
            case "PARTY_PAYER" -> "Плательщик по договору не подтверждён. Проверьте ФИО, ИНН и роль.";
            case "PARTY_RECIPIENT" -> "Получатель по договору не подтверждён. Проверьте ФИО, ИНН и роль.";
            default -> explanation;
        };
    }
}
