package ru.urfu.alphamatcher.experiment;

import java.util.regex.Pattern;

final class IdentityRules {
    private static final String WORD="[а-яё]{1,40}(?:-[а-яё]{1,40}){0,2}";
    private static final Pattern FULL=Pattern.compile("(?iu)(?<![\\p{L}-])(?=("+WORD+" "+WORD+" "+WORD+")(?![\\p{L}-]))");
    private static final Pattern LABEL_MARKER=Pattern.compile("(?iu)(?<!\\p{L})(?:"+ContextRules.NAME_MARKER+")(?!\\p{L}) *[:=-]? *");
    private static final Pattern ENTREPRENEUR=Pattern.compile("(?iu)(?<!\\p{L})(?:ИП|индивидуальный предприниматель)(?!\\p{L}) *(?:Ф\\.? *И\\.? *О\\.?(?!\\p{L}) *[:=-]? *)?");
    private static final Pattern INITIALS=Pattern.compile("(?iu)(?<![\\p{L}-])("+WORD+") +([а-яё])\\. *([а-яё])\\.");
    private static final Pattern INITIALS_FIRST=Pattern.compile("(?iu)(?<![\\p{L}-])([а-яё])\\. *([а-яё])\\. +("+WORD+")(?![\\p{L}-])");
    private static final Pattern SERVICE_NAME=Pattern.compile("(?iu)(?:имени|поручению|за|для|по|от|в|на|как|из|о|об|не)");
    private static final Pattern PATRONYMIC=Pattern.compile("(?iu).*(?:ич(?:а|у|ем|ом|е)?|(?:овн|евн|ичн)(?:а|ы|е|у|ой))$");
    static final Pattern ORGANIZATION=Pattern.compile("(?iu)(?<!\\p{L})(?:общество с ограниченной ответственностью|публичное акционерное общество|акционерное общество|ООО|ПАО|ОАО|ЗАО|АО)(?!\\p{L}) *");
    private static final Pattern STOP=Pattern.compile("(?iu)(?:[,;]| +(?=(?:ИНН|ФИО|договор|дог\\.|по договору|от имени|по поручению)(?!\\p{L})))");
    private static final Pattern THIRD=Pattern.compile("(?iu)(?<!\\p{L})(?:за +треть(?:е|его|ю) +лиц[оа]|в пользу +третьего +лица|от имени|по поручению|за +ИП)(?!\\p{L})");
    private static final Pattern ACTOR_LABEL=Pattern.compile("(?iu)(?<!\\p{L})(?:"+ContextRules.ROLE_LABELS+")(?!\\p{L}) *[:=-]? *");
    private static final Pattern ACTOR_NON_NAME=Pattern.compile("(?iu)(?:ИНН|INN|"+ContextRules.NAME_MARKER+"|ИП|ООО|АО|ПАО|ОАО|ЗАО|общество|акционерное|индивидуальный)(?!\\p{L})");
    private static final Pattern ACTOR_WORDS=Pattern.compile("[\\p{L}\\p{M}]+(?:[-'’][\\p{L}\\p{M}]+)*(?: +[\\p{L}\\p{M}]+(?:[-'’][\\p{L}\\p{M}]+)*){0,3}");
    private static final Pattern ACTOR_STOP=Pattern.compile("(?iu)[,;:!?，；：。！？]|\\.(?= |$)| +(?=(?:ИНН|INN|договор|contract|"+ContextRules.NAME_MARKER+"|"+ContextRules.ROLE_LABELS+"|по договору|от имени|по поручению|оплата|плат[её]ж|расч[её]т|перечисление|payment|purpose|amount)(?!\\p{L}))| +(?=[\\p{L}]{2,32} *[:=])");
    private static final Pattern INN_ROLE_SUFFIX=Pattern.compile("(?iu)(?:ИНН|INN) *[:=-]? *$");
    private static final Pattern META_PREFIX=Pattern.compile("(?iu)(?:сведения|информация|данные) +(?:о|об) +$");
    private static final Pattern META_PREDICATE=Pattern.compile("(?iu)(?:требуют +уточнения|нуждаются +в +уточнении|уточняются|уточняется|отсутствуют|отсутствует|не +указаны|не +указана)(?=$|[.;,])");
    /** One index per logical parse, rather than one full scan per entity. */
    static final class OrganizationIndex {
        final int[] starts,ends,quotedEnds;
        OrganizationIndex(java.util.List<int[]> spans){starts=new int[spans.size()];ends=new int[spans.size()];quotedEnds=new int[spans.size()];int all=0,quoted=0;for(int i=0;i<spans.size();i++){int[] r=spans.get(i);starts[i]=r[0];all=Math.max(all,r[1]);if(r[2]!=0)quoted=Math.max(quoted,r[1]);ends[i]=all;quotedEnds[i]=quoted;}}
        boolean contains(int position,boolean quoted){int left=0,right=starts.length;while(left<right){int mid=(left+right)>>>1;if(starts[mid]<=position)left=mid+1;else right=mid;}return left>0&&(quoted?quotedEnds[left-1]:ends[left-1])>position;}
    }
    private static OrganizationIndex organizationIndex(CharSequence text,PurposeParser.Evidence out,ParsingBudget budget){
        if(out.scopeSource==text||out.scopeSource!=null&&out.scopeSource.equals(text))return out.organizationIndex;
        var spans=new java.util.ArrayList<int[]>();var org=ORGANIZATION.matcher(text);
        while(org.find()){budget.check();int start=org.end(),end;boolean quoted=start<text.length()&&text.charAt(start)=='"';
            if(quoted){end=WindowedText.indexOf(text,'"',start+1);if(end<0)end=text.length();else end++;}
            else {int limit=Math.min(text.length(),start+161);var stop=STOP.matcher(text).region(start,limit);end=stop.find()?stop.start():limit;}
            spans.add(new int[]{start,end,quoted?1:0});
        }
        out.scopeSource=text;out.organizationIndex=new OrganizationIndex(spans);return out.organizationIndex;
    }
    static boolean insideOrganizationName(CharSequence text,int position,ParsingBudget budget,PurposeParser.Evidence out){return organizationIndex(text,out,budget).contains(position,false);}
    static boolean insideQuotedOrganizationName(CharSequence text,int position,ParsingBudget budget,PurposeParser.Evidence out){return organizationIndex(text,out,budget).contains(position,true);}
    private IdentityRules() { }
    static void extract(CharSequence text,PurposeParser.Evidence out,ParsingBudget budget) {
        out.third=THIRD.matcher(text).find();
        var explicitRanges=new java.util.ArrayList<int[]>();
        var markers=LABEL_MARKER.matcher(text);
        while(markers.find()){budget.match();if(insideQuotedOrganizationName(text,markers.start(),budget,out))continue;observeExplicit(text,markers.end(),out,explicitRanges,true);}
        var actors=ACTOR_LABEL.matcher(text);
        while(actors.find()){
            budget.match();if(insideQuotedOrganizationName(text,actors.start(),budget,out))continue;int start=actors.end();
            // Only a complete metadata predicate is non-person prose. Never skip an
            // unknown/partial actor merely because the sentence starts with "Сведения".
            if(META_PREFIX.matcher(text.subSequence(Math.max(0,actors.start()-40),actors.start())).find()
                    && META_PREDICATE.matcher(text).region(start,text.length()).lookingAt())continue;
            // A role suffix of an INN field is not a separate name field.
            if(INN_ROLE_SUFFIX.matcher(text.subSequence(Math.max(0,actors.start()-16),actors.start())).find())continue;
            if(start<text.length() && ACTOR_NON_NAME.matcher(text).region(start,text.length()).lookingAt())continue;
            observeExplicit(text,start,out,explicitRanges,false);
        }
        var names=FULL.matcher(text);
        while(names.find()) {
            budget.check(); String value=names.group(1);
            if(explicitRanges.stream().anyMatch(r->names.start(1)<r[1] && names.end(1)>r[0])) continue;
            String last=value.substring(value.lastIndexOf(' ')+1);
            if(!PATRONYMIC.matcher(last).matches()) continue;
            if(insideQuotedOrganizationName(text,names.start(1),budget,out))continue;
            // A code-like service footer is unresolved evidence, not a silently absent person.
            // Capitalization alone must never discard a genuine conflicting full name.
            if(last.equals(last.toUpperCase(java.util.Locale.ROOT))
                    && value.matches("(?iu)^(?:служебная|сопроводительная|техническая) +(?:пометка|отметка|запись) +.*")) {
                out.issues.add("UNSUPPORTED_NAME_FORMAT: metadata-or-person ambiguity; untyped service span may contain a participant; explicit field typing required");
                continue;
            }
            budget.match(); addName(text,value,names.start(),out);
        }
        var initials=INITIALS.matcher(text);
        while(initials.find()) {
            budget.match(); String value=initials.group(1)+" "+initials.group(2)+"."+initials.group(3)+".";
            if(SERVICE_NAME.matcher(initials.group(1)).matches()||insideQuotedOrganizationName(text,initials.start(),budget,out))continue;
            addName(text,value,initials.start(),out);
            out.issues.add("INCOMPLETE_NAME: инициалы не подтверждают полное ФИО");
        }
        var beforeSurname=INITIALS_FIRST.matcher(text);
        while(beforeSurname.find()) { budget.match();if(insideQuotedOrganizationName(text,beforeSurname.start(),budget,out))continue;String value=beforeSurname.group(1)+"."+beforeSurname.group(2)+". "+beforeSurname.group(3);addName(text,value,beforeSurname.start(),out);out.issues.add("INCOMPLETE_NAME: initials before surname do not establish full identity"); }
        var entrepreneurs=ENTREPRENEUR.matcher(text);
        while(entrepreneurs.find()) {
            budget.match();if(insideQuotedOrganizationName(text,entrepreneurs.start(),budget,out))continue;
            if(Pattern.compile("(?iu)как +$").matcher(text.subSequence(Math.max(0,entrepreneurs.start()-20),entrepreneurs.start())).find()) {
                out.issues.add("UNSUPPORTED_ACTOR_CONTEXT: entrepreneur capacity is not a person identity at normalized ["+entrepreneurs.start()+","+entrepreneurs.end()+")");continue;
            }
            if(!out.nameStarts.contains(entrepreneurs.end())) out.issues.add("UNSUPPORTED_NAME_FORMAT: имя ИП не подтверждено полным ФИО");
        }
        var org=ORGANIZATION.matcher(text);
        while(org.find()) {
            budget.match(); int start=org.end(),end;
            if(start==text.length()) { out.issues.add("UNSUPPORTED_ORGANIZATION: нет названия"); continue; }
            if(text.charAt(start)=='"') {
                end=WindowedText.indexOf(text,'"',start+1);
                if(end<0 || end-start>256) { out.issues.add("UNSUPPORTED_ORGANIZATION: незакрытые кавычки/слишком длинное название"); continue; }
                end++;
            } else {
                var stop=STOP.matcher(text).region(start,text.length()); end=stop.find()?stop.start():text.length();
                if(end-start>160 || WindowedText.slice(text,start,end).isBlank()) { out.issues.add("UNSUPPORTED_ORGANIZATION"); continue; }
            }
            String value=NameNormalizer.organizationKey(WindowedText.slice(text,org.start(),end)); out.organizations.add(value);
            String role=ContextRules.role(text,org.start()); if(!role.equals("UNKNOWN")) out.claims.add(new PurposeParser.RoleClaim("ORGANIZATION",value,role));
            out.third|=ContextRules.acting(text,org.start());
        }
    }
    private static void observeExplicit(CharSequence text,int start,PurposeParser.Evidence out,java.util.List<int[]> ranges,boolean typedName){
        if(start>=text.length()){out.issues.add("UNSUPPORTED_NAME_FORMAT: empty explicit name field");return;}
        int end=Math.min(text.length(),start+240);var stop=ACTOR_STOP.matcher(text).region(start,end);boolean stopped=stop.find();if(stopped)end=stop.start();
        var words=ACTOR_WORDS.matcher(text).region(start,end);
        if(!words.lookingAt()){out.issues.add("UNSUPPORTED_NAME_FORMAT: explicit name value not extracted");return;}
        String value=words.group();
        if(words.end()<text.length() && text.charAt(words.end())=='.' && value.matches("(?iu).+ [а-яё]")){out.issues.add("INCOMPLETE_NAME: initials do not establish a complete name");return;}
        if(ranges.stream().anyMatch(r->start<r[1]&&words.end()>r[0]))return;
        boolean tail=!WindowedText.slice(text,words.end(),end).isBlank();
        if(value.split(" ").length>3 || tail)value=WindowedText.slice(text,start,end).strip();
        ranges.add(new int[]{start,end});
        String[] components=value.split(" ");
        boolean personShape=java.util.Arrays.stream(components).anyMatch(w->PATRONYMIC.matcher(w).matches())
                || components.length==3 && (typedName || java.util.Arrays.stream(components).allMatch(w->Character.isUpperCase(w.codePointAt(0))));
        if(!personShape && typedName && components.length>3){
            // Preserve malformed explicitly typed evidence, without inventing a role-bearing person.
            out.nameStarts.add(start);out.names.put(NameNormalizer.key(value),NameNormalizer.resolve(value));
            out.issues.add("INCOMPLETE_NAME: unresolved explicit name components at normalized ["+start+","+end+")");return;
        }
        if(!personShape){
            out.issues.add((Character.isUpperCase(value.codePointAt(0))?"UNSUPPORTED_NAME_FORMAT":"UNSUPPORTED_ACTOR_CONTEXT")
                    +": unqualified actor span normalized ["+start+","+end+"): "+value);return;
        }
        addName(text,value,start,out);
        if(tail)out.issues.add("UNSUPPORTED_NAME_FORMAT: unprocessed explicit field tail");
        if(value.split(" ").length!=3)out.issues.add("INCOMPLETE_NAME: unresolved number of explicit name components");
        if(!stopped && end==start+240 && end<text.length())out.issues.add("UNSUPPORTED_NAME_FORMAT: bounded explicit observation incomplete");
    }
    private static void addName(CharSequence text,String value,int start,PurposeParser.Evidence out) {
        out.nameStarts.add(start);
        if(!NameNormalizer.singleSupportedScript(NameNormalizer.key(value))) out.issues.add("UNSUPPORTED_NAME_FORMAT: mixed scripts in full name");
        // Distinct observations must not overwrite each other merely because their hypotheses coincide.
        var mention=NameNormalizer.resolve(value); out.names.put(NameNormalizer.key(value),mention);
        String role=ContextRules.role(text,start); if(!role.equals("UNKNOWN")) out.claims.add(new PurposeParser.RoleClaim("NAME",value,role));
        out.third|=ContextRules.acting(text,start);
    }
}
