package ru.urfu.alphamatcher.parsing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.parsing.ParseResult.Diagnostic;
import ru.urfu.alphamatcher.parsing.ParseResult.Field;
import ru.urfu.alphamatcher.parsing.ParseResult.Kind;
import ru.urfu.alphamatcher.parsing.ParseResult.Status;

/** Deliberately limited, read-only parser. Candidates are never assigned to a payment party. */
@Service
public final class BasicPaymentPurposeParser implements PaymentPurposeParser {
    public static final int MAX_LENGTH = 4000;
    private static final Pattern INN = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])ИНН\\s*[:№#-]?\\s*([0-9][0-9\\s\\u00a0\\u202f.-]{6,22}[0-9])");
    private static final Pattern CONTRACT = Pattern.compile("(?iu)(?<!\\p{L})(?:по\\s+)?(?:договор(?:а|у|ом|е)?|дог\\.)\\s*(?:№|N\\.?|#)?\\s*([A-ZА-Я0-9][A-ZА-Я0-9./-]{1,39})");
    private static final Pattern FULL_NAME = Pattern.compile("(?iu)(?<!\\p{L})(?:ФИО\\s*[:=-]?|(?:оплата|плат[её]ж)\\s+за)\\s*([А-ЯЁ][а-яё]+(?:-[А-ЯЁ][а-яё]+)?\\s+[А-ЯЁ][а-яё]+\\s+[А-ЯЁ][а-яё]+)");
    private static final Pattern ORGANIZATION = Pattern.compile("(?iu)(?<!\\p{L})(?:ООО|АО|ПАО|ОАО|ЗАО)\\s*[«\"„]([^»\"“]{2,100})[»\"“]");
    private static final Pattern THIRD_PARTY = Pattern.compile("(?iu)(?:за\\s+треть(?:е|его|ю)\\s+лиц[оа]|(?:оплата|плат[её]ж)\\s+за\\s+[А-ЯЁ][а-яё]+\\s+[А-ЯЁ][а-яё]+\\s+[А-ЯЁ][а-яё]+(?:овича|евича|ович|евич|овну|евну|овна|евна|ичну|ична|ич)(?!\\p{L}))");
    private static final Pattern PATRONYMIC = Pattern.compile("(?iu).+(?:овича|евича|ович|евич|овну|евну|овна|евна|ичну|ична|ич)");
    private static final int[] LEGAL_WEIGHTS = {2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] PERSON_FIRST = {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] PERSON_SECOND = {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};

    @Override
    public ParseResult parse(String text) {
        String original = text == null ? "" : text;
        if (original.length() > MAX_LENGTH) throw new IllegalArgumentException("Назначение не должно превышать 4000 символов");
        String normalized = original.replace('\u00a0', ' ').replace('\u202f', ' ')
                .replace('–', '-').replace('—', '-')
                .replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (normalized.isBlank()) diagnostics.add(new Diagnostic(Kind.MISSING, "Введите назначение платежа для проверки."));

        LinkedHashSet<String> validInns = new LinkedHashSet<>();
        LinkedHashSet<String> invalidInns = new LinkedHashSet<>();
        Matcher innMatcher = INN.matcher(normalized);
        while (innMatcher.find()) {
            String digits = innMatcher.group(1).replaceAll("[^0-9]", "");
            (validInn(digits) ? validInns : invalidInns).add(digits);
        }
        LinkedHashSet<String> contracts = new LinkedHashSet<>();
        Matcher contractMatcher = CONTRACT.matcher(normalized);
        while (contractMatcher.find()) {
            String candidate = contractMatcher.group(1).replaceAll("[.,/-]+$", "");
            if (candidate.matches(".*[0-9].*")) contracts.add(candidate);
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher nameMatcher = FULL_NAME.matcher(normalized);
        while (nameMatcher.find()) {
            String candidate = nameMatcher.group(1).replaceAll("\\s+", " ");
            String[] parts = candidate.split(" ");
            if (PATRONYMIC.matcher(parts[2]).matches()) names.add(candidate);
        }
        LinkedHashSet<String> organizations = new LinkedHashSet<>();
        Matcher organizationMatcher = ORGANIZATION.matcher(normalized);
        while (organizationMatcher.find()) organizations.add(organizationMatcher.group().replaceAll("\\s+", " "));
        boolean thirdParty = THIRD_PARTY.matcher(normalized).find();

        Field fullNames = field(names);
        Field inns = innField(validInns, invalidInns);
        Field contractNumbers = field(contracts);
        Field organizationNames = field(organizations);
        addDiagnostics(diagnostics, "ФИО", fullNames);
        addDiagnostics(diagnostics, "ИНН", inns);
        addDiagnostics(diagnostics, "Договор", contractNumbers);
        addDiagnostics(diagnostics, "Организация", organizationNames);
        if (thirdParty) diagnostics.add(new Diagnostic(Kind.REVIEW,
                "Есть текстовый признак платежа за третье лицо. Проверьте участников вручную."));
        else diagnostics.add(new Diagnostic(Kind.MISSING, "Признак платежа за третье лицо не найден."));
        return new ParseResult(original, fullNames, inns, contractNumbers, organizationNames,
                thirdParty, diagnostics);
    }

    private static Field field(LinkedHashSet<String> candidates) {
        return new Field(candidates.isEmpty() ? Status.NOT_FOUND
                : candidates.size() > 1 ? Status.AMBIGUOUS : Status.FOUND,
                List.copyOf(candidates), List.of());
    }

    private static Field innField(LinkedHashSet<String> valid, LinkedHashSet<String> invalid) {
        int count = valid.size() + invalid.size();
        return new Field(count > 1 ? Status.AMBIGUOUS : !invalid.isEmpty() ? Status.INVALID
                : valid.isEmpty() ? Status.NOT_FOUND : Status.FOUND,
                List.copyOf(valid), List.copyOf(invalid));
    }

    private static void addDiagnostics(List<Diagnostic> out, String label, Field field) {
        if (!field.values().isEmpty()) {
            int count = field.values().size();
            String noun = count % 10 == 1 && count % 100 != 11 ? "значение"
                    : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)
                    ? "значения" : "значений";
            out.add(new Diagnostic(Kind.EXTRACTED, label + ": найдено " + count + " " + noun + "."));
        }
        if (field.status() == Status.NOT_FOUND) out.add(new Diagnostic(Kind.MISSING, label + ": не найдено."));
        if (field.status() == Status.AMBIGUOUS) out.add(new Diagnostic(Kind.REVIEW,
                label + ": найдено несколько значений, требуется проверка."));
        if (!field.invalidValues().isEmpty()) out.add(new Diagnostic(Kind.FORMAT,
                label + ": есть некорректные значения (длина или контрольные цифры)."));
    }

    public static boolean validInn(String value) {
        if (value == null || !value.matches("[0-9]{10}|[0-9]{12}")) return false;
        if (value.length() == 10) return checkDigit(value, LEGAL_WEIGHTS) == value.charAt(9) - '0';
        return checkDigit(value, PERSON_FIRST) == value.charAt(10) - '0'
                && checkDigit(value, PERSON_SECOND) == value.charAt(11) - '0';
    }

    private static int checkDigit(String value, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (value.charAt(i) - '0') * weights[i];
        return sum % 11 % 10;
    }
}
