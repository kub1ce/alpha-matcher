package ru.urfu.alphamatcher.experiment;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Exact user-supplied manual corpus; expectations are independent of parser output. */
class ManualNineParserTest {
 static final List<String> TEXTS=List.of(
"\u041e\u043f\u043b\u0430\u0442\u0430 \u043f\u043e \u0434\u043e\u0433\u043e\u0432\u043e\u0440\u0430\u043c \u2116 \u0410-45/26 \u043e\u0442 12.09.2026 \u0438 \u2116 \u0410-46/26 \u043e\u0442 14.09.2026 \u0437\u0430 \u043f\u043e\u0441\u0442\u0430\u0432\u043a\u0443 \u043e\u0431\u043e\u0440\u0443\u0434\u043e\u0432\u0430\u043d\u0438\u044f \u041e\u041e\u041e \u00ab\u0412\u0435\u043a\u0442\u043e\u0440\u00bb \u0432 \u043f\u043e\u043b\u044c\u0437\u0443 \u0410\u041e \u00ab\u041a\u043e\u043d\u0442\u0443\u0440\u00bb. \u0418\u041d\u041d 7707083893.",
"\u041f\u043b\u0430\u0442\u0435\u0436 \u043f\u043e \u0434\u043e\u0433\u043e\u0432\u043e\u0440\u0443 \u2116 D-001 \u043e\u0442 03.03.2026 \u0437\u0430 \u0418\u0432\u0430\u043d\u043e\u0432\u0430 \u0418\u0432\u0430\u043d\u0430 \u0418\u0432\u0430\u043d\u043e\u0432\u0438\u0447\u0430. \u0424\u0430\u043a\u0442\u0438\u0447\u0435\u0441\u043a\u0438\u0439 \u043f\u043b\u0430\u0442\u0435\u043b\u044c\u0449\u0438\u043a \u041e\u041e\u041e \u00ab\u0412\u0435\u043a\u0442\u043e\u0440\u00bb, \u043f\u043e\u043b\u0443\u0447\u0430\u0442\u0435\u043b\u044c \u041e\u041e\u041e \u00ab\u0420\u043e\u043c\u0430\u0448\u043a\u0430\u00bb. \u0414\u0435\u043d\u0435\u0436\u043d\u044b\u0435 \u0441\u0440\u0435\u0434\u0441\u0442\u0432\u0430 \u043f\u0435\u0440\u0435\u0447\u0438\u0441\u043b\u044f\u0435\u0442 \u0442\u0440\u0435\u0442\u044c\u0435 \u043b\u0438\u0446\u043e \u043f\u043e \u043f\u043e\u0440\u0443\u0447\u0435\u043d\u0438\u044e \u0434\u043e\u043b\u0436\u043d\u0438\u043a\u0430.",
"\u041f\u043b\u0430\u0442\u0435\u043b\u044c\u0449\u0438\u043a \u041e\u041e\u041e \u00ab\u0410\u043b\u044c\u0444\u0430\u00bb, \u0418\u041d\u041d 7707083893. \u041f\u043e\u043b\u0443\u0447\u0430\u0442\u0435\u043b\u044c \u041e\u041e\u041e \u00ab\u0411\u0435\u0442\u0430\u00bb, \u0418\u041d\u041d 000012345627. \u041e\u043f\u043b\u0430\u0442\u0430 \u043f\u043e \u0434\u043e\u0433\u043e\u0432\u043e\u0440\u0443 \u2116 77/2026 \u0437\u0430 \u041f\u0435\u0442\u0440\u043e\u0432\u0430 \u041f\u0435\u0442\u0440\u0430 \u0421\u0435\u0440\u0433\u0435\u0435\u0432\u0438\u0447\u0430. \u0412\u044b\u0433\u043e\u0434\u043e\u043f\u0440\u0438\u043e\u0431\u0440\u0435\u0442\u0430\u0442\u0435\u043b\u044c \u2014 \u041e\u041e\u041e \u00ab\u0413\u0430\u043c\u043c\u0430\u00bb.",
"\u0412\u043e\u0437\u0432\u0440\u0430\u0442 \u0437\u0430\u0434\u043e\u043b\u0436\u0435\u043d\u043d\u043e\u0441\u0442\u0438 \u0437\u0430 \u0418\u0432\u0430\u043d\u043e\u0432\u0430 \u0418\u0432\u0430\u043d\u0430 \u0418\u0432\u0430\u043d\u043e\u0432\u0438\u0447\u0430 \u043f\u043e \u0434\u043e\u0433\u043e\u0432\u043e\u0440\u0443 \u0437\u0430\u0439\u043c\u0430 \u2116 14/\u041a-26. \u041f\u043e\u043b\u0443\u0447\u0435\u043d\u043d\u044b\u0435 \u0441\u0440\u0435\u0434\u0441\u0442\u0432\u0430 \u043f\u0440\u0435\u0434\u043d\u0430\u0437\u043d\u0430\u0447\u0435\u043d\u044b \u041f\u0435\u0442\u0440\u043e\u0432\u0443 \u041f\u0435\u0442\u0440\u0443 \u0421\u0435\u0440\u0433\u0435\u0435\u0432\u0438\u0447\u0443. \u041e\u0442\u043f\u0440\u0430\u0432\u0438\u0442\u0435\u043b\u044c \u043f\u043b\u0430\u0442\u0435\u0436\u0430 \u2014 \u0421\u0438\u0434\u043e\u0440\u043e\u0432\u0430 \u0410\u043d\u043d\u0430 \u041e\u043b\u0435\u0433\u043e\u0432\u043d\u0430.",
"\u041e\u043f\u043b\u0430\u0442\u0430 \u043f\u043e \u0434\u043e\u0433\u043e\u0432\u043e\u0440\u0443 \u2116 100-2026. \u0418\u041d\u041d \u043f\u043b\u0430\u0442\u0435\u043b\u044c\u0449\u0438\u043a\u0430 7707083894, \u0418\u041d\u041d \u043f\u043e\u043b\u0443\u0447\u0430\u0442\u0435\u043b\u044f 000012345627. \u0421\u0432\u0435\u0434\u0435\u043d\u0438\u044f \u043e\u0431 \u043e\u0442\u043f\u0440\u0430\u0432\u0438\u0442\u0435\u043b\u0435 \u0442\u0440\u0435\u0431\u0443\u044e\u0442 \u0443\u0442\u043e\u0447\u043d\u0435\u043d\u0438\u044f.",
"\u041e\u043f\u043b\u0430\u0442\u0430 \u043f\u043e \u0434\u043e\u0433. \u2116 AB-0\u041e1/26. \u0418\u041d\u041d 7707083893. \u041e\u041e\u041e \u00ab\u0412\u0435\u043a\u0442\u043e\u0440\u00bb.",
"\u041e\u041f\u041b\u0410\u0422\u0410   \u041f\u041e \u0414\u041e\u0413. \u2116  045/26 \u2014 \u0437\u0430 \u0443\u0441\u043b\u0443\u0433\u0438\n\u0418\u041d\u041d: 7707083893;  \u0424\u0418\u041e: \u041f\u0435\u0442\u0440\u043e\u0432 \u041f\u0435\u0442\u0440 \u0421\u0435\u0440\u0433\u0435\u0435\u0432\u0438\u0447.\n\u041f\u043e\u043b\u0443\u0447\u0430\u0442\u0435\u043b\u044c: \u041e\u041e\u041e \u00ab\u0420\u043e\u043c\u0430\u0448\u043a\u0430\u00bb. \u0411\u0435\u0437 \u041d\u0414\u0421.",
"\u041f\u0435\u0440\u0435\u0447\u0438\u0441\u043b\u0435\u043d\u0438\u0435 \u0434\u0435\u043d\u0435\u0436\u043d\u044b\u0445 \u0441\u0440\u0435\u0434\u0441\u0442\u0432 \u0437\u0430 \u043e\u043a\u0430\u0437\u0430\u043d\u043d\u044b\u0435 \u043a\u043e\u043d\u0441\u0443\u043b\u044c\u0442\u0430\u0446\u0438\u043e\u043d\u043d\u044b\u0435 \u0443\u0441\u043b\u0443\u0433\u0438 \u0437\u0430 \u043e\u043a\u0442\u044f\u0431\u0440\u044c 2026 \u0433\u043e\u0434\u0430 \u0441\u043e\u0433\u043b\u0430\u0441\u043d\u043e \u0440\u0430\u043d\u0435\u0435 \u0434\u043e\u0441\u0442\u0438\u0433\u043d\u0443\u0442\u044b\u043c \u0434\u043e\u0433\u043e\u0432\u043e\u0440\u0435\u043d\u043d\u043e\u0441\u0442\u044f\u043c. \u0411\u0435\u0437 \u041d\u0414\u0421.",
"\u041e\u043f\u043b\u0430\u0442\u0430 \u043f\u043e \u0434\u043e\u0433\u043e\u0432\u043e\u0440\u0443 \u2116 D-009'; DROP TABLE contract; --. \u041f\u043e\u043b\u0443\u0447\u0430\u0442\u0435\u043b\u044c \u041e\u041e\u041e \u00ab\u0412\u0435\u043a\u0442\u043e\u0440\u00bb, \u0418\u041d\u041d 7707083893. \u0422\u0435\u043a\u0441\u0442 \u043a\u043e\u043c\u043c\u0435\u043d\u0442\u0430\u0440\u0438\u044f \u044f\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u0447\u0430\u0441\u0442\u044c\u044e \u043d\u0430\u0437\u043d\u0430\u0447\u0435\u043d\u0438\u044f \u043f\u043b\u0430\u0442\u0435\u0436\u0430.");
 private final PurposeParser parser=new PurposeParser();
 static Stream<Integer> nine(){return java.util.stream.IntStream.rangeClosed(1,9).boxed();}
 @ParameterizedTest @MethodSource("nine")
 void exactManualCase(int id){verify(id, TEXTS.get(id-1));}
 private void verify(int id,String text){
  var f=parser.parse(text);
  assertEquals(id==9?PurposeParser.Completeness.INCOMPLETE:PurposeParser.Completeness.COMPLETE,f.contractAnalysis(),f.toString());
  assertEquals(id==2||id==3||id==4,f.thirdParty(),f.toString());
  assertEquals(id==5?List.of("7707083894"):List.of(),f.invalidInns());
  assertEquals(switch(id){case 1,6,7,9->List.of("7707083893");case 3->List.of("7707083893","000012345627");case 5->List.of("000012345627");default->List.of();},f.inns());
  assertEquals(switch(id){case 1->List.of("А-45/26","А-46/26");case 2->List.of("D-001");case 3->List.of("77/2026");case 4->List.of("14/К-26.");case 5->List.of("100-2026.");case 6->List.of("AB-0О1/26.");case 7->List.of("045/26");default->List.of();},f.contractNumbers());
  assertEquals(switch(id){case 1->List.of("ооо вектор","ао контур");case 2->List.of("ооо вектор","ооо ромашка");case 3->List.of("ооо альфа","ооо бета","ооо гамма");case 6,9->List.of("ооо вектор");case 7->List.of("ооо ромашка");default->List.of();},f.organizations());
  assertEquals(switch(id){case 2->List.of("Иванова Ивана Ивановича");case 3->List.of("Петрова Петра Сергеевича");case 4->List.of("Петрову Петру Сергеевичу","Сидорова Анна Олеговна","Иванова Ивана Ивановича");case 7->List.of("Петров Петр Сергеевич");default->List.of();},f.names().stream().map(n->n.surface()).toList());
  assertEquals(switch(id){
   case 1->List.of("ORGANIZATION|ао контур|RECIPIENT");
   case 2->List.of("ORGANIZATION|ооо вектор|PAYER","ORGANIZATION|ооо ромашка|RECIPIENT");
   case 3->List.of("INN|7707083893|PAYER","INN|000012345627|RECIPIENT","ORGANIZATION|ооо альфа|PAYER","ORGANIZATION|ооо бета|RECIPIENT","ORGANIZATION|ооо гамма|BENEFICIARY");
   case 4->List.of("NAME|Петрову Петру Сергеевичу|RECIPIENT","NAME|Сидорова Анна Олеговна|PAYER");
   case 5->List.of("INN|7707083894|PAYER","INN|000012345627|RECIPIENT");
   case 7->List.of("ORGANIZATION|ооо ромашка|RECIPIENT");
   case 9->List.of("INN|7707083893|RECIPIENT","ORGANIZATION|ооо вектор|RECIPIENT");default->List.of();},f.roleClaims().stream().map(c->c.kind()+"|"+c.value()+"|"+c.role()).toList());
  assertEquals(id==6?List.of("MIXED_SCRIPT_CONTRACT"):id==9?List.of("UNSUPPORTED_CONTRACT","UNSUPPORTED_CONTRACT","UNSUPPORTED_CONTRACT"):List.of(),f.issues().stream().map(i->i.split(":",2)[0]).toList());
  if(id==4)assertFalse(f.names().get(1).recognized(),"Explicit surface is retained without pretending dictionary certainty");

  assertEquals(switch(id){case 2,3,7->1;case 4->3;default->0;},f.names().size());
  if(id==4){assertTrue(f.roleClaims().contains(new PurposeParser.RoleClaim("NAME","Сидорова Анна Олеговна","PAYER")));assertTrue(f.roleClaims().contains(new PurposeParser.RoleClaim("NAME","Петрову Петру Сергеевичу","RECIPIENT")));}
  if(id==3)for(String role:List.of("PAYER","RECIPIENT","BENEFICIARY"))assertTrue(f.roleClaims().stream().anyMatch(c->c.role().equals(role)));
  if(id==6)assertTrue(f.issues().stream().anyMatch(i->i.startsWith("MIXED_SCRIPT_CONTRACT")));
  else if(id==9)assertTrue(f.issues().stream().allMatch(i->i.startsWith("UNSUPPORTED_CONTRACT")));
  else assertEquals(List.of(),f.issues());
  if(id>=4&&id<=6){String literal=switch(id){case 4->"14/К-26.";case 5->"100-2026.";default->"AB-0О1/26.";};assertEquals(List.of(literal,literal.substring(0,literal.length()-1)),f.contractReferences().getFirst().alternatives());}
  else for(var reference:f.contractReferences())assertEquals(List.of(reference.surface()),reference.alternatives());
 }
 @Test void deterministicWhitespaceMetamorphisms(){
  String[] spaces={"\u00a0","\u202f","\t","\n\n","   "};
  for(int seed=0;seed<1080;seed++){
   int id=seed%9+1;String s=TEXTS.get(id-1);StringBuilder b=new StringBuilder();int n=seed;
   for(int j=0;j<s.length();j++){char c=s.charAt(j);if(c==' '||c=='\n'){b.append(spaces[Math.floorMod(n+j,spaces.length)]);n=n*31+17;}else b.append(c);}
   verify(id,b.toString());
  }
 }
 @Test void terminalDotsRemainLiteralAndPunctuationAlternatives(){
  var f=parser.parse("По договору № A.12.");
  assertEquals(List.of("A.12.","A.12"),f.contractReferences().getFirst().alternatives());
 }
 @Test void ordinaryNounsAreNotContractTypesWithoutNumberMarker(){
  for(String type:List.of("займа","аренды","поставки","обслуживания")){
   var f=parser.parse("По договору "+type+" за услуги");
   assertTrue(f.contractNumbers().isEmpty());assertNotEquals(PurposeParser.Completeness.COMPLETE,f.contractAnalysis());
  }
 }
}
