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
    /** key is only a canonical hypothesis; neither it nor recognized confirms a DB identity. */
    public record Mention(String surface, String key, boolean recognized) {
        public List<String> canonicalHypotheses() { return hypotheses(surface); }
    }
    public enum FullNameMatch { NONE, EXACT, INFLECTED, REORDERED }
    /** A full-name comparison with one specified DB participant, not an identity/INN match. */
    public record Confirmation(String surface, String databaseCanonical, FullNameMatch match) {
        public boolean fullNameMatched() { return match != FullNameMatch.NONE; }
    }
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
        // A standard irregular paradigm: the productive consonant rule must not invent "Лева".
        add(Part.GIVEN, Gender.MALE, "Лев", "Льва|Льву|Львом|Льве");
        add(Part.GIVEN, Gender.MALE, "Илья", "Ильи|Илье|Илью|Ильёй");
        add(Part.GIVEN, Gender.FEMALE, "Любовь", "Любови|Любовь|Любовью");
        add(Part.SURNAME, Gender.MALE, "Заяц", "Зайца|Зайцу|Зайцем|Зайце");
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
        add(Part.GIVEN, Gender.MALE, "Сергей", "Сергея|Сергею|Сергеем|Сергее");
        add(Part.GIVEN, Gender.MALE, "Дмитрий", "Дмитрия|Дмитрию|Дмитрием|Дмитрии");
        add(Part.GIVEN, Gender.MALE, "Александр", "Александра|Александру|Александром|Александре");
        add(Part.GIVEN, Gender.MALE, "Константин", "Константина|Константину|Константином|Константине");
        add(Part.GIVEN, Gender.MALE, "Виктор", "Виктора|Виктору|Виктором|Викторе");
        add(Part.GIVEN, Gender.MALE, "Андрей", "Андрея|Андрею|Андреем|Андрее");
        add(Part.GIVEN, Gender.MALE, "Николай", "Николая|Николаю|Николаем|Николае");
        add(Part.GIVEN, Gender.FEMALE, "Наталья", "Натальи|Наталье|Наталью|Натальей");
        add(Part.GIVEN, Gender.FEMALE, "Татьяна", "Татьяны|Татьяне|Татьяну|Татьяной");
        add(Part.GIVEN, Gender.FEMALE, "Ирина", "Ирины|Ирине|Ирину|Ириной");
        add(Part.PATRONYMIC, Gender.MALE, "Васильевич", "Васильевича|Васильевичу|Васильевичем|Васильевиче");
        add(Part.PATRONYMIC, Gender.MALE, "Сергеевич", "Сергеевича|Сергеевичу|Сергеевичем|Сергеевиче");
        add(Part.PATRONYMIC, Gender.MALE, "Андреевич", "Андреевича|Андреевичу|Андреевичем|Андреевиче");
        add(Part.PATRONYMIC, Gender.MALE, "Романович", "Романовича|Романовичу|Романовичем|Романовиче");
        add(Part.PATRONYMIC, Gender.FEMALE, "Александровна", "Александровны|Александровне|Александровну|Александровной");
        add(Part.PATRONYMIC, Gender.FEMALE, "Павловна", "Павловны|Павловне|Павловну|Павловной");
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
        if(unique.size()==1) return new Mention(surface,unique.getFirst(),true);
        List<String> hypotheses=hypotheses(surface);
        return new Mention(surface,hypotheses.size()==1?hypotheses.getFirst():key(surface),false);
    }

    public static String key(String value) {
        return PurposeParser.normalize(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC))
                .toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    public static String organizationKey(String value) {
        return key(value).replaceFirst("^общество с ограниченной ответственностью\\b","ооо")
                .replaceFirst("^публичное акционерное общество\\b","пао")
                .replaceFirst("^акционерное общество\\b","ао")
                .replaceAll("[«»\"„“”]", "").replaceAll("\\s+", " ").trim();
    }

    public static boolean initials(String value) { return value.indexOf('.')>=0; }

    /** Evidence is checked against a real, complete DB name, never against a guessed surname alone. */
    public static boolean matches(String surface,String canonical) {
        return confirm(surface,canonical).fullNameMatched();
    }

    public static Confirmation confirm(String surface,String canonical) {
        String value=key(surface), known=key(canonical);
        FullNameMatch result=FullNameMatch.NONE;
        if(singleSupportedScript(value) && singleSupportedScript(known) && !initials(value)
                && value.split(" ").length==3 && known.split(" ").length==3) {
            if(value.equals(known)) result=FullNameMatch.EXACT;
            else if(matchesOrdered(value,known)) result=FullNameMatch.INFLECTED;
            else {
                String ordered=surnameFirst(value);
                if(!ordered.equals(value) && (ordered.equals(known)||matchesOrdered(ordered,known))) result=FullNameMatch.REORDERED;
            }
        }
        return new Confirmation(surface,known,result);
    }

    static boolean singleSupportedScript(String value) {
        var scripts=new java.util.HashSet<Character.UnicodeScript>();
        value.codePoints().filter(Character::isLetter).forEach(c->scripts.add(Character.UnicodeScript.of(c)));
        return scripts.size()==1 && (scripts.contains(Character.UnicodeScript.CYRILLIC)||scripts.contains(Character.UnicodeScript.LATIN));
    }

    // Only a Russian patronymic anchors an alternative order; foreign component order is never guessed.
    private static String surnameFirst(String value) {
        String[] p=value.split(" ");
        return p.length==3 && p[1].matches(".*(?:ич(?:а|у|ем|ом|е)?|(?:овн|евн|ичн)(?:а|ы|е|у|ой))$")
                && !p[2].matches(".*(?:ич(?:а|у|ем|ом|е)?|(?:овн|евн|ичн)(?:а|ы|е|у|ой))$")
                ? p[2]+" "+p[0]+" "+p[1] : value;
    }

    private static boolean matchesOrdered(String surface,String canonical) {
        String value=key(surface), known=key(canonical);
        if(initials(value) || known.split(" ").length!=3) return false;
        if(value.equals(known)) return true;
        String[] p=known.split(" ");
        for(Gender gender:Gender.values()) {
            boolean lexicalGiven = lookup(Part.GIVEN,gender,p[1]).contains(p[1]);
            if(gender==Gender.MALE && !p[2].endsWith("ич") || gender==Gender.FEMALE && !p[2].endsWith("на")) continue;
            for(int grammaticalCase=1;grammaticalCase<=5;grammaticalCase++) {
                // Origin-dependent -ов/-ин instrumental is not newly inferred for unfamiliar given names.
                if (!lexicalGiven && gender==Gender.MALE && grammaticalCase==4 && p[0].matches(".*(?:ов|ев|ин|ын)$")) continue;
                String family=surname(p[0],gender,grammaticalCase);
                String first=given(p[1],gender,grammaticalCase);
                String middle=patronymic(p[2],gender,grammaticalCase);
                if(family!=null && first!=null && middle!=null && value.equals(family+" "+first+" "+middle)) return true;
            }
        }
        return false;
    }

    public static List<String> hypotheses(String surface) {
        String ordered=surnameFirst(key(surface));
        String[] p=ordered.split(" ");
        if(p.length!=3 || initials(surface) || !singleSupportedScript(ordered)) return List.of(key(surface));
        if(ordered.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.LATIN)) return List.of(ordered);
        var candidates=new java.util.LinkedHashSet<String>();
        for(Gender gender:Gender.values()) {
            List<String> first=inverseComponent(p[1],Part.GIVEN,gender), middle=inverseComponent(p[2],Part.PATRONYMIC,gender);
            var last=new java.util.LinkedHashSet<String>(); last.add(p[0]);
            for(String l:lookup(Part.SURNAME,gender,p[0])) last.add(l);
            for(String l:inverseSurname(p[0],gender)) last.add(l);
            for(String l:last) for(String f:first) for(String m:middle) {
                String name=l+" "+f+" "+m;
                if(matchesOrdered(ordered,name)) {
                    candidates.add(name);
                    // An unfamiliar invariant given name can also be an oblique Russian form.
                    // Keep uncertainty instead of inventing a unique canonical name for mixed cases.
                    if(f.equals(p[1]) && lookup(Part.GIVEN,gender,f).isEmpty() && !name.equals(ordered)) candidates.add(key(surface));
                }
            }
        }
        if(candidates.isEmpty()) candidates.add(key(surface));
        return candidates.stream().limit(16).toList();
    }

    /** Bounded inverse candidates are validated by the same forward paradigm and a shared case. */
    private static List<String> inverseComponent(String value,Part part,Gender gender) {
        var result=new java.util.LinkedHashSet<String>(lookup(part,gender,value));
        if(part==Part.GIVEN && !result.isEmpty()) return List.copyOf(result);
        result.add(value);
        if(part==Part.PATRONYMIC) {
            if(gender==Gender.MALE) for(String ending:List.of("а","у","ем","ом","е")) {
                if(value.endsWith(ending)) result.add(value.substring(0,value.length()-ending.length()));
            }
            else for(String ending:List.of("ы","е","у","ой")) {
                if(value.endsWith(ending)) result.add(value.substring(0,value.length()-ending.length())+"а");
            }
            return result.stream().filter(s->gender==Gender.MALE?s.endsWith("ич"):s.matches(".*(?:овна|евна|ична)$")).toList();
        }
        for(String ending:List.of("а","у","ом","е","я","ю","ем","и","ы","ой","ей")) {
            if(!value.endsWith(ending) || value.length()<=ending.length()+1) continue;
            String base=value.substring(0,value.length()-ending.length());
            if(gender==Gender.MALE) { result.add(base);result.add(base+"й");result.add(base+"ь"); }
            else { result.add(base+"а");result.add(base+"я"); }
        }
        return List.copyOf(result);
    }

    private static List<String> inverseSurname(String value,Gender gender) {
        if(value.contains("-")) {
            String[] parts=value.split("-"); if(parts.length!=2) return List.of();
            List<String> result=new ArrayList<>();
            for(String left:inverseSurname(parts[0],gender)) for(String right:inverseSurname(parts[1],gender)) result.add(left+"-"+right);
            return result;
        }
        var result=new java.util.LinkedHashSet<String>(); result.add(value);
        if(gender==Gender.MALE) {
            for(String ending:List.of("а","у","ом","е","я","ю","ем")) if(value.endsWith(ending) && value.length()>ending.length()+1) {
                String base=value.substring(0,value.length()-ending.length());
                result.add(base);
                if(List.of("я","ю","ем","е").contains(ending)) result.add(base+"ь");
            }
            result.addAll(lookup(Part.SURNAME,gender,value));
            for(String ending:List.of("а","у","ым","е")) if(value.endsWith(ending)) {
                String base=value.substring(0,value.length()-ending.length());
                if(base.matches(".+(?:ов|ев|ин|ын)")) result.add(base);
            }
            for(String ending:List.of("ого","ому","им","ом")) if(value.endsWith(ending)) {
                String base=value.substring(0,value.length()-ending.length());
                if(base.endsWith("ск") || base.endsWith("цк")) result.add(base+"ий");
            }
        } else {
            for(String ending:List.of("ой","у")) if(value.endsWith(ending)) {
                String base=value.substring(0,value.length()-ending.length());
                if(base.matches(".+(?:ов|ев|ин|ын)")) result.add(base+"а");
                if(base.endsWith("ск") || base.endsWith("цк")) result.add(base+"ая");
            }
        }
        return List.copyOf(result);
    }

    // Cases: GEN, DAT, ACC, INS, PREP. These are bounded paradigms of a known DB component.
    private static String given(String canonical,Gender gender,int c) {
        var lex=LEXICON.stream().filter(l->l.part==Part.GIVEN && l.gender==gender && l.canonical.equals(canonical)).findFirst();
        if (lex.isPresent()) {
            if(canonical.equals("илья")) return new String[]{"ильи","илье","илью","ильей","илье"}[c-1];
            if(canonical.equals("любовь")) return new String[]{"любови","любови","любовь","любовью","любови"}[c-1];
            int i=gender==Gender.MALE?new int[]{1,2,1,3,4}[c-1]:new int[]{1,2,3,4,2}[c-1];
            return lex.get().forms.size()>i?lex.get().forms.get(i):null;
        }
        // Forward generation only from the complete DB component, never recovery by suffix stripping.
        if (canonical.matches(".+[оеэиыую]$") || canonical.matches(".+[аеиоуыэюя]а$")) return canonical;
        if (gender==Gender.FEMALE) {
            if (canonical.endsWith("ия")) return canonical.substring(0,canonical.length()-1)+new String[]{"и","и","ю","ей","и"}[c-1];
            if (canonical.endsWith("я")) return canonical.substring(0,canonical.length()-1)+new String[]{"и","е","ю","ей","е"}[c-1];
            if (canonical.matches(".+[бвдзлмнпрстф]а$")) return canonical.substring(0,canonical.length()-1)+new String[]{"ы","е","у","ой","е"}[c-1];
            if (canonical.matches(".+[гкх]а$")) return canonical.substring(0,canonical.length()-1)+new String[]{"и","е","у","ой","е"}[c-1];
            if (canonical.matches(".+[бвгджзйклмнпрстфхцчшщь]$")) return canonical;
        } else {
            if (canonical.matches(".+[бвгджзклмнпрстфхцчшщ]ь$")) return canonical.substring(0,canonical.length()-1)+new String[]{"я","ю","я","ем","е"}[c-1];
            if (canonical.matches(".+[аеёиоуыэюя]й$")) {
                String prep=canonical.endsWith("ий")?"и":"е";
                return canonical.substring(0,canonical.length()-1)+new String[]{"я","ю","я","ем",prep}[c-1];
            }
            if (canonical.matches(".+[бвгдзклмнпрстфх]$") && !canonical.matches(".*(?:ел|ек|ок|ец)$"))
                return canonical+new String[]{"а","у","а","ом","е"}[c-1];
        }
        return null;
    }

    private static String patronymic(String value,Gender gender,int c) {
        if(gender==Gender.MALE && value.endsWith("ич")) {
            String instrumental=List.of("ильич","кузьмич","лукич","фомич").contains(value)?"ом":"ем";
            return value+new String[]{"а","у","а",instrumental,"е"}[c-1];
        }
        if(gender==Gender.FEMALE && (value.endsWith("овна") || value.endsWith("евна") || value.endsWith("ична"))) return value.substring(0,value.length()-1)+new String[]{"ы","е","у","ой","е"}[c-1];
        return null;
    }

    private static String surname(String value,Gender gender,int c) {
        if(gender==Gender.MALE && value.equals("заяц")) return "зайц"+new String[]{"а","у","а","ем","е"}[c-1];
        if(gender==Gender.MALE && value.endsWith("ь")) return value.substring(0,value.length()-1)+new String[]{"я","ю","я","ем","е"}[c-1];
        if(value.equals("дюма")) return value;
        if(value.equals("лев") && gender==Gender.MALE) return "льв"+new String[]{"а","у","а","ом","е"}[c-1];
        if(value.contains("-")) {
            String[] parts=value.split("-"); if(parts.length!=2) return null;
            String left=surname(parts[0],gender,c),right=surname(parts[1],gender,c);
            return left==null||right==null?null:left+"-"+right;
        }
        if(value.matches(".+(?:о|е|э|и|ы|у|ю|их|ых)$") || value.matches(".+[аеиоуыэюя]а$")
                || gender==Gender.FEMALE && value.matches(".+[бвгджзйклмнпрстфхцчшщь]$")
                    && !value.matches(".+(?:ой|ей|ый|ий)$")) return value;
        if(value.equals("ким") && gender==Gender.MALE) return value+new String[]{"а","у","а","ом","е"}[c-1];
        if(gender==Gender.MALE && value.matches(".+(?:ов|ев|ин|ын)$")) return value+new String[]{"а","у","а","ым","е"}[c-1];
        if(gender==Gender.FEMALE && value.matches(".+(?:ова|ева|ина|ына)$")) return value.substring(0,value.length()-1)+new String[]{"ой","ой","у","ой","ой"}[c-1];
        if(gender==Gender.MALE && (value.endsWith("ский") || value.endsWith("цкий"))) return value.substring(0,value.length()-2)+new String[]{"ого","ому","ого","им","ом"}[c-1];
        if(gender==Gender.FEMALE && (value.endsWith("ская") || value.endsWith("цкая"))) return value.substring(0,value.length()-2)+new String[]{"ой","ой","ую","ой","ой"}[c-1];
        // Fleeting vowels, soft signs and ambiguous adjective-like endings are intentionally excluded.
        if(gender==Gender.MALE && value.matches(".+[бвгдзклмнпрстфх]$") && !value.matches(".*(?:ел|ек|ок|ец)$")
                && !value.equals("лев")) return value+new String[]{"а","у","а","ом","е"}[c-1];
        return null;
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
