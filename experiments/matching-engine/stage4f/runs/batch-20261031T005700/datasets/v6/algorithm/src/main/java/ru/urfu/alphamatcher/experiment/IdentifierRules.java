package ru.urfu.alphamatcher.experiment;

import java.util.regex.Pattern;

final class IdentifierRules {
    private static final Pattern INN=Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:ИНН(?:ы)?|INN)(?: +(?:плательщика|получателя|выгодоприобретателя|бенефициара))? *[:№#=-]? *");
    private static final Pattern INN_SUFFIX=Pattern.compile("(?iu)ИНН(?:ы)? +(плательщика|получателя|выгодоприобретателя|бенефициара)");
    private static final Pattern DIGITS=Pattern.compile("[0-9][0-9 .-]{0,32}");
    private static final Pattern CONTRACT=Pattern.compile("(?iu)(?<!\\p{L})(?:договор(?:а|у|ом|е|ы|ов|ам|ами|ах)?|дог\\.?|д-р(?:а|у)?|дг\\.?|contract)(?!\\p{L}) *(?:[:№#] *|N(?= +) *)*");
    private static final Pattern ITEM_MARKER=Pattern.compile("(?iu)(?:[№#]|N(?= +)) *");
    private static final Pattern PAREN_FIELD=Pattern.compile("(?iu)(?:\\(|\\[) *(?:ИНН|INN|ФИО|ООО|ПАО|АО|ИП)(?!\\p{L})");
    private static final Pattern NUMBER=Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}./_-]{0,127}");
    private static final Pattern SEPARATOR=Pattern.compile("(?iu) *(?:[,;，；]+|/|и(?!\\p{L})) *");
    private static final Pattern ALPHABETIC=Pattern.compile("\\p{L}+(?:[/._-]\\p{L}+)*");
    private static final Pattern PLURAL=Pattern.compile("(?iu)договор(?:ы|ов|ам|ами|ах)(?!\\p{L})");
    private static final java.util.Set<String> NEXT_FIELD=java.util.Set.of("ИНН","INN","ФИО","ООО","ПАО","АО","ИП","БЕЗ","ПЕРИОД","ОСНОВАНИЕ","ПЛАТЁЖ","ПЛАТЕЖ","ПЛАТЕЛЬЩИК","ПОЛУЧАТЕЛЬ","БЕНЕФИЦИАР","ОТ","НА","ПО","ЗА","СУММА");
    private static final java.util.Set<String> PROSE=java.util.Set.of("ПОСТАВКИ","АРЕНДЫ","ЗАЙМА","УСЛУГ","ОКАЗАНИЯ","СТРАХОВАНИЯ","ОБСЛУЖИВАНИЯ","КУПЛИ-ПРОДАЖИ","ОТСУТСТВУЕТ","НЕИЗВЕСТЕН");
    private IdentifierRules() { }
    static void extract(CharSequence text,PurposeParser.Evidence out,ParsingBudget budget) {
        var inns=INN.matcher(text);
        while(inns.find()) {
            budget.match(); String role=ContextRules.role(text,inns.start());
            String prefixRole=role;
            var suffix=INN_SUFFIX.matcher(inns.group()); if(suffix.find()) role=ContextRules.suffixRole(suffix.group(1));
            boolean roleConflict=!prefixRole.equals("UNKNOWN") && !role.equals("UNKNOWN") && !prefixRole.equals(role);
            if(roleConflict) out.issues.add("CONFLICTING_ROLE_CLAIMS: роли до и после метки ИНН противоречат друг другу");
            int offset=inns.end(); boolean first=true;
            while(offset<text.length()) {
                var token=DIGITS.matcher(text).region(offset,text.length());
                if(!token.lookingAt()) { if(first) out.issues.add("INVALID_INN_FORMAT: после метки нет ИНН"); break; }
                budget.match(); first=false; String value=token.group().replaceAll("[^0-9]", "");
                (Inn.valid(value)?out.inns:out.invalid).add(value);
                if(!role.equals("UNKNOWN")) out.claims.add(new PurposeParser.RoleClaim("INN",value,role));
                if(roleConflict) out.claims.add(new PurposeParser.RoleClaim("INN",value,prefixRole));
                if(token.end()<text.length() && Character.isLetterOrDigit(text.charAt(token.end())) && !Character.isWhitespace(text.charAt(token.end()-1))) out.issues.add("INVALID_INN_FORMAT: недопустимое продолжение ИНН");
                if(token.end()<text.length() && !Character.isWhitespace(text.charAt(token.end()-1)) && ",;/)]\"".indexOf(text.charAt(token.end()))<0 && !Character.isLetterOrDigit(text.charAt(token.end()))) out.issues.add("INVALID_INN_FORMAT: неподдерживаемый разделитель внутри реквизита");
                var separator=SEPARATOR.matcher(text).region(token.end(),text.length()); if(!separator.lookingAt()) break;
                offset=separator.end();
                if(offset>=text.length() || !Character.isDigit(text.charAt(offset))) {
                    if(separator.group().contains("/") && offset<text.length() && !Pattern.matches("(?iu)^(?:ИНН|договор|ФИО).*",text.subSequence(offset,text.length()))) out.issues.add("INVALID_INN_FORMAT: неподтверждённое продолжение списка");
                    break;
                }
            }
            if(first) out.issues.add("INVALID_INN_FORMAT: пустое значение");
        }
        var contracts=CONTRACT.matcher(text);
        while(contracts.find()) {
            budget.match(); int offset=contracts.end(); boolean first=true;int referencesBefore=out.references.size();
            boolean explicitlyNumbered=ITEM_MARKER.matcher(contracts.group()).find();
            boolean explicitList=PLURAL.matcher(contracts.group()).find(),alphabeticSeen=false;
            while(offset<text.length()) {
                var marker=ITEM_MARKER.matcher(text).region(offset,text.length());
                if(marker.lookingAt()) offset=marker.end();
                if(offset>=text.length()) { out.issues.add("UNSUPPORTED_CONTRACT: пустой элемент списка"); break; }
                if(!first && !marker.lookingAt() && CONTRACT.matcher(text).region(offset,text.length()).lookingAt()) break;
                if(!first && !marker.lookingAt() && PAREN_FIELD.matcher(text).region(offset,text.length()).lookingAt()) break;
                char wrapper=0;
                if(text.charAt(offset)=='(' || text.charAt(offset)=='['){wrapper=text.charAt(offset++);while(offset<text.length()&&text.charAt(offset)==' ')offset++;}
                if(offset==text.length()){out.issues.add("UNSUPPORTED_CONTRACT: пустая группа номера");break;}
                boolean quoted=text.charAt(offset)=='"';
                if(quoted) offset++;
                while(offset<text.length() && text.charAt(offset)==' ') offset++;
                var token=NUMBER.matcher(text).region(offset,text.length());
                if(!token.lookingAt()) {
                    // A parenthesized next field is parsed independently, not a malformed second number.
                    if(!first && !marker.lookingAt() && !quoted && PAREN_FIELD.matcher(text).region(offset,text.length()).lookingAt()) break;
                    out.issues.add("UNSUPPORTED_CONTRACT: неподдерживаемый элемент списка номеров"); break;
                }
                String number=token.group();
                String validationNumber=number.endsWith(".")?ContractBoundaries.syntaxCore(number):number;
                if(!number.matches(".*[0-9].*")) {
                    String upper=number.toUpperCase(java.util.Locale.ROOT);
                    if(!first && NEXT_FIELD.contains(upper) && !marker.lookingAt() && !quoted) break;
                    boolean strongMarker=first&&explicitlyNumbered||marker.lookingAt()||quoted;
                    boolean inheritedMarker=explicitlyNumbered&&number.equals(upper);
                    if(!alphabetic(validationNumber,strongMarker||inheritedMarker)) {
                        // An unmarked word is not an identifier. An ambiguous second item must block SAFE.
                        boolean identifierLike=number.equals(upper)||number.indexOf('/')>=0||number.indexOf('-')>=0||number.indexOf('.')>=0||number.indexOf('_')>=0;
                        if(first || strongMarker || inheritedMarker || explicitList || alphabeticSeen || identifierLike) out.issues.add("UNSUPPORTED_CONTRACT: "+number);
                        break;
                    }
                    alphabeticSeen=true;
                }
                budget.match(); first=false;
                if(quoted && (token.end()==text.length() || text.charAt(token.end())!='"')) out.issues.add("UNSUPPORTED_CONTRACT: незакрытые кавычки номера");
                else if(!ContractBoundaries.supportedTail(text,token.end(),quoted,number)) out.issues.add("UNSUPPORTED_CONTRACT: номер длиннее 128 символов или неподтверждённая граница");
                else {
                    String observed=PurposeParser.number(number);
                    out.numbers.add(observed);
                    var reference=ContractBoundaries.reference(text,token.start(),token.end(),quoted);
                    out.references.add(reference);
                    if(reference.completeness()!=PurposeParser.Completeness.COMPLETE)out.issues.add("MATCH_LIMIT: incomplete bounded contract interpretations");
                }
                int quotedEnd=token.end()+(quoted?1:0);
                if(wrapper!=0 && (quotedEnd>=text.length() || text.charAt(quotedEnd)!=(wrapper=='('?')':']')))
                    out.issues.add("UNSUPPORTED_CONTRACT: незакрытая группа номера");
                int end=ContractBoundaries.afterQuotedOrGrouped(text,token.end(),quoted,wrapper);
                var separator=SEPARATOR.matcher(text).region(end,text.length());
                if(!separator.lookingAt()) {
                    if(end<text.length() && text.charAt(end)==' ') {
                        if(ITEM_MARKER.matcher(text).region(end+1,text.length()).lookingAt())
                            out.issues.add("UNSUPPORTED_CONTRACT: adjacent explicit number marker without a list boundary");
                        var adjacent=NUMBER.matcher(text).region(end+1,text.length());
                        if(adjacent.lookingAt() && (alphabetic(adjacent.group(),false)||adjacent.group().matches(".*[0-9].*")
                                || explicitList && adjacent.group().equals(adjacent.group().toUpperCase(java.util.Locale.ROOT))
                                    && !NEXT_FIELD.contains(adjacent.group()) && alphabetic(adjacent.group(),true)))
                            out.issues.add("UNSUPPORTED_CONTRACT: между возможными номерами нет разделителя");
                    }
                    break;
                }
                offset=separator.end();
                if(offset==text.length())break; // terminal field punctuation, not a discarded second reference
            }
            if(explicitList && out.references.size()==referencesBefore+1){
                var only=out.references.get(referencesBefore);
                if(!only.quoted() && only.surface().contains("/"))out.issues.add("UNSUPPORTED_CONTRACT: compact plural slash notation has unresolved scalar/list interpretations");
            }
            if(first) out.issues.add("UNSUPPORTED_CONTRACT: нет подтверждённого номера");
        }
    }
    private static boolean alphabetic(String value,boolean marked) {
        String upper=value.toUpperCase(java.util.Locale.ROOT);
        if(NEXT_FIELD.contains(upper)||PROSE.contains(upper)||!ALPHABETIC.matcher(value).matches()) return false;
        // New letter-only identifiers use one script, no transliteration or homoglyph folding.
        Character.UnicodeScript script=null;
        for(int c:value.codePoints().toArray()) if(Character.isLetter(c)) {
            var next=Character.UnicodeScript.of(c);
            if(next!=Character.UnicodeScript.LATIN && next!=Character.UnicodeScript.CYRILLIC || script!=null&&next!=script) return false;
            script=next;
        }
        boolean structured=value.indexOf('/')>=0||value.indexOf('-')>=0||value.indexOf('.')>=0||value.indexOf('_')>=0;
        return (marked || structured) && (marked || value.equals(upper));
    }
}
