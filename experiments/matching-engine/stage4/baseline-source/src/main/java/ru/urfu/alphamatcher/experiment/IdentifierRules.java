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
    private static final Pattern SEPARATOR=Pattern.compile("(?iu) *(?:[,;/]|и(?!\\p{L})) *");
    private IdentifierRules() { }
    static void extract(String text,PurposeParser.Evidence out,ParsingBudget budget) {
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
                    if(separator.group().contains("/") && offset<text.length() && !text.substring(offset).matches("(?iu)^(?:ИНН|договор|ФИО).*")) out.issues.add("INVALID_INN_FORMAT: неподтверждённое продолжение списка");
                    break;
                }
            }
            if(first) out.issues.add("INVALID_INN_FORMAT: пустое значение");
        }
        var contracts=CONTRACT.matcher(text);
        while(contracts.find()) {
            budget.match(); int offset=contracts.end(); boolean first=true;
            while(offset<text.length()) {
                var marker=ITEM_MARKER.matcher(text).region(offset,text.length());
                if(marker.lookingAt()) offset=marker.end();
                if(offset>=text.length()) { out.issues.add("UNSUPPORTED_CONTRACT: пустой элемент списка"); break; }
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
                if(!number.matches(".*[0-9].*")) {
                    String upper=number.toUpperCase(java.util.Locale.ROOT);
                    boolean nextField=java.util.Set.of("ИНН","INN","ФИО","ООО","ПАО","АО","ИП","БЕЗ","ПЕРИОД","ОСНОВАНИЕ","ПЛАТЁЖ","ПЛАТЕЖ").contains(upper);
                    if(first || !nextField && number.matches("[A-ZА-ЯЁ]{1,16}")) out.issues.add("UNSUPPORTED_CONTRACT: "+number);
                    break;
                }
                budget.match(); first=false;
                boolean clauseColon=token.end()<text.length() && text.charAt(token.end())==':' && (token.end()+1==text.length() || Character.isWhitespace(text.charAt(token.end()+1)));
                if(token.end()<text.length() && !Character.isWhitespace(text.charAt(token.end())) && "\",;)]".indexOf(text.charAt(token.end()))<0 && !clauseColon) out.issues.add("UNSUPPORTED_CONTRACT: номер длиннее 128 символов или содержит неподдерживаемый символ");
                else out.numbers.add(PurposeParser.number(number));
                int end=token.end(); if(end<text.length() && text.charAt(end)=='"') end++;
                var separator=SEPARATOR.matcher(text).region(end,text.length()); if(!separator.lookingAt()) break;
                offset=separator.end();
            }
            if(first) out.issues.add("UNSUPPORTED_CONTRACT: нет подтверждённого номера");
        }
    }
}
