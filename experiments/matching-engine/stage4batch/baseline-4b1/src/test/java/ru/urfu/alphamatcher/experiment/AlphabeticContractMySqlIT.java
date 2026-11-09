package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AlphabeticContractMySqlIT {
    @ParameterizedTest @ValueSource(strings={"ALPHA/BETA","AB-CD","АБ/ВГ","ABC_DEF"})
    void alphabeticSafeStillRequiresOneRealContractAndConsistentFinancialParties(String number) throws Exception {
        try(var c=ExperimentDatabase.connect()) {try {
            try(var s=c.prepareStatement("UPDATE contract SET contract_number=? WHERE id=101")){s.setString(1,number);s.executeUpdate();}
            setPurpose(c,"Договор "+number+", ИНН 9900000017");var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
            var d=engine.match(c,1);assertEquals(MatchingModel.Status.SAFE,d.status());assertEquals(101L,d.proposedContractId());assertEquals(List.of(101L),d.candidateIds());
            setPurpose(c,"Договор "+number+", ИНН 9900000017, "+"x ".repeat(30000)+"ИНН 9900000031");
            assertEquals(MatchingModel.Status.REVIEW,engine.match(c,1).status(),"A contradiction at the end of the complete bounded input must survive");
            setPurpose(c,"Договор "+number+", ИНН 9900000017, "+"x ".repeat(32001));
            var oversized=engine.match(c,1);assertEquals(MatchingModel.Status.REVIEW,oversized.status());assertTrue(oversized.features().issues().stream().anyMatch(i->i.startsWith("SIZE_LIMIT")));
            for(String text:List.of("Договор "+number,"Договор "+number+", ИНН 9900000031","Договор "+number+", ИНН 9900000018","Договор "+number+", получатель ИНН 9900000017","Договор "+number+", ИНН 9900000017, за третье лицо","Договор "+number+", ИНН 9900000017, по поручению ФИО Иванов Иван Иванович","Договор "+number+", ИНН 9900000017, ФИО Сидоров Иван Иванович","Договоры "+number+", MISSING/CONTRACT, ИНН 9900000017","Договор \""+number+", ИНН 9900000017","Договор "+number+", ИНН 9900000017. "+"Пояснение. ".repeat(500)+" ИНН 9900000031")) {
                setPurpose(c,text);var refused=engine.match(c,1);assertEquals(MatchingModel.Status.REVIEW,refused.status(),text.substring(0,Math.min(text.length(),100)));assertNull(refused.proposedContractId());
            }
            try(var s=c.prepareStatement("UPDATE contract SET contract_number=? WHERE id=102")){s.setString(1,number);s.executeUpdate();}
            setPurpose(c,"Договор "+number+", ИНН 9900000017");var ambiguous=engine.match(c,1);assertEquals(MatchingModel.Status.REVIEW,ambiguous.status());assertEquals(List.of(101L,102L),ambiguous.candidateIds());
        } finally{c.rollback();}}
    }
    @Test void similarAlphabeticNumbersAndHomoglyphsNeverSelectFirstOrFoldIdentity() throws Exception {
        try(var c=ExperimentDatabase.connect()) {try {
            try(var s=c.createStatement()){s.executeUpdate("UPDATE contract SET contract_number='AB-CD' WHERE id=101");s.executeUpdate("UPDATE contract SET contract_number='AB-CDE' WHERE id=102");}
            var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
            setPurpose(c,"Договор AB-CD, ИНН 9900000017");assertEquals(List.of(101L),engine.match(c,1).candidateIds());
            setPurpose(c,"Договор AB-CDE, ИНН 9900000017");assertEquals(List.of(102L),engine.match(c,1).candidateIds());
            setPurpose(c,"Договор AВ-CD, ИНН 9900000017");assertEquals(MatchingModel.Status.REVIEW,engine.match(c,1).status());
            setPurpose(c,"Договоры AB-CD; AB-CDE, ИНН 9900000017");var result=engine.match(c,1);assertEquals(MatchingModel.Status.REVIEW,result.status());assertEquals(List.of(101L,102L),result.candidateIds());
        }finally{c.rollback();}}
    }
    private static void setPurpose(java.sql.Connection c,String text) throws Exception {try(var s=c.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=1")){s.setString(1,text);s.executeUpdate();}}
}
