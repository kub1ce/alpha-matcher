package ru.urfu.alphamatcher.experiment;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Bounded lexical paradigms, not suffix stripping. Unknown or ambiguous forms remain unresolved. */
public final class NameNormalizer {
    public enum Gender { MALE, FEMALE }
    private enum Part { SURNAME, GIVEN, PATRONYMIC }
    private record Lexeme(Part part, Gender gender, String canonical, List<String> forms) { }
    public record Mention(String surface, String key, boolean recognized) { }
    private static final List<Lexeme> LEXICON = new ArrayList<>();
    static {
        add(Part.SURNAME, Gender.MALE, "Иванов", "Иванова|Иванову|Ивановым|Иванове");
        add(Part.SURNAME, Gender.FEMALE, "Иванова", "Ивановой|Иванову");
        add(Part.SURNAME, Gender.MALE, "Петров", "Петрова|Петрову|Петровым|Петрове");
        add(Part.SURNAME, Gender.FEMALE, "Петрова", "Петровой|Петрову");
        add(Part.SURNAME, Gender.FEMALE, "Сидорова", "Сидоровой|Сидорову");
        add(Part.SURNAME, Gender.FEMALE, "Смирнова", "Смирновой|Смирнову");
        add(Part.SURNAME, Gender.MALE, "Орлов", "Орлова|Орлову|Орловым|Орлове");
        add(Part.SURNAME, Gender.MALE, "Ким", "Кима|Киму|Кимом|Киме");
        add(Part.SURNAME, Gender.FEMALE, "Ким", "");
        add(Part.SURNAME, Gender.MALE, "Шевченко", "");
        add(Part.SURNAME, Gender.FEMALE, "Шевченко", "");
        add(Part.SURNAME, Gender.MALE, "Иванов-Петров", "Иванова-Петрова|Иванову-Петрову|Ивановым-Петровым|Иванове-Петрове");
        add(Part.GIVEN, Gender.MALE, "Иван", "Ивана|Ивану|Иваном|Иване");
        add(Part.GIVEN, Gender.MALE, "Пётр", "Петра|Петру|Петром|Петре");
        add(Part.GIVEN, Gender.MALE, "Павел", "Павла|Павлу|Павлом|Павле");
        add(Part.GIVEN, Gender.MALE, "Алексей", "Алексея|Алексею|Алексеем|Алексее");
        add(Part.GIVEN, Gender.FEMALE, "Анна", "Анны|Анне|Анну|Анной");
        add(Part.GIVEN, Gender.FEMALE, "Мария", "Марии|Марию|Марией");
        add(Part.GIVEN, Gender.FEMALE, "Елена", "Елены|Елене|Елену|Еленой");
        add(Part.GIVEN, Gender.FEMALE, "Ольга", "Ольги|Ольге|Ольгу|Ольгой");
        add(Part.PATRONYMIC, Gender.MALE, "Иванович", "Ивановича|Ивановичу|Ивановичем|Ивановиче");
        add(Part.PATRONYMIC, Gender.MALE, "Петрович", "Петровича|Петровичу|Петровичем|Петровиче");
        add(Part.PATRONYMIC, Gender.MALE, "Олегович", "Олеговича|Олеговичу|Олеговичем|Олеговиче");
        add(Part.PATRONYMIC, Gender.MALE, "Николаевич", "Николаевича|Николаевичу|Николаевичем|Николаевиче");
        add(Part.PATRONYMIC, Gender.FEMALE, "Сергеевна", "Сергеевны|Сергеевне|Сергеевну|Сергеевной");
        add(Part.PATRONYMIC, Gender.FEMALE, "Андреевна", "Андреевны|Андреевне|Андреевну|Андреевной");
        add(Part.PATRONYMIC, Gender.FEMALE, "Игоревна", "Игоревны|Игоревне|Игоревну|Игоревной");
        add(Part.PATRONYMIC, Gender.FEMALE, "Викторовна", "Викторовны|Викторовне|Викторовну|Викторовной");
    }

    private NameNormalizer() { }

    public static Mention resolve(String surface) {
        String[] parts = key(surface).split(" ");
        if (parts.length != 3) return new Mention(surface, key(surface), false);
        List<String> resolved = new ArrayList<>();
        for (Gender gender : Gender.values()) {
            List<String> last = lookup(Part.SURNAME, gender, parts[0]);
            List<String> first = lookup(Part.GIVEN, gender, parts[1]);
            List<String> middle = lookup(Part.PATRONYMIC, gender, parts[2]);
            for (String l : last) for (String f : first) for (String m : middle)
                resolved.add(l + " " + f + " " + m);
        }
        List<String> unique = resolved.stream().distinct().toList();
        return new Mention(surface, unique.size() == 1 ? unique.getFirst() : key(surface), unique.size() == 1);
    }

    public static String key(String value) {
        return PurposeParser.normalize(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC))
                .toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    public static String organizationKey(String value) {
        return key(value).replaceAll("[«»\"„“”]", "").replaceAll("\\s+", " ").trim();
    }

    private static List<String> lookup(Part part, Gender gender, String form) {
        return LEXICON.stream().filter(item -> item.part == part && item.gender == gender && item.forms.contains(form))
                .map(Lexeme::canonical).toList();
    }

    private static void add(Part part, Gender gender, String canonical, String forms) {
        List<String> values = new ArrayList<>(List.of(key(canonical)));
        Arrays.stream(forms.split("\\|")).filter(value -> !value.isEmpty()).map(NameNormalizer::key).forEach(values::add);
        LEXICON.add(new Lexeme(part, gender, key(canonical), List.copyOf(values)));
    }
}
