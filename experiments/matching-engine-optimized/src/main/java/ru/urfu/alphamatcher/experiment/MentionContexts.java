package ru.urfu.alphamatcher.experiment;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded textual provenance. No mention or correction is a verified financial fact. */
public final class MentionContexts {
 public enum Context { ABSENT, AFFIRMED, NEGATED, CORRECTION, HISTORICAL, QUOTED, UNKNOWN }
 public record Mention(String kind,String surface,int start,int end,Context context,String coordinateSystem,boolean confirmedFact) { }
 private static final String TOKEN="[\\p{L}\\p{N}][\\p{L}\\p{N}./_-]{0,127}";
 private static final String ORG=IdentityRules.ORGANIZATION.pattern()+"\"[^\"]{1,160}\"";
 private static final String NON_BINDING_BEFORE="(?:не +касается +договор[а-яё]{0,4}|не +связан[а-яё]{0,3} +с +договор[а-яё]{0,4}"
  +"|(?:не +(?:оплачивать|считать +оплатой|направлен[а-яё]{0,3} +на +погашение)|кроме +суммы) +по +договор[а-яё]{0,4})";
 private static final String HISTORICAL_BEFORE="ранее +действовавш[а-яё]{1,3} +договор[а-яё]{0,4}";
 private static final String HISTORICAL_AFTER="не +действу(?:ет|ют)|утратил[а-яё]{0,3} +силу|аннулирован[а-яё]{0,3}|отмен[её]н[а-яё]{0,3}";
 private static final String NON_BINDING_AFTER="не +подлеж(?:ит|ат) +оплате|привед[её]н[а-яё]{0,3} +справочно";
 private static final Pattern HISTORICAL_SUFFIX=Pattern.compile("(?iu) *(?:"+HISTORICAL_AFTER+")(?!\\p{L})");
 private static final Pattern NON_BINDING_SUFFIX=Pattern.compile("(?iu) *(?:"+HISTORICAL_AFTER+"|"+NON_BINDING_AFTER+")(?!\\p{L})");
 private static final Pattern RISK=Pattern.compile("(?iu)(?<!\\p{L})(?:"+NON_BINDING_BEFORE+"|"+HISTORICAL_BEFORE+"|не +(?:является|относится|перечислять|отправител[ьяюе]|плательщик[ау]?|получател[ьяюе]|заключали|заключили|подписывали)|ошибоч[а-яё]{0,12}|корректн[а-яё]{0,8} +основан[а-яё]{0,8}|предыдущ[а-яё]{0,8} +номер|исключительно +для +идентификаци[а-яё]{0,4}|цитат[а-яё]{0,6}|отмен[её]нн[а-яё]{0,10}|расторгнут[а-яё]{0,10}|отношения +не +имеет|фактическ[а-яё]{0,8} +ИНН)(?!\\p{L})");
 private static final Pattern ID=Pattern.compile("(?iu)(?:[№#]|номер(?:ом)?) *("+TOKEN+")");
 private static final Pattern ORGS=Pattern.compile("(?iu)"+ORG);
 private static final Pattern ORG_INN=Pattern.compile("(?iu)"+ORG+" *(?:, *|с +)ИНН *[:=-]? *[0-9]{10}(?:[0-9]{2})?(?![0-9])");
 private static final Pattern OTHER_DOC=Pattern.compile("(?iu)(?<!\\p{L})(счет[а-яё]{0,3}|акт[а-яё]{0,3}|спецификаци[а-яё]{0,3}|накладн[а-яё]{0,5}|УПД|заявк[а-яё]{0,3}|дополнительн[а-яё]{0,5} +соглашени[а-яё]{0,3}) +[№#] *("+TOKEN+")");
 private static final Pattern DOMAIN_ROLE=Pattern.compile("(?iu)(?<!\\p{L})(за[её]мщик|поручитель) *[:=-] *[^,.;]{1,160}");
 private static final Pattern CONTRACT_TYPE=Pattern.compile("(?iu)(?<!\\p{L})договор(?:а|у|ом|е|ы|ов|ам|ами|ах)? +([а-яё-]+(?: +[а-яё-]+){0,2}) +[№#]");
 private static final Pattern THIRD=Pattern.compile("(?iu)(?<!\\p{L})(?:за +треть(?:е|его|ю) +лиц[оа]|в +пользу +третьего +лица|от +имени|по +поручению|третье +лицо)(?!\\p{L})");
 private MentionContexts() { }
 static void inspectContractSuffix(CharSequence text,int offset,PurposeParser.Evidence evidence,ParsingBudget budget) {
  var suffix=NON_BINDING_SUFFIX.matcher(text).region(offset,text.length());
  if(suffix.lookingAt()){
   budget.match();
   evidence.issues.add("UNRESOLVED_MENTION_CONTEXT: normalized ["+suffix.start()+","+suffix.end()+"): "+suffix.group().strip()+"; mentions retained, no confirmed fact");
  }
 }
 static void inspect(CharSequence text,PurposeParser.Evidence evidence,ParsingBudget budget) {
  var m=RISK.matcher(text);
  while(m.find()){budget.match();if(!IdentityRules.insideQuotedOrganizationName(text,m.start(),budget,evidence))evidence.issues.add("UNRESOLVED_MENTION_CONTEXT: normalized ["+m.start()+","+m.end()+"): "+m.group()+"; mentions retained, no confirmed fact");}
  var ids=ID.matcher(text);var skeletons=new HashMap<String,String>();
  while(ids.find()){budget.match();String value=ids.group(1);if(!value.matches(".*[0-9].*")||IdentityRules.insideQuotedOrganizationName(text,ids.start(1),budget,evidence))continue;
   String raw=PurposeParser.number(value),key=skeleton(raw);String previous=skeletons.putIfAbsent(key,raw);
   if(previous!=null&&!previous.equals(raw))evidence.issues.add("CONFUSABLE_IDENTIFIER_RISK: normalized ["+ids.start(1)+","+ids.end(1)+"): "+previous+" versus "+raw+"; no alias certified, all literal keys retained");
  }
 }
 // Diagnostic comparison only. Never used by number(), candidate keys, SQL, or matching.
 private static String skeleton(String value){String from="АВЕКМНОРСТХУO",to="ABEKMH0PCTXY0";var b=new StringBuilder();for(char c:value.toCharArray()){int i=from.indexOf(c);b.append(i<0?c:to.charAt(i));}return b.toString();}
 private static Context context(String text,int start,int end,boolean organization){
  int left=Math.max(text.lastIndexOf(';',Math.max(0,start-1)),text.lastIndexOf(". ",Math.max(0,start-1)));left=Math.max(Math.max(0,start-140),left<0?0:left+1);
  int right=Math.min(text.length(),end+160);int boundary=text.indexOf(". ",Math.max(start,end-1));if(boundary>=0)right=Math.min(right,Math.max(end,boundary));
  String before=text.substring(left,start).toLowerCase(Locale.ROOT),after=text.substring(end,right).toLowerCase(Locale.ROOT);
  if(!organization){long quotes=text.substring(0,start).chars().filter(c->c=='"').count();if(quotes%2==1)return Context.QUOTED;}
  if(before.matches("(?s).*не +(?:относится +к|по) +(?:договор[а-яё]* +[№#]? *)?"))return Context.NEGATED;
  if(before.matches("(?s).*"+NON_BINDING_BEFORE+" +[№#]? *"))return Context.NEGATED;
  if(before.matches("(?s).*"+HISTORICAL_BEFORE+" +[№#]? *")||HISTORICAL_SUFFIX.matcher(after).lookingAt())return Context.HISTORICAL;
  if(NON_BINDING_SUFFIX.matcher(after).lookingAt())return Context.UNKNOWN;
  if(after.matches("(?s)^ *указан[а-яё]* +ошибоч[а-яё]*.*")||before.contains("ошибоч")||before.contains("корректное основание"))return Context.CORRECTION;
  if(after.matches("(?s)^[ ,]*расторгнут[а-яё]*.*")||before.contains("предыдущий номер"))return Context.HISTORICAL;
  if(before.contains("цитат")||before.contains("отмененн"))return Context.QUOTED;
  if(RISK.matcher(before+after).find())return Context.UNKNOWN;
  if(before.contains("фактически"))return Context.AFFIRMED;
  // An unscoped cancellation cannot retroactively certify the preceding assertion.
  if(Pattern.compile("(?iu)это +цитата|отмен[её]нного +письма").matcher(text).find())return Context.UNKNOWN;
  return Context.AFFIRMED;
 }
 private static void add(List<Mention> list,String text,String kind,int start,int end){if(list.size()<256)list.add(new Mention(kind,text.substring(start,end),start,end,context(text,start,end,kind.startsWith("ORGANIZATION")),"NORMALIZED_UTF16",false));}
 public static List<Mention> describe(PurposeParser.Features f) {
  String text=f.normalized();var result=new ArrayList<Mention>();var entities=new LinkedHashMap<String,List<String>>();
  entities.put("CONTRACT",f.contractReferences().stream().map(PurposeParser.ContractReference::surface).distinct().toList());
  var inns=new ArrayList<String>(f.inns());inns.addAll(f.invalidInns());entities.put("INN",inns);
  entities.put("NAME",f.names().stream().map(NameNormalizer.Mention::surface).toList());
  for(var entry:entities.entrySet())for(String value:entry.getValue())for(int i=0;i+value.length()<=text.length()&&result.size()<256;i++){
   if(!text.regionMatches(true,i,value,0,value.length()))continue;int end=i+value.length();add(result,text,entry.getKey(),i,end);i=end-1;
  }
  var opaque=new ArrayList<int[]>();var orgNames=ORGS.matcher(text);while(orgNames.find())opaque.add(new int[]{orgNames.start(),orgNames.end()});
  for(var spec:Map.of("ORGANIZATION",ORGS,"ORGANIZATION_INN",ORG_INN,"DOCUMENT",OTHER_DOC,"DOMAIN_ROLE",DOMAIN_ROLE,"CONTRACT_TYPE",CONTRACT_TYPE).entrySet()){
   var m=spec.getValue().matcher(text);while(m.find()&&result.size()<256){String kind=spec.getKey();if(!kind.startsWith("ORGANIZATION")&&opaque.stream().anyMatch(r->m.start()>=r[0]&&m.start()<r[1]))continue;if(kind.equals("DOMAIN_ROLE"))kind=m.group(1).toLowerCase(Locale.ROOT).startsWith("поруч")?"ROLE_GUARANTOR":"ROLE_BORROWER";add(result,text,kind,m.start(),m.end());}
  }
  var ids=ID.matcher(text);while(ids.find()&&result.size()<256)if(ids.group(1).matches(".*[0-9].*")&&opaque.stream().noneMatch(r->ids.start(1)>=r[0]&&ids.start(1)<r[1]))add(result,text,"IDENTIFIER_REFERENCE",ids.start(1),ids.end(1));
  return result.stream().distinct().sorted(Comparator.comparingInt(Mention::start).thenComparing(Mention::kind)).toList();
 }
 public static Context thirdPartyContext(PurposeParser.Features f) {
  if(!f.thirdParty())return Context.ABSENT;
  var mentions=THIRD.matcher(f.normalized());boolean found=false,allNegative=true,affirmed=false;
  while(mentions.find()){found=true;String before=f.normalized().substring(Math.max(0,mentions.start()-120),mentions.start());int b=Math.max(before.lastIndexOf(';'),before.lastIndexOf(". "));before=before.substring(b<0?0:b+1);
   boolean negative=before.matches("(?isu).*не +(?:является[^,.;]{0,100}|перечислять[^,.;]{0,100})$")||before.matches("(?isu).*не +$");
   Context c=context(f.normalized(),mentions.start(),mentions.end(),false);
   if(!negative||c==Context.QUOTED)allNegative=false;
   if(!negative&&c==Context.AFFIRMED)affirmed=true;
  }
  for(var name:f.names()){int start=f.normalized().indexOf(name.surface());if(start<0)continue;String prefix=f.normalized().substring(Math.max(0,start-80),start);
   if(prefix.matches("(?iu)(?s).*(?:за|по поручению|от имени) +$")&&context(f.normalized(),start,start+name.surface().length(),false)!=Context.QUOTED){allNegative=false;affirmed=true;}
  }
  if(affirmed)return Context.AFFIRMED;
  if(found&&allNegative)return Context.NEGATED;
  return RISK.matcher(f.normalized()).find()?Context.UNKNOWN:Context.AFFIRMED;
 }
 public static List<String> displayNumbers(PurposeParser.Features f) {
  var groups=new ArrayList<LinkedHashSet<String>>();var surfaces=new ArrayList<String>();var counts=new ArrayList<Integer>();
  for(var ref:f.contractReferences()){
   int match=-1;for(int i=0;i<groups.size();i++)if(!Collections.disjoint(groups.get(i),ref.alternatives())){match=i;break;}
   if(match<0){groups.add(new LinkedHashSet<>(ref.alternatives()));surfaces.add(ref.surface());counts.add(1);}
   else {groups.get(match).retainAll(ref.alternatives());counts.set(match,counts.get(match)+1);}
  }
  var values=new ArrayList<String>();for(int i=0;i<groups.size();i++)values.add(groups.get(i).size()==1||counts.get(i)>1?groups.get(i).stream().min(Comparator.comparingInt(String::length)).orElseThrow():surfaces.get(i));return List.copyOf(values);
 }
}
