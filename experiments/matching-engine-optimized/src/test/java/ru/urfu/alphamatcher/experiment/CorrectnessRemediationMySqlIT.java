package ru.urfu.alphamatcher.experiment;

import java.sql.Connection;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Real catalog lookup, no mocked candidate IDs; changes are rolled back on this owned DB. */
class CorrectnessRemediationMySqlIT {
    private final MatchingEngine engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
    static void purpose(Connection c,String value)throws Exception {
        try(var s=c.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=1")){s.setString(1,value);assertEquals(1,s.executeUpdate());}
    }
    static void number(Connection c,long id,String value)throws Exception {
        try(var s=c.prepareStatement("UPDATE contract SET contract_number=? WHERE id=?")){s.setString(1,value);s.setLong(2,id);assertEquals(1,s.executeUpdate());}
    }
    @Test void terminalPeriodAlternativesUseActualMysqlAndRetainAmbiguity()throws Exception {
        try(var c=ExperimentDatabase.connect()) {try {
            number(c,101,"ZX-583");number(c,102,"UNRELATED-584");
            for(String text:List.of("Договор ZX-583. ИНН 9900000017","ИНН 9900000017; Договор ZX-583.")) {
                purpose(c,text);var d=engine.match(c,1);assertEquals(Status.SAFE,d.status(),d.toString());assertEquals(List.of(101L),d.candidateIds());
                assertTrue(d.checks().stream().anyMatch(k->k.code().equals("PURPOSE_NAMES")&&k.passed()));
            }
            number(c,102,"ZX-583.");purpose(c,"Договор ZX-583. ИНН 9900000017");
            var ambiguous=engine.match(c,1);assertEquals(Status.REVIEW,ambiguous.status());assertEquals(List.of(101L,102L),ambiguous.candidateIds());assertNull(ambiguous.proposedContractId());
            purpose(c,"Договор \"ZX-583.\". ИНН 9900000017");
            var literal=engine.match(c,1);assertEquals(List.of(102L),literal.candidateIds());
            assertEquals(Status.SAFE,literal.status());assertEquals(102L,literal.proposedContractId());
            // Contract103, unlike102, has a different payer in the immutable fixture.
            number(c,102,"UNRELATED-584");number(c,103,"ZX-583.");
            var wrongParty=engine.match(c,1);assertEquals(List.of(103L),wrongParty.candidateIds());
            assertEquals(Status.REVIEW,wrongParty.status());assertNull(wrongParty.proposedContractId());
            number(c,101,"ZX.583");purpose(c,"Договор ZX.583, ИНН 9900000017");
            assertEquals(Status.SAFE,engine.match(c,1).status());
            purpose(c,"Договор \"ZX-583\", ИНН 9900000017");assertTrue(engine.match(c,1).candidateIds().isEmpty());
        }finally{c.rollback();}}
    }
    @Test void observedNameMustReachFinalGuardWithRealNumberInnAndParties()throws Exception {
        try(var c=ExperimentDatabase.connect()){try{
            try(var s=c.createStatement()){
                s.executeUpdate("UPDATE client SET last_name='Туманов',first_name='Елисей',middle_name='Захарович' WHERE id=1");
            }
            number(c,101,"ZX-583");
            for(String value:List.of("Тумaнов Елисей Захарович","Туманов Елиcей Захарович","Туманов Елисей Зaхарович",
                    "Τуманов Елисей Захарович","Tumanov Elisei Zakharovich","Туманов Елисей Назарович","???")) {
                purpose(c,"Договор ZX-583, ИНН 9900000017; Плательщик: "+value);
                var d=engine.match(c,1);assertEquals(List.of(101L),d.candidateIds());assertEquals(Status.REVIEW,d.status());
                assertNull(d.proposedContractId());CorrectnessRemediationTest.passed(d,"INPUT_VALID");
                CorrectnessRemediationTest.passed(d,"PURPOSE_INN");CorrectnessRemediationTest.passed(d,"PARTY_PAYER");
                assertTrue(d.checks().stream().anyMatch(k->k.code().equals("PURPOSE_NAMES")&&!k.passed()),d.toString());
            }
            purpose(c,"Договор ZX-583, ИНН 9900000017; Плательщик: Туманов Елисей Захарович");
            var safe=engine.match(c,1);assertEquals(Status.SAFE,safe.status());CorrectnessRemediationTest.passed(safe,"PURPOSE_NAMES");
            purpose(c,"Договор ZX-583, ИНН 9900000017; Другов Елисей ЗАХАРОВИЧ");
            var casing=engine.match(c,1);assertEquals(Status.REVIEW,casing.status());assertFalse(casing.features().names().isEmpty());
            assertTrue(casing.checks().stream().anyMatch(k->k.code().equals("PURPOSE_NAMES")&&!k.passed()));
        }finally{c.rollback();}}
    }
    @Test void newNameAndPaymentObjectFormsReachFinalGuardsInMysql()throws Exception {
        try(var c=ExperimentDatabase.connect()){try{
            for(String[] pair:List.of(new String[]{"Dupré André Lucien","Dupré André Lucien"},
                    new String[]{"Кхан Раджив Кумар","Кхан Раджив Кумар"},
                    new String[]{"Елисей Захарович Туманов","Туманов Елисей Захарович"},
                    new String[]{"Соловья Льва Ильича","Соловей Лев Ильич"},
                    new String[]{"Журавеля Кузьмы Фомича","Журавель Кузьма Фомич"})){
                String[] p=pair[1].split(" ");
                try(var s=c.prepareStatement("UPDATE client SET last_name=?,first_name=?,middle_name=? WHERE id=1")){
                    for(int i=0;i<3;i++)s.setString(i+1,p[i]);s.executeUpdate();
                }
                purpose(c,"Оплата за оцифровку архива; Договор D-001, ИНН 9900000017; Плательщик: "+pair[0]);
                var d=engine.match(c,1);assertEquals(Status.SAFE,d.status(),d.toString());
                CorrectnessRemediationTest.passed(d,"PURPOSE_NAMES");CorrectnessRemediationTest.passed(d,"ROLE_CLAIMS");
                purpose(c,"Оплата за оцифровку архива; Договор D-001, ИНН 9900000017; Плательщик: Другое "+p[1]+" "+p[2]);
                var bad=engine.match(c,1);assertEquals(Status.REVIEW,bad.status());assertNull(bad.proposedContractId());
            }
            for(String context:List.of("Оплата за организацию ООО \"Синтетический получатель\"","Оплата за доверителя",
                    "Оплата за неизвестность","По поручению Туманова Елисея Захаровича")){
                purpose(c,context+"; Договор D-001, ИНН 9900000017");assertEquals(Status.REVIEW,engine.match(c,1).status());
            }
        }finally{c.rollback();}}
    }
    @Test void newDevelopmentDecisionsAreFullyEqualSerialAndW4AndDoNotWriteLinks()throws Exception {
        // New independent tiny world in its own IDs, shared by real read-only worker sessions.
        // Creation/cleanup is fixture setup, never an algorithm side effect.
        var texts=List.of("Договор E4-650. ИНН 9900000017",
                "Договор \"E4.651\", ИНН 9900000017",
                "Договор E4-650, ИНН 9900000017; Плательщик: Туманов Елисей Захарович",
                "Договор E4-650, ИНН 9900000017; Плательщик: Тумaнов Елисей Захарович",
                "Договор E4-650, ИНН 9900000017; Плательщик: Tumanov Elisei Zakharovich",
                "Договор E4-650, ИНН 9900000017; Плательщик: ???",
                "Оплата за ламинирование документов; Договор E4-650, ИНН 9900000017",
                "Оплата за организацию; Договор E4-650, ИНН 9900000017",
                "Договоры ABSENT-651, ABSENT-652, ИНН 9900000017",
                "Договор ABSENT-651, ИНН 9900000017",
                "Договор E4-652. ИНН 9900000017",
                "Договор \"E4-652.\", ИНН 9900000017");
        var expected=List.of(Status.SAFE,Status.SAFE,Status.SAFE,Status.REVIEW,Status.REVIEW,Status.REVIEW,
                Status.SAFE,Status.REVIEW,Status.REVIEW,Status.NO_CONTRACT,Status.REVIEW,Status.SAFE);
        var ids=java.util.stream.LongStream.range(71000200,71000200+texts.size()).boxed().toList();
        try(var c=ExperimentDatabase.connect()){
            try{
                try(var s=c.createStatement()){
                    s.executeUpdate("INSERT INTO client(id,client_type_id,inn,last_name,first_name,middle_name) SELECT 71000001,client_type_id,inn,'Туманов','Елисей','Захарович' FROM client WHERE id=1");
                    for(int i=0;i<4;i++){
                        String n=List.of("E4-650","E4.651","E4-652","E4-652.").get(i);long id=71000100+i;
                        s.executeUpdate("INSERT INTO contract(id,contract_type_id,contract_number,contract_date) SELECT "+id+",contract_type_id,'"+n+"',contract_date FROM contract WHERE id=101");
                        s.executeUpdate("INSERT INTO contract_party(contract_id,role_id,client_id) SELECT "+id+",role_id,CASE WHEN client_id=1 THEN 71000001 ELSE client_id END FROM contract_party WHERE contract_id=101");
                    }
                }
                for(int i=0;i<ids.size();i++){
                    try(var s=c.prepareStatement("INSERT INTO operation(id,reference,operation_at,payment_purpose,status_id) SELECT ?,?,operation_at,?,status_id FROM operation WHERE id=1")){
                        s.setLong(1,ids.get(i));s.setString(2,"STAGE4E-"+ids.get(i));s.setString(3,texts.get(i));s.executeUpdate();
                    }
                    try(var s=c.createStatement()){
                        s.executeUpdate("INSERT INTO operation_party(operation_id,role_id,client_id,client_type_id,inn,name) SELECT "+ids.get(i)+",role_id,CASE WHEN client_id=1 THEN 71000001 ELSE client_id END,client_type_id,inn,name FROM operation_party WHERE operation_id=1");
                    }
                }
                c.commit();
                var serial=new ArrayList<Decision>();
                try(var reader=MatchingEngine.openReadOnlySession()){for(long id:ids)serial.add(reader.match(id));}
                assertEquals(expected,serial.stream().map(Decision::status).toList());
                var parallel=new ArrayList<Decision>();
                var summary=new ParallelMatcher().run(ids,new ParallelMatcher.Config(4,1,java.time.Duration.ofSeconds(30),java.time.Duration.ofSeconds(5),false),
                        new ParallelMatcher.Cancellation(),out->{assertEquals(ParallelMatcher.State.SUCCESS,out.state());parallel.add(out.result().decision());});
                assertEquals(serial,parallel);assertEquals(ids.size(),summary.succeeded());assertEquals(0,summary.errors());
                assertTrue(serial.get(3).checks().stream().anyMatch(k->k.code().equals("PURPOSE_NAMES")&&!k.passed()));
                String run=System.getenv("STAGE4E_RUN");
                if(run!=null){Path out=Path.of(run,"development-decisions-"+System.nanoTime()+".txt");
                    Files.writeString(out,"Complete serial = W4 Decisions, 12/12\n"+serial,StandardOpenOption.CREATE_NEW);}
                try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM operation WHERE id BETWEEN 71000200 AND 71000299 AND contract_id IS NOT NULL")){r.next();assertEquals(0,r.getInt(1));}
            }finally{
                c.rollback();try(var s=c.createStatement()){
                    s.executeUpdate("DELETE FROM operation_party WHERE operation_id BETWEEN 71000200 AND 71000299");
                    s.executeUpdate("DELETE FROM operation WHERE id BETWEEN 71000200 AND 71000299");
                    s.executeUpdate("DELETE FROM contract_party WHERE contract_id BETWEEN 71000100 AND 71000103");
                    s.executeUpdate("DELETE FROM contract WHERE id BETWEEN 71000100 AND 71000103");
                    s.executeUpdate("DELETE FROM client WHERE id=71000001");
                }c.commit();
            }
        }
    }
}
