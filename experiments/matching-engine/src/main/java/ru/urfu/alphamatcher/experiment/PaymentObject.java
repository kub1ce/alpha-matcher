package ru.urfu.alphamatcher.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded domain-semantic noun phrase proof. Unknown dependencies are retained, never skipped.
 * This is a small explicit Russian technical vocabulary, not a general morphological analyser.
 */
final class PaymentObject {
    enum Kind { PAYABLE_ACTION, UNKNOWN }
    record Analysis(Kind kind,int start,int end,String head,List<String> dependencies,String rule) { }
    private static final Pattern ACTION=Pattern.compile("(?:анализ|синтез|монтаж|демонтаж|контроль|аудит|надзор|осмотр|перепл[её]т|"
            +"(?:испытани|исследовани|обследовани|измерени|вычислени|освидетельствовани|тестировани)[ея]|"
            +"(?:экспертиз|поверк|проверк|сборк|настройк|наладк|регулировк)у)");
    // Semantic classes: materials, documents/information, equipment, structures and cargo.
    // Inflected words are admitted compositionally; no complete payment phrases or IDs occur here.
    private static final Pattern ARTIFACT=Pattern.compile("(?:материал(?:ы|а|ов)?|документ(?:ы|а|ов)?|документаци[яию]|"
            +"черт[её]ж(?:и|а|ей)|ограждени[еяй]|кабел(?:я|и|ей)|образц(?:ы|а|ов)|издели[еяй]|оборудовани[еяй]|"
            +"площадк[аиу]|стенд(?:а|ы|ов)?|проект(?:а|ы|ов)?|груз(?:а|ы|ов)?|насос(?:а|ы|ов)?|вес(?:ы|ов)|датчик(?:а|и|ов)?|"
            +"детал(?:ь|и|ей)|объект(?:а|ы|ов)?|прибор(?:а|ы|ов)?|блок(?:а|и|ов)?|модул[ьяией]+|установк[аиу]|сооружени[еяй]|здани[еяй]|"
            +"отч[её]т(?:а|ы|ов)?|отч[её]тност[ьи]|связ[ьи]|качеств[оа]|работ[ауы]?|параметр(?:а|ы|ов)?|"
            +"веществ(?:а|о)?|панел[ьи]|конструкци[яиюй]|соединени[еяй]|агрегат(?:а|ы|ов)?|участк(?:а|и|ов)|"
            +"архив(?:а|ы|ов)?|образ(?:а|ы|ов)?|имуществ[оа]|запас(?:а|ы|ов)?|парти[яиюй]|продукци[яию]|товар(?:а|ы|ов)?|"
            +"тар[аыу]|упаковк(?:а|и|у|ок)|контейнер(?:а|ы|ов)?|ящик(?:а|и|ов)?|короб(?:а|ы|ов)?|"
            +"доступ(?:а)?|поддержк[аиу]|подключени[ея]|сервис(?:а|ы|ов)?)");
    private static final Pattern ATTRIBUTE=Pattern.compile("(?:техническ|контрольн|промышленн|нов|опытн|архивн|финансов|металлическ|пластиков|"
            +"сварн|несущ|рабоч|регламентн|материальн|товарн|исходн|защитн|химическ|цифров|строительн|сигнальн|сенсорн|регистрационн|"
            +"измерительн|программны?)(?:ого|его|ой|ых|их|ий|ый|ая|ое|ую|ые|ие|ой|ым|ыми)");
    private static final Pattern FIELD=Pattern.compile("(?iu)(?:ИНН|INN|договор|дог\\.|д-р|дг\\.|contract|"+ContextRules.NAME_MARKER+"|"+ContextRules.ROLE_LABELS+"|"
            +"по договору|согласно договору|в рамках договора|на основании договора|номер договора|без НДС|НДС|от имени|по поручению|основание|сумма|дата|ООО|АО|ПАО|ИП)(?!\\p{L})");
    private static final Pattern FINANCIAL_SUFFIX=Pattern.compile("(?iu)(?:период +[0-9]{1,4}(?:[./-][0-9]{1,4}){0,2}|"
            +"(?:плат[её]ж|оплата|расч[её]т|перечисление) +(?:согласно|по) +(?:графику|плану|реестру|ведомости))(?=$|[,;.])");
    // Explicit limited domain vocabulary; inflected word types, never complete phrases.
    enum WordType { ACTION, ARTIFACT, ATTRIBUTE, UNKNOWN }
    private static final java.util.Map<String,WordType> VOCABULARY = vocabulary();
    private static java.util.Map<String,WordType> vocabulary() {
        var words=new java.util.HashMap<String,WordType>();
        for(String word:List.of("прокладку","съёмку","съемку","очистку"))words.put(word,WordType.ACTION);
        for(String word:List.of("панелей","устройства","устройств","устройству","системы","систем","систему",
                "коммуникации","коммуникаций","коммуникацию","предметы","предметов","предмета",
                "фильтры","фильтров","фильтра"))words.put(word,WordType.ARTIFACT);
        for(String stem:List.of("гидравлическ","подземн","научн","оптоволоконн","историческ","газов"))
            for(String ending:List.of("ого","ой","ых","их","ий","ый","ая","ое","ую","ые","ым","ыми"))
                words.put(stem+ending,WordType.ATTRIBUTE);
        return java.util.Map.copyOf(words);
    }
    static WordType type(String word) {
        WordType known=VOCABULARY.get(word);
        if(known!=null)return known;
        if(ACTION.matcher(word).matches())return WordType.ACTION;
        if(ARTIFACT.matcher(word).matches())return WordType.ARTIFACT;
        if(ATTRIBUTE.matcher(word).matches())return WordType.ATTRIBUTE;
        return WordType.UNKNOWN;
    }
    private PaymentObject() { }
    static Analysis analyse(CharSequence text,int start,boolean previousHeadProof,boolean paymentGovernor,ParsingBudget budget) {
        int limit=Math.min(text.length(),start+241),end=start;
        while(end<limit){
            char ch=text.charAt(end);
            if(ch==','){
                int next=end+1;while(next<text.length()&&text.charAt(next)==' ')next++;
                if(next<text.length()&&!FIELD.matcher(text).region(next,text.length()).lookingAt()
                        &&!FINANCIAL_SUFFIX.matcher(text).region(next,text.length()).lookingAt())
                    return unknown(start,next,"Unresolved comma continuation may be an actor/object");
                break;
            }
            if(ch=='.'||ch==';')break;
            if(ch=='?'||ch=='!'||ch==':'||ch=='['||ch==']'||ch=='('||ch==')')return unknown(start,end,"Unresolved phrase boundary");
            if(end>start && text.charAt(end-1)==' ' && FIELD.matcher(text).region(end,text.length()).lookingAt())break;
            end++;
        }
        if(end==limit && limit<text.length())return unknown(start,end,"Object fragment exceeds bounded evidence");
        String fragment=WindowedText.slice(text,start,end).strip();String[] words=fragment.split(" +");
        if(words.length==0||words.length>24)return unknown(start,end,"Object token budget");
        int headIndex=0;
        // A finite typed modifier can precede an action, not replace it or license an unknown noun.
        while(headIndex<words.length && type(words[headIndex])==WordType.ATTRIBUTE){budget.match();headIndex++;}
        if(headIndex==words.length)return unknown(start,end,"Modifier without action head");
        String head=words[headIndex];
        if(!head.equals(head.toLowerCase(java.util.Locale.ROOT)))return unknown(start,end,"Capitalized or unsupported head");
        if(!(headIndex==0&&previousHeadProof) && !(paymentGovernor&&type(head)==WordType.ACTION))return unknown(start,end,"Unclassified object head");
        var dependencies=new ArrayList<String>();boolean pendingAttribute=false;boolean noun=false;boolean coordinated=false;
        for(int i=headIndex+1;i<words.length;i++){
            budget.match();String word=words[i];dependencies.add(word);
            if(!word.equals(word.toLowerCase(java.util.Locale.ROOT)))return unknown(start,end,"Possible proper name in dependency");
            if(coordinated&&(type(word)==WordType.ACTION||ContextRules.coordinatedObjectHead(word))){noun=true;coordinated=false;continue;}
            if(type(word)==WordType.ARTIFACT){noun=true;pendingAttribute=false;coordinated=false;continue;}
            if(type(word)==WordType.ATTRIBUTE){pendingAttribute=true;continue;}
            if(word.equals("и")&&noun&&!pendingAttribute){noun=false;coordinated=true;continue;}
            return unknown(start,end,"Unknown dependent word: "+word);
        }
        if(pendingAttribute || words.length>headIndex+1&&!noun)return unknown(start,end,"Unfinished dependent phrase");
        return new Analysis(Kind.PAYABLE_ACTION,start,end,head,List.copyOf(dependencies),"Typed action/object head and complete inanimate dependency phrase");
    }
    private static Analysis unknown(int start,int end,String reason){return new Analysis(Kind.UNKNOWN,start,end,"",List.of(),reason);}
}
