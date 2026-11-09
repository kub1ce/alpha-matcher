package ru.urfu.alphamatcher.experiment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Extracts evidence, never assigns an unlabelled identifier to a participant role. */
public final class PurposeParser {
    public static final int MAX_LENGTH = 4000;
    private static final Pattern SPACE = Pattern.compile("[\\s\\u00a0\\u202f]+");
    private static final Pattern INN_LABEL = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])ИНН\\s*[:№#=-]?\\s*");
    private static final Pattern INN_VALUE = Pattern.compile("[0-9][0-9 .-]*");
    private static final Pattern CONTRACT_LABEL = Pattern.compile(
            "(?iu)(?<!\\p{L})(?:договор(?:а|у|ом|е)?|дог\\.)(?!\\p{L})\\s*(?:№|N\\.?|#)?\\s*[«\"]?\\s*");
    private static final Pattern CONTRACT_VALUE = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}./_-]*");
    private static final String WORD = "[а-яё]+(?:-[а-яё]+)?";
    private static final Pattern NAME = Pattern.compile("(?iu)(?<!\\p{L})(?=(" + WORD + " " + WORD + " " + WORD + ")(?!\\p{L}))");
    private static final Pattern NAME_LABEL = Pattern.compile("(?iu)(?<!\\p{L})ФИО\\s*[:=-]?\\s*(" + WORD + "(?: " + WORD + "){0,2})");
    private static final Pattern PATRONYMIC = Pattern.compile("(?iu).*(?:ович|евич|овича|евича|овичу|евичу|овичем|евичем|овиче|евиче|овна|евна|овны|евны|овне|евне|овну|евну|овной|евной)$");
    private static final Pattern ORGANIZATION = Pattern.compile("(?iu)(?<!\\p{L})(?:ООО|ПАО|АО|ЗАО|ОАО)\\s+(?:[«\"]([^»\"]{1,160})[»\"]|[\\p{L}\\p{N}.-]+)");
    private static final Pattern THIRD_PARTY = Pattern.compile("(?iu)(?<!\\p{L})за\\s+треть(?:е|его|ю)\\s+лиц[оа](?!\\p{L})");
    private static final Pattern FOR_PREFIX = Pattern.compile("(?iu)(?:^|[^\\p{L}])за(?:\\s+ИП)?\\s*$");

    public record Features(String normalized, List<String> inns, List<String> invalidInns,
                           List<String> contractNumbers, boolean thirdParty, List<String> issues,
                           List<NameNormalizer.Mention> names, List<String> organizations) {
        public Features(String normalized, List<String> inns, List<String> invalidInns,
                        List<String> contractNumbers, boolean thirdParty, List<String> issues) {
            this(normalized, inns, invalidInns, contractNumbers, thirdParty, issues, List.of(), List.of());
        }
        public Features {
            inns = List.copyOf(inns);
            invalidInns = List.copyOf(invalidInns);
            contractNumbers = List.copyOf(contractNumbers);
            issues = List.copyOf(issues);
            names = List.copyOf(names);
            organizations = List.copyOf(organizations);
        }
        public boolean hasSearchKeys() { return !inns.isEmpty() || !contractNumbers.isEmpty() || !names.isEmpty() || !organizations.isEmpty(); }
    }

    public Features parse(String text) {
        if (text != null && text.length() > MAX_LENGTH)
            throw new IllegalArgumentException("Назначение превышает 4000 символов");
        String normalized = normalize(text);
        var inns = new LinkedHashSet<String>();
        var invalid = new LinkedHashSet<String>();
        var numbers = new LinkedHashSet<String>();
        List<String> issues = new ArrayList<>();
        var labels = INN_LABEL.matcher(normalized);
        while (labels.find()) {
            var value = INN_VALUE.matcher(normalized).region(labels.end(), normalized.length());
            if (!value.lookingAt()) {
                issues.add("После метки ИНН нет числового значения");
                continue;
            }
            String digits = value.group().replaceAll("[^0-9]", "");
            (Inn.valid(digits) ? inns : invalid).add(digits);
            if (value.end() < normalized.length() && Character.isLetterOrDigit(normalized.charAt(value.end()))
                    && !Character.isWhitespace(normalized.charAt(value.end() - 1)))
                issues.add("После числового ИНН есть недопустимое продолжение");
        }
        var contracts = CONTRACT_LABEL.matcher(normalized);
        while (contracts.find()) {
            var token = CONTRACT_VALUE.matcher(normalized).region(contracts.end(), normalized.length());
            if (!token.lookingAt()) {
                issues.add("После метки договора нет номера");
                continue;
            }
            String value = token.group();
            if (value.length() <= 128 && value.matches(".*[0-9].*")) numbers.add(number(value));
            else issues.add("Неподдерживаемый формат номера договора: " + value);
        }
        var names = new java.util.LinkedHashMap<String, NameNormalizer.Mention>();
        var explicitNames = NAME_LABEL.matcher(normalized);
        while (explicitNames.find()) {
            var mention = NameNormalizer.resolve(explicitNames.group(1));
            names.put(mention.key(), mention);
            if (mention.surface().split(" ").length != 3) issues.add("Неполное ФИО");
        }
        boolean thirdParty = THIRD_PARTY.matcher(normalized).find();
        var possibleNames = NAME.matcher(normalized);
        while (possibleNames.find()) {
            String surface = possibleNames.group(1);
            if (!PATRONYMIC.matcher(surface.substring(surface.lastIndexOf(' ') + 1)).matches()) continue;
            var mention = NameNormalizer.resolve(surface);
            names.put(mention.key(), mention);
            thirdParty |= forPrefix(normalized, possibleNames.start());
        }
        var organizations = new LinkedHashSet<String>();
        var orgMatcher = ORGANIZATION.matcher(normalized);
        while (orgMatcher.find()) {
            organizations.add(NameNormalizer.organizationKey(orgMatcher.group()));
            thirdParty |= forPrefix(normalized, orgMatcher.start());
        }
        return new Features(normalized, List.copyOf(inns), List.copyOf(invalid), List.copyOf(numbers),
                thirdParty, issues, List.copyOf(names.values()), List.copyOf(organizations));
    }

    private static boolean forPrefix(String text, int start) {
        return FOR_PREFIX.matcher(text.substring(Math.max(0, start - 25), start)).find();
    }

    public static String normalize(String value) {
        return SPACE.matcher(value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
                .replace('–', '-').replace('—', '-').replace('‑', '-').replace('“', '"').replace('”', '"'))
                .replaceAll(" ").trim();
    }

    public static String number(String value) { return normalize(value).toUpperCase(Locale.ROOT); }
}
