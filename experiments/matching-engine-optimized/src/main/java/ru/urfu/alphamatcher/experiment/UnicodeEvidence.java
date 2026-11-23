package ru.urfu.alphamatcher.experiment;

import java.util.regex.Pattern;

/** Detect confusable financial labels; never transliterate identifiers or organization names. */
final class UnicodeEvidence {
    private static final Pattern WORD=Pattern.compile("(?<!\\p{L})\\p{L}{2,16}(?!\\p{L})");
    private UnicodeEvidence() { }
    static void inspect(CharSequence text,PurposeParser.Evidence out,ParsingBudget budget) {
        var matcher=WORD.matcher(text);
        while(matcher.find()) {
            budget.check(); String word=matcher.group().toLowerCase(java.util.Locale.ROOT);
            boolean latin=false,cyrillic=false; StringBuilder lookalike=new StringBuilder();
            for(char c:word.toCharArray()) {
                latin|=Character.UnicodeScript.of(c)==Character.UnicodeScript.LATIN;
                cyrillic|=Character.UnicodeScript.of(c)==Character.UnicodeScript.CYRILLIC;
                lookalike.append(switch(c) { case 'a'->'а'; case 'c'->'с'; case 'e'->'е'; case 'h','n'->'н'; case 'o'->'о'; case 'p'->'р'; case 'x'->'х'; case 'k'->'к'; case 'm'->'м'; case 't'->'т'; case 'i'->'и'; default->c; });
            }
            String mask=lookalike.toString();
            if(latin&&cyrillic&&(mask.equals("инн")||mask.startsWith("договор")||mask.equals("фио"))) {
                budget.match(); out.issues.add("UNICODE_CONFUSABLE_LABEL: "+matcher.group());
            }
        }
    }
}
