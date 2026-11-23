package ru.urfu.alphamatcher.experiment;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
class TwentyScenarioRegressionTest {
 static java.util.stream.Stream<Integer> cases(){return java.util.stream.IntStream.rangeClosed(1,20).boxed();}
 @ParameterizedTest @MethodSource("cases") void exactCases(int id){
  var f=new PurposeParser().parse(ManualTwentyCorpus.TEXTS.get(id-1));
  var observed=new LinkedHashSet<>(f.contractSearchNumbers().stream().map(v->v.replaceFirst("[.]+$","")).toList());assertEquals(new LinkedHashSet<>(ManualTwentyCorpus.CONTRACTS.get(id-1)),observed,"case"+id+" "+f);
  assertEquals(new HashSet<>(ManualTwentyCorpus.INNS.get(id-1)),new HashSet<>(f.inns()),"INNs case"+id);
  assertEquals(new HashSet<>(ManualTwentyCorpus.ORGANIZATIONS.get(id-1)),new HashSet<>(f.organizations()),"organizations case"+id);
  assertEquals(new HashSet<>(ManualTwentyCorpus.NAMES.get(id-1)),new HashSet<>(f.names().stream().map(n->n.surface()).toList()),"names case"+id);
  assertEquals(new HashSet<>(ManualTwentyCorpus.ISSUES.get(id-1)),new HashSet<>(f.issues().stream().map(v->v.split(":",2)[0]).toList()),"issue codes case"+id);
  assertEquals(id==19?PurposeParser.Completeness.INCOMPLETE:PurposeParser.Completeness.COMPLETE,f.contractAnalysis(),"contract completeness case"+id);
  for(var m:f.mentions()){assertFalse(m.confirmedFact());assertEquals(m.surface(),f.normalized().substring(m.start(),m.end()));}
  if(id==19)assertEquals(PurposeParser.Completeness.INCOMPLETE,f.contractAnalysis());
  if(id==7||id==14)assertTrue(f.names().isEmpty(),f.toString());
  if(id==12)assertTrue(f.names().stream().noneMatch(n->n.surface().startsWith("имени")));
  if(id==13)assertTrue(f.names().stream().noneMatch(n->n.surface().contains("действует")));
  if(id==8)assertTrue(f.issues().stream().noneMatch(i->i.startsWith("INVALID_INN_FORMAT")));
  if(id==10||id==20)assertEquals(MentionContexts.Context.AFFIRMED,MentionContexts.thirdPartyContext(f));
  if(id==9)assertFalse(f.thirdPartyAsserted());
  if(id==9)assertEquals(MentionContexts.Context.NEGATED,MentionContexts.thirdPartyContext(f));
  if(id==6||id==20)assertTrue(f.issues().stream().anyMatch(i->i.startsWith("CONFUSABLE_IDENTIFIER_RISK")),f.toString());
  if(id==17){assertEquals(List.of("0007/26"),f.contractDisplayNumbers());assertEquals(2,f.contractReferences().size());}
  if(id==14)assertTrue(f.issues().stream().noneMatch(i->i.startsWith("UNSUPPORTED_CONTRACT")||i.startsWith("UNSUPPORTED_ACTOR_CONTEXT")),f.toString());
  if(id==4){assertTrue(f.mentions().stream().anyMatch(m->m.kind().equals("ROLE_BORROWER")));assertTrue(f.mentions().stream().anyMatch(m->m.kind().equals("ROLE_GUARANTOR")));}
  if(id==20)assertTrue(f.roleClaims().stream().anyMatch(c->c.role().equals("RECIPIENT")&&c.kind().equals("NAME")),f.toString());
 }
 @Test void unicode17isExact(){String s=ManualTwentyCorpus.TEXTS.get(16);assertTrue(s.contains("дог.\u00a0№\u202f0007"));assertTrue(s.contains("ИНН:\t"));}
 @Test void affirmingAnotherClauseCannotBeNegatedByEarlierThirdPartyDenial(){var f=new PurposeParser().parse("Платеж НЕ является исполнением обязательства за третье лицо. Оплата по поручению Иванова Ивана Ивановича.");assertNotEquals(MentionContexts.Context.NEGATED,MentionContexts.thirdPartyContext(f));}
 @Test void deterministicVariants(){var unique=new HashSet<String>();for(int i=0;i<1100;i++){String original=ManualTwentyCorpus.TEXTS.get(i%20);StringBuilder altered=new StringBuilder();int state=i+113;for(int j=0;j<original.length();j++){char c=original.charAt(j);if(Character.isWhitespace(c)||Character.isSpaceChar(c)){state=state*1664525+1013904223;altered.append(List.of("\u00a0","\u202f","\t","\n\n","   ").get(Math.floorMod(state>>>16,5)));}else altered.append(c);}unique.add(altered.toString());assertEquals(new PurposeParser().parse(original),new PurposeParser().parse(altered.toString()));}assertEquals(1100,unique.size(),"Variations must really differ, not repeat20 identical cases");}
 @Test void opaqueOrganizationNameIsNotAPersonContractOrInn(){var f=new PurposeParser().parse("ООО \"Договор № 77 ИНН 7707083893 Иванов Иван Иванович\"");assertTrue(f.contractNumbers().isEmpty());assertTrue(f.inns().isEmpty());assertTrue(f.names().isEmpty());assertEquals(1,f.organizations().size());}
 @Test void case11RolesRequireFiniteCaseEvidence(){var f=new PurposeParser().parse(ManualTwentyCorpus.TEXTS.get(10));assertTrue(f.roleClaims().contains(new PurposeParser.RoleClaim("NAME","Сидоровой Анной Олеговной","PAYER")));assertTrue(f.roleClaims().contains(new PurposeParser.RoleClaim("NAME","Иванову Ивану Ивановичу","RECIPIENT")));assertTrue(f.roleClaims().contains(new PurposeParser.RoleClaim("NAME","Иванов Иван Иванович","BENEFICIARY")));}
 @Test void unresolvedFrameworkCannotBorrowAnUnrelatedContractIdentity(){var f=new PurposeParser().parse("Договор займа № ZX-583. Основание платежа - рамочный договор, спецификация прилагается.");assertEquals(PurposeParser.Completeness.INCOMPLETE,f.contractAnalysis());}
 @Test void explicitBrokenMarkerCannotBeSuppressedAsNarrative(){var f=new PurposeParser().parse("Договор № стороны не заключали");assertEquals(PurposeParser.Completeness.INCOMPLETE,f.contractAnalysis());}
 @Test void malformedTypedNameRemainsUnresolvedEvidenceWithoutRole(){var f=new PurposeParser().parse("Payer Full name: Brown John George Extra");assertEquals("Brown John George Extra",f.names().getFirst().surface());assertTrue(f.roleClaims().stream().noneMatch(c->c.kind().equals("NAME")));assertTrue(f.issues().stream().anyMatch(i->i.startsWith("INCOMPLETE_NAME")));}
 @Test void quotedStatementRetainsKeysWithoutCertifyingIntent(){var f=new PurposeParser().parse("\"Договор № ZX-583\"");assertEquals(List.of("ZX-583"),f.contractSearchNumbers());assertEquals(ru.urfu.alphamatcher.experiment.MatchingModel.Status.REVIEW,CorrectnessRemediationTest.decide(f.normalized(),List.of(CorrectnessRemediationTest.CONTRACT)).status());}
 @Test void documentBasisDoesNotMakeUnknownDependenciesSafe(){
  var good=new PurposeParser().parse("Оплата за услуги по счету № 578. Договор № ZX-583");assertTrue(good.issues().isEmpty(),good.toString());
  var bad=new PurposeParser().parse("Оплата за услуги неизвестному лицу по счету № 578. Договор № ZX-583");assertTrue(bad.issues().stream().anyMatch(i->i.startsWith("UNSUPPORTED_ACTOR_CONTEXT")),bad.toString());
 }
}
