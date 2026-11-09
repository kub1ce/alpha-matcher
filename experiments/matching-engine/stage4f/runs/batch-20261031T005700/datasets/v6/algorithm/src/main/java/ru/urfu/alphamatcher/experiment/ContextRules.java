package ru.urfu.alphamatcher.experiment;

import java.util.regex.Pattern;

final class ContextRules {
    static final String ROLE_LABELS="плательщик(?:а|у|ом|е)?|отправител[ьяюе]|получател[ьяюе]|бенефициар(?:а|у)?|выгодоприобретател[ьяюе]|в пользу|payer|sender|recipient|beneficiary";
    static final String NAME_MARKER="Ф\\.? *И\\.? *О\\.?|F\\.? *I\\.? *O\\.?|full +name";
    private static final Pattern ROLE=Pattern.compile("(?iu)("+ROLE_LABELS+")\\s*(?:[:=-]\\s*)?(?:(?:"+NAME_MARKER+")\\s*[:=-]?\\s*)?(?:ИП\\s*)?$");
    private static final Pattern ACTING=Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:за|от имени|по поручению)\\s*(?:ИП\\s*)?$");
    private static final Pattern FOR=Pattern.compile("(?iu)(?<!\\p{L})за +([\\p{L}-]{1,80})(?!\\p{L})");
    private static final Pattern NEGATED=Pattern.compile("(?iu)(?<!\\p{L})(?:не +(?:по +)?(?:договор|плательщик|получател|ИНН|выгодоприобретател)|без +(?:договора|ИНН))(?![a-z])");
    private static final java.util.Set<String> SERVICE=java.util.Set.of("услуги","услуг","поставку","оборудование","обучение","ремонт","товары","товар","аренду","период","связь","связи","электроэнергию","содержание","обслуживание","работы","работу","доступ","продукцию","перевозку");
    // Productive action-noun classes under an explicit payment governor, not a corpus noun list.
    // Unknown bare objects, proper names and acting/agency expressions remain unresolved/manual.
    private static final Pattern PAYMENT_GOVERNOR=Pattern.compile("(?iu)(?<!\\p{L})(?:оплата|оплату|плат[её]ж|расч[её]т|перечисление)(?!\\p{L})[^,;:]{0,48}$");
    private static final Pattern ACTION_OBJECT=Pattern.compile("[а-яё-]{3,70}(?:ание|ение|ирование|изацию|ацию|ировку|овку)");
    private ContextRules() { }
    static String role(CharSequence text,int start) {
        var matcher=ROLE.matcher(text.subSequence(Math.max(0,start-80),start));
        if(!matcher.find()) return "UNKNOWN";
        String value=matcher.group(1).toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("платель") || value.startsWith("отправ") || value.equals("payer") || value.equals("sender")?"PAYER"
                :value.startsWith("получ") || value.equals("в пользу") || value.equals("recipient")?"RECIPIENT":"BENEFICIARY";
    }
    static boolean acting(CharSequence text,int start) { return ACTING.matcher(text.subSequence(Math.max(0,start-60),start)).find(); }
    static void uncertainActors(CharSequence text,PurposeParser.Evidence out,ParsingBudget budget) {
        if(NEGATED.matcher(text).find()) out.issues.add("NEGATED_EVIDENCE: отрицание роли или идентификатора требует проверки");
        var matcher=FOR.matcher(text);
        while(matcher.find()) {
            budget.match(); String word=matcher.group(1),key=word.toLowerCase(java.util.Locale.ROOT);
            if(java.util.Set.of("организацию","предприятие","учреждение","должника","плательщика","получателя","представителя","доверителя","клиента").contains(key)) { out.third=true; continue; }
            if(SERVICE.contains(key)) continue;
            if(word.equals(key) && ACTION_OBJECT.matcher(key).matches()
                    && PAYMENT_GOVERNOR.matcher(text.subSequence(Math.max(0,matcher.start()-64),matcher.start())).find()) continue;
            if(Character.isUpperCase(word.charAt(0)) || key.matches(".+(?:ова?|ева?|ина?|ский|ского|скую)$") || java.util.Set.of("клиента","доверителя","представителя").contains(key)) out.third=true;
            else if(!out.third) out.issues.add("UNSUPPORTED_ACTOR_CONTEXT: не установлено, услуга это или третье лицо");
        }
    }
    static String suffixRole(String value) {
        if(value==null) return "UNKNOWN";
        String text=value.toLowerCase(java.util.Locale.ROOT);
        return text.startsWith("плат")?"PAYER":text.startsWith("получ")?"RECIPIENT":"BENEFICIARY";
    }
}
