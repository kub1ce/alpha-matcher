package ru.urfu.alphamatcher.parsing;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.experiment.PurposeParser;
import ru.urfu.alphamatcher.parsing.ParseResult.*;

/** Extraction only: a text without an operation and JDBC evidence never produces SAFE. */
@Service
public final class EnginePaymentPurposeParser implements PaymentPurposeParser {
    public static final int MAX_LENGTH = PurposeParser.MAX_INPUT_LENGTH;
    private final PurposeParser parser = new PurposeParser();

    @Override
    public ParseResult parse(String text) {
        String original = text == null ? "" : text;
        if (original.length() > MAX_LENGTH)
            throw new IllegalArgumentException("Назначение не должно превышать " + MAX_LENGTH + " символов UTF-16");
        PurposeParser.Features f = parser.parse(original);
        List<Diagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(new Diagnostic(Kind.EXTRACTED, "Извлечение признаков без проверки договора и участников операции."));
        diagnostics.add(new Diagnostic(f.contractAnalysis() == PurposeParser.Completeness.COMPLETE ? Kind.EXTRACTED : Kind.REVIEW,
                "Синтаксический разбор договорных ссылок: " + switch (f.contractAnalysis()) {
                    case COMPLETE -> "полный разбор";
                    case INCOMPLETE -> "неполный разбор, требуется проверка";
                    case UNKNOWN -> "не установлена, требуется проверка";
                }));
        f.issues().stream().map(issue -> new Diagnostic(Kind.REVIEW, explain(issue))).distinct().forEach(diagnostics::add);
        if (!f.invalidInns().isEmpty()) diagnostics.add(new Diagnostic(Kind.FORMAT, "Некорректные ИНН: " + f.invalidInns()));
        if (f.thirdParty()) diagnostics.add(new Diagnostic(Kind.REVIEW, "Есть упоминание платежа за третье лицо; контекст и участники требуют проверки."));
        if (f.contractDisplayNumbers().size() > 1 || f.inns().size() > 1)
            diagnostics.add(new Diagnostic(Kind.EXTRACTED, "Извлечено несколько значений. Само по себе это не означает конфликт ролей; связи участников проверяет движок."));
        var names = f.names().stream().map(n -> n.surface()).toList();
        return new ParseResult(original, field(names, List.of(), f, "NAME"), field(f.inns(), f.invalidInns(), f, "INN"),
                field(f.contractDisplayNumbers(), List.of(), f, "CONTRACT"), field(f.organizations(), List.of(), f, "ORGANIZATION"),
                f.thirdParty(), diagnostics, f);
    }

    private static Field field(List<String> values, List<String> invalid, PurposeParser.Features features, String kind) {
        boolean conflictingRoles = features.roleClaims().stream().filter(c -> c.kind().equals(kind))
                .collect(java.util.stream.Collectors.groupingBy(PurposeParser.RoleClaim::value,
                        java.util.stream.Collectors.mapping(PurposeParser.RoleClaim::role, java.util.stream.Collectors.toSet())))
                .values().stream().anyMatch(roles -> roles.size() > 1);
        boolean unresolvedName = kind.equals("NAME") && !values.isEmpty() && features.issues().stream().anyMatch(i -> i.startsWith("INCOMPLETE_NAME") || i.startsWith("UNSUPPORTED_NAME_FORMAT"));
        Status status = !invalid.isEmpty() ? Status.INVALID : conflictingRoles || unresolvedName ? Status.AMBIGUOUS : values.size() > 1 ? Status.MULTIPLE
                : values.isEmpty() ? Status.NOT_FOUND : Status.FOUND;
        return new Field(status, values, invalid);
    }

    private static String explain(String issue) {
        String code = issue.split(":", 2)[0];
        return switch (code) {
            case "UNRESOLVED_MENTION_CONTEXT" -> "Есть отрицание, исправление, историческая ссылка или цитата. Упоминания сохранены; контекст и факты операции требуют проверки.";
            case "UNSUPPORTED_CONTRACT" -> "Договорная конструкция не разобрана полностью. Автоматическое подтверждение недопустимо.";
            case "CONFUSABLE_IDENTIFIER_RISK" -> "Обнаружены разные, визуально похожие номера. Исходные Unicode-символы сохранены без автоматической подмены.";
            case "MIXED_SCRIPT_CONTRACT" -> "В номере договора смешаны кириллица и латиница. Исходные символы сохранены; требуется проверка.";
            case "INCOMPLETE_NAME" -> "ФИО указано не полностью: инициалы или отдельные части не подтверждают полное имя.";
            case "UNSUPPORTED_NAME_FORMAT" -> "Формат ФИО не удалось надежно интерпретировать.";
            case "UNSUPPORTED_ACTOR_CONTEXT" -> issue.contains("entrepreneur capacity") ? "Упоминание статуса ИП не устанавливает личность участника. Требуется проверка." : "Контекст участника не удалось надежно отделить от описания платежа.";
            case "CONFLICTING_ROLE_CLAIMS" -> "В тексте есть противоречащие друг другу указания роли участника.";
            case "INVALID_INN_FORMAT" -> "Формат ИНН не подтвержден. Проверьте реквизиты.";
            case "SIZE_LIMIT", "MATCH_LIMIT", "TIME_LIMIT" -> "Достигнут предел анализа. Результат неполный и требует проверки.";
            default -> "Обнаружена неподдерживаемая конструкция. Анализ требует проверки; код доступен в технических деталях.";
        };
    }
}
