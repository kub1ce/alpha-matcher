package ru.urfu.alphamatcher.experiment;

import java.util.regex.Pattern;

final class IdentityRules {
    private static final String WORD="[а-яё]{1,40}(?:-[а-яё]{1,40}){0,2}";
    private static final String LABELED_WORD="[\\p{IsLatin}а-яё]{1,40}(?:[-'’][\\p{IsLatin}а-яё]{1,40}){0,2}";
    private static final Pattern FULL=Pattern.compile("(?iu)(?<![\\p{L}-])(?=("+WORD+" "+WORD+" "+WORD+")(?![\\p{L}-]))");
    private static final Pattern LABEL=Pattern.compile("(?iu)(?<!\\p{L})Ф\\.? *И\\.? *О\\.?(?!\\p{L}) *[:=-]? *("+LABELED_WORD+"(?: "+LABELED_WORD+"){0,2})(?![\\p{L}'’-])");
    private static final Pattern LABEL_MARKER=Pattern.compile("(?iu)(?<!\\p{L})Ф\\.? *И\\.? *О\\.?(?!\\p{L}) *[:=-]? *");
    private static final Pattern ENTREPRENEUR=Pattern.compile("(?iu)(?<!\\p{L})(?:ИП|индивидуальный предприниматель)(?!\\p{L}) *(?:Ф\\.? *И\\.? *О\\.?(?!\\p{L}) *[:=-]? *)?");
    private static final Pattern INITIALS=Pattern.compile("(?iu)(?<![\\p{L}-])("+WORD+") +([а-яё])\\. *([а-яё])\\.");
    private static final Pattern PATRONYMIC=Pattern.compile("(?iu).*(?:ич(?:а|у|ем|ом|е)?|(?:овн|евн|ичн)(?:а|ы|е|у|ой))$");
    private static final Pattern ORGANIZATION=Pattern.compile("(?iu)(?<!\\p{L})(?:общество с ограниченной ответственностью|публичное акционерное общество|акционерное общество|ООО|ПАО|ОАО|ЗАО|АО)(?!\\p{L}) *");
    private static final Pattern STOP=Pattern.compile("(?iu)(?:[,;]| +(?=(?:ИНН|ФИО|договор|дог\\.|по договору|от имени|по поручению)(?!\\p{L})))");
    private static final Pattern THIRD=Pattern.compile("(?iu)(?<!\\p{L})(?:за +треть(?:е|его|ю) +лиц[оа]|в пользу +третьего +лица|от имени|по поручению|за +ИП)(?!\\p{L})");
    private IdentityRules() { }
    static void extract(String text,PurposeParser.Evidence out,ParsingBudget budget) {
        out.third=THIRD.matcher(text).find(); var explicit=LABEL.matcher(text);
        var explicitStarts=new java.util.HashSet<Integer>();
        var explicitRanges=new java.util.ArrayList<int[]>();
        while(explicit.find()) {
            budget.match(); explicitStarts.add(explicit.start());
            explicitRanges.add(new int[]{explicit.start(1),explicit.end(1)});
            if(explicit.group(1).split(" ").length<3 && explicit.end(1)<text.length() && text.charAt(explicit.end(1))=='.') { out.issues.add("INCOMPLETE_NAME"); continue; }
            addName(text,explicit.group(1),explicit.start(1),out);
            if(explicit.group(1).split(" ").length!=3) out.issues.add("INCOMPLETE_NAME");
        }
        var names=FULL.matcher(text);
        while(names.find()) {
            budget.check(); String value=names.group(1);
            if(explicitRanges.stream().anyMatch(r->names.start(1)<r[1] && names.end(1)>r[0])) continue;
            if(!PATRONYMIC.matcher(value.substring(value.lastIndexOf(' ')+1)).matches()) continue;
            budget.match(); addName(text,value,names.start(),out);
        }
        var initials=INITIALS.matcher(text);
        while(initials.find()) {
            budget.match(); String value=initials.group(1)+" "+initials.group(2)+"."+initials.group(3)+".";
            out.names.put(NameNormalizer.key(value),new NameNormalizer.Mention(value,NameNormalizer.key(value),false));
            out.issues.add("INCOMPLETE_NAME: инициалы не подтверждают полное ФИО");
        }
        var markers=LABEL_MARKER.matcher(text);
        while(markers.find()) {
            budget.match();
            if(!explicitStarts.contains(markers.start())) out.issues.add("UNSUPPORTED_NAME_FORMAT: метка ФИО есть, значение не извлечено");
        }
        var entrepreneurs=ENTREPRENEUR.matcher(text);
        while(entrepreneurs.find()) {
            budget.match();
            if(!out.nameStarts.contains(entrepreneurs.end())) out.issues.add("UNSUPPORTED_NAME_FORMAT: имя ИП не подтверждено полным ФИО");
        }
        var org=ORGANIZATION.matcher(text);
        while(org.find()) {
            budget.match(); int start=org.end(),end;
            if(start==text.length()) { out.issues.add("UNSUPPORTED_ORGANIZATION: нет названия"); continue; }
            if(text.charAt(start)=='"') {
                end=text.indexOf('"',start+1);
                if(end<0 || end-start>256) { out.issues.add("UNSUPPORTED_ORGANIZATION: незакрытые кавычки/слишком длинное название"); continue; }
                end++;
            } else {
                var stop=STOP.matcher(text).region(start,text.length()); end=stop.find()?stop.start():text.length();
                if(end-start>160 || text.substring(start,end).isBlank()) { out.issues.add("UNSUPPORTED_ORGANIZATION"); continue; }
            }
            String value=NameNormalizer.organizationKey(text.substring(org.start(),end)); out.organizations.add(value);
            String role=ContextRules.role(text,org.start()); if(!role.equals("UNKNOWN")) out.claims.add(new PurposeParser.RoleClaim("ORGANIZATION",value,role));
            out.third|=ContextRules.acting(text,org.start());
        }
    }
    private static void addName(String text,String value,int start,PurposeParser.Evidence out) {
        out.nameStarts.add(start);
        if(!NameNormalizer.singleSupportedScript(NameNormalizer.key(value))) out.issues.add("UNSUPPORTED_NAME_FORMAT: mixed scripts in full name");
        // Distinct observations must not overwrite each other merely because their hypotheses coincide.
        var mention=NameNormalizer.resolve(value); out.names.put(NameNormalizer.key(value),mention);
        String role=ContextRules.role(text,start); if(!role.equals("UNKNOWN")) out.claims.add(new PurposeParser.RoleClaim("NAME",value,role));
        out.third|=ContextRules.acting(text,start);
    }
}
