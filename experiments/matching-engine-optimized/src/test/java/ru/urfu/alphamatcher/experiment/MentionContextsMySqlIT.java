package ru.urfu.alphamatcher.experiment;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;
class MentionContextsMySqlIT {
 private final MatchingEngine engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
 @Test void twentyRawInputsCannotCertifyUnrelatedSyntheticParties()throws Exception{
  try(var c=ExperimentDatabase.connect()){try{for(String text:ManualTwentyCorpus.TEXTS){purpose(c,text);var d=engine.match(c,1);assertNotEquals(Status.SAFE,d.status(),d.toString());assertEquals(d,engine.match(c,1));assertFalse(d.checks().isEmpty());}try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM contract")){assertTrue(r.next());assertEquals(9,r.getInt(1));}}finally{c.rollback();}}
 }
 @Test void correctedNegatedAndCancelledReferencesStayInCompleteDiscovery()throws Exception{
  try(var c=ExperimentDatabase.connect()){try{
   for(String text:List.of("Договор № D-001 указан ошибочно. Корректное основание - договор № D-DUP-1, ИНН 9900000017", "Платеж НЕ относится к договору № D-001, ИНН 9900000017", "\"Договор № D-001, ИНН 9900000017\". Это цитата из отмененного письма")){
    purpose(c,text);var d=engine.match(c,1);assertEquals(Status.REVIEW,d.status());assertTrue(d.candidateIds().contains(101L),d.toString());assertEquals("COMPLETE",d.searchEvidence().completeness().name());assertTrue(d.checks().stream().anyMatch(k->k.code().equals("INPUT_VALID")&&!k.passed()),d.toString());
    if(text.contains("D-DUP-1"))assertTrue(d.candidateIds().containsAll(List.of(102L,103L)),d.toString());
   }
  }finally{c.rollback();}}
 }
 @Test void newlySupportedTypesRequireAllExistingSafeChecks()throws Exception{
  try(var c=ExperimentDatabase.connect()){try{for(String type:List.of("процентного займа","поручительства","залога","услуг")){purpose(c,"Оплата по договору "+type+" № D-001, ИНН 9900000017");var d=engine.match(c,1);assertEquals(Status.SAFE,d.status(),d.toString());assertEquals(101L,d.proposedContractId());assertEquals(List.of(101L),d.candidateIds());assertTrue(d.checks().stream().allMatch(MatchingModel.Check::passed));assertEquals("COMPLETE",d.searchEvidence().completeness().name());}}finally{c.rollback();}}
 }
 @Test void equalLegalNamesDoNotHideSwappedInnRoles()throws Exception{
  try(var c=ExperimentDatabase.connect()){try{
   try(var s=c.prepareStatement("UPDATE client SET name=? WHERE id IN (1,2)")){s.setString(1,"ООО Вектор");assertEquals(2,s.executeUpdate());}
   purpose(c,"Договор D-001. Плательщик ООО \"Вектор\", ИНН 9900000024. Получатель ООО \"Вектор\", ИНН 9900000017");var bad=engine.match(c,1);assertEquals(Status.REVIEW,bad.status());assertTrue(bad.checks().stream().anyMatch(k->k.code().equals("ROLE_CLAIMS")&&!k.passed()),bad.toString());
   purpose(c,"Договор D-001. Плательщик ООО \"Вектор\", ИНН 9900000017. Получатель ООО \"Вектор\", ИНН 9900000024");assertEquals(Status.SAFE,engine.match(c,1).status());
  }finally{c.rollback();}}
 }
 private static void purpose(Connection c,String text)throws Exception{try(var s=c.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=1")){s.setString(1,text);assertEquals(1,s.executeUpdate());}}
}
