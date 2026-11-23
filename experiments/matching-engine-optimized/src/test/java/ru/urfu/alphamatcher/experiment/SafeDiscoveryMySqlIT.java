package ru.urfu.alphamatcher.experiment;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Actual MySQL collisions and JDBC faults, not candidate mocks. Every change is rolled back. */
class SafeDiscoveryMySqlIT {
    final MatchingEngine engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
    static void set(Connection c,String sql,String value)throws Exception{try(var s=c.prepareStatement(sql)){s.setString(1,value);s.executeUpdate();}}
    static void number(Connection c,long id,String n)throws Exception{set(c,"UPDATE contract SET contract_number=? WHERE id="+id,n);}
    static void purpose(Connection c,String value)throws Exception{set(c,"UPDATE operation SET payment_purpose=? WHERE id=1",value);}
    @Test void generatedCatalogMatrixNeverChoosesOneBoundaryVariantBeforeSearchingTheOthers()throws Exception{
        try(var c=ExperimentDatabase.connect()){try{
            for(String n:List.of("PX-672","687421","KL.MN"))for(String ending:SafeDiscoveryTest.TERMINATORS){
                purpose(c,SafeDiscoveryTest.purpose(n,ending));number(c,103,"UNRELATED-673");
                for(int state=0;state<4;state++){
                    number(c,101,state==0||state==2?n:"UNRELATED-674");number(c,102,state==1||state==2?n+".":"UNRELATED-675");
                    var d=engine.match(c,1);assertEquals(switch(state){case 0,1->Status.SAFE;case 2->Status.REVIEW;default->Status.NO_CONTRACT;},d.status(),d.toString());
                    SafeDiscoveryTest.passed(d,"INPUT_VALID");SafeDiscoveryTest.passed(d,"OPERATION_PARTIES");SafeDiscoveryTest.passed(d,"CANDIDATE_SEARCH_COMPLETE");
                    assertEquals(CandidateSearch.Origin.JDBC,d.searchEvidence().origin());
                    if(state==2){assertEquals(List.of(101L,102L),d.candidateIds());assertNull(d.proposedContractId());
                        assertTrue(d.checks().stream().anyMatch(k->k.code().equals("EXPLICIT_UNIQUE_CONTRACT")&&!k.passed()));}
                }
                number(c,101,n);number(c,102,n+"-MORE");assertEquals(List.of(101L),engine.match(c,1).candidateIds());
                number(c,102,n+".");purpose(c,"Договор № \""+n+".\"; ИНН 9900000017");
                assertEquals(List.of(102L),engine.match(c,1).candidateIds());
            }
            number(c,101,"PX-672");number(c,102,"UNRELATED-674");
            purpose(c,"Договор № PX-672.; договор № \"PX-672\"; ИНН 9900000017");assertEquals(Status.SAFE,engine.match(c,1).status());
            purpose(c,"Договоры № PX-672.; № ABSENT-673; ИНН 9900000017");assertEquals(Status.REVIEW,engine.match(c,1).status());
            purpose(c,"Договоры № ABSENT-674; № ABSENT-675; ИНН 9900000017");assertEquals(Status.REVIEW,engine.match(c,1).status());
        }finally{c.rollback();}}
    }
    @Test void jdbcRowLimitCannotCreateFalseUniquenessAndCursorFailureNeverBecomesNoContract()throws Exception{
        try(var c=ExperimentDatabase.connect()){try{
            number(c,101,"PX-672");number(c,102,"PX-672.");purpose(c,"Договор № PX-672.; ИНН 9900000017");
            var limited=engine.match(fault(c,true),1);assertEquals(Status.REVIEW,limited.status());assertNull(limited.proposedContractId());
            SafeDiscoveryTest.passed(limited,"INPUT_VALID");assertEquals(PurposeParser.Completeness.INCOMPLETE,limited.searchEvidence().completeness());
            assertTrue(limited.checks().stream().anyMatch(k->k.code().equals("CANDIDATE_SEARCH_COMPLETE")&&!k.passed()));
            assertThrows(SQLTransientException.class,()->engine.match(fault(c,false),1));
            assertEquals(Status.REVIEW,engine.match(c,1).status(),"Normal full search still finds both after injected failure");
        }finally{c.rollback();}}
    }
    @Test void serverSessionSelectLimitIsOverriddenByTheExplicitDetectableBudget()throws Exception{
        try(var c=ExperimentDatabase.connect()){try{
            number(c,101,"PX-672");number(c,102,"PX-672.");purpose(c,"Договор № PX-672.; ИНН 9900000017");
            try(var s=c.createStatement()){s.execute("SET SESSION sql_select_limit=1");}
            var d=engine.match(c,1);assertEquals(Status.REVIEW,d.status());assertEquals(List.of(101L,102L),d.candidateIds());
            SafeDiscoveryTest.passed(d,"CANDIDATE_SEARCH_COMPLETE");
        }finally{try(var s=c.createStatement()){s.execute("SET SESSION sql_select_limit=DEFAULT");}c.rollback();}}
    }
    @Test void rowBudgetOverflowIsExplicitIncompleteEvidenceNotAnEmptyCompleteSearch()throws Exception{
        try(var c=ExperimentDatabase.connect()){try{
            String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
            try(var s=c.createStatement()){
                assertEquals(5000,s.executeUpdate("INSERT INTO contract(id,contract_type_id,contract_number,contract_date) SELECT 73000000+a.n+10*b.n+100*d.n+1000*e.n,1,'PX-OVER-676','2026-01-01' FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" d CROSS JOIN "+digits+" e WHERE a.n+10*b.n+100*d.n+1000*e.n<5000"));
                assertEquals(9999,s.executeUpdate("INSERT INTO contract_party(contract_id,role_id,client_id) SELECT c.id,r.id,CASE WHEN r.code='PAYER' THEN 1 ELSE 2 END FROM contract c JOIN contract_party_role r ON r.code IN ('PAYER','RECIPIENT') WHERE c.id BETWEEN 73000000 AND 73004999 AND NOT (c.id=73004999 AND r.code='RECIPIENT')"));
            }
            purpose(c,"Договор № PX-OVER-676, ИНН 9900000017");
            var expectedIds = java.util.stream.LongStream.range(73000000, 73005000).boxed().toList();
            for (var role : List.of("RECIPIENT", "BENEFICIARY")) {
                var complete = engine.match(c,1);
                assertEquals(Status.REVIEW, complete.status());
                assertEquals(expectedIds, complete.candidateIds());
                assertEquals(PurposeParser.Completeness.COMPLETE, complete.searchEvidence().completeness());
                SafeDiscoveryTest.passed(complete,"CANDIDATE_SEARCH_COMPLETE");
                int client = role.equals("RECIPIENT") ? 2 : 1;
                try (var add = c.prepareStatement("""
                        INSERT INTO contract_party(contract_id,role_id,client_id)
                        SELECT 73004999,id,? FROM contract_party_role WHERE code=?
                        """)) {
                    add.setInt(1, client);
                    add.setString(2, role);
                    assertEquals(1, add.executeUpdate());
                }
            }
            var d=engine.match(c,1);
            assertEquals(Status.REVIEW,d.status());
            assertEquals(expectedIds,d.candidateIds());
            assertEquals(PurposeParser.Completeness.INCOMPLETE,d.searchEvidence().completeness());
            assertTrue(d.searchEvidence().diagnostic().contains("budget"));assertNull(d.proposedContractId());
            assertTrue(d.checks().stream().anyMatch(k->k.code().equals("CANDIDATE_SEARCH_COMPLETE")&&!k.passed()));
            assertThrows(SQLNonTransientException.class,()->new JdbcMatchingRepository().candidates(c,new PurposeParser().parse("Договор № PX-OVER-676")));
        }finally{c.rollback();}}
    }
    @Test void incompleteEvidenceRetainsUsefulCandidatesWithoutCertifyingAbsenceOrUniqueness()throws Exception{
        try(var c=ExperimentDatabase.connect()){try{
            number(c,101,"PX-672");purpose(c,"Без договора; договор PX-672, ИНН 9900000017");
            var d=engine.match(c,1);assertEquals(Status.REVIEW,d.status());assertEquals(List.of(101L),d.candidateIds());
            assertEquals(PurposeParser.Completeness.INCOMPLETE,d.searchEvidence().completeness());assertFalse(d.searchEvidence().searchedKeys().isEmpty());
            assertTrue(d.checks().stream().anyMatch(k->k.code().equals("CONTRACT_ANALYSIS_COMPLETE")&&!k.passed()));
            purpose(c,"Без договора; договор ABSENT-678, ИНН 9900000017");var absent=engine.match(c,1);
            assertEquals(Status.REVIEW,absent.status());assertTrue(absent.candidateIds().isEmpty());assertEquals(PurposeParser.Completeness.INCOMPLETE,absent.searchEvidence().completeness());
        }finally{c.rollback();}}
    }
    @Test void inflectedRoleAndFullNameLabelsCannotHideForeignNamesOrExtraComponents()throws Exception{
        try(var c=ExperimentDatabase.connect()){try{
            try(var s=c.createStatement()){s.executeUpdate("UPDATE client SET last_name='Brown',first_name='John',middle_name='George' WHERE id=1");}
            for(String label:List.of("Плательщика:","Получателю:","Бенефициара:","Выгодоприобретателя:","В пользу","FIO:","Full name:","Recipient Full name:")){
                purpose(c,"Договор D-001, ИНН 9900000017; "+label+" Wrong John George");
                var d=engine.match(c,1);assertEquals(Status.REVIEW,d.status(),d.toString());SafeDiscoveryTest.passed(d,"CANDIDATE_SEARCH_COMPLETE");
                SafeDiscoveryTest.passed(d,"PURPOSE_INN");assertTrue(d.checks().stream().anyMatch(k->k.code().equals("PURPOSE_NAMES")&&!k.passed()));
            }
            for(String label:List.of("ФИО:","Full name:")){
                purpose(c,"Договор D-001, ИНН 9900000017; "+label+" Brown John George Extra");var d=engine.match(c,1);
                assertEquals(Status.REVIEW,d.status());assertTrue(d.features().names().getFirst().surface().endsWith("Extra"));
                assertTrue(d.checks().stream().anyMatch(k->k.code().equals("PURPOSE_NAMES")&&!k.passed()));
            }
        }finally{c.rollback();}}
    }
    static Connection fault(Connection c,boolean limit){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try{
                Object value=method.invoke(c,args);
                if(method.getName().equals("prepareStatement") && args[0].toString().contains("FROM contract c")){
                    PreparedStatement actual=(PreparedStatement)value;if(limit){actual.setMaxRows(1);return actual;}
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                        try{Object result=m.invoke(actual,a);if(!m.getName().equals("executeQuery"))return result;
                            ResultSet rs=(ResultSet)result;int[] calls={0};return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(pp,mm,aa)->{
                                if(mm.getName().equals("next")&&++calls[0]>1)throw new SQLTransientException("Injected real-JDBC cursor failure after first row");
                                try{return mm.invoke(rs,aa);}catch(InvocationTargetException failure){throw failure.getCause();}});
                        }catch(InvocationTargetException failure){throw failure.getCause();}});
                }return value;
            }catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
}
