package ru.urfu.alphamatcher.matching;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.urfu.alphamatcher.demo.*;
import ru.urfu.alphamatcher.experiment.*;

@EnabledIfEnvironmentVariable(named="ALPHA_DEMO_PASSWORD", matches=".+")
class JdbcEngineApplicationTest {
    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    void resetReservedWorkflowFixtureState() throws Exception {
        try(var c=connection();var s=c.createStatement()) {
            s.executeUpdate("UPDATE operation SET contract_id=NULL WHERE id IN (1,2)");
            s.executeUpdate("UPDATE ui_demo_state SET employee_snapshot=NULL WHERE id=1");
        }
    }
    private static final String URL="jdbc:mysql://127.0.0.1:33368/alpha_matcher_ui_demo?useSSL=false&allowPublicKeyRetrieval=true";
    private JdbcDemoDataService source() throws Exception {
        return new JdbcDemoDataService(URL,"ui_demo",System.getenv("ALPHA_DEMO_PASSWORD"),System.getenv("ALPHA_DEMO_OWNER"),true);
    }
    private Connection connection() throws Exception { return DriverManager.getConnection(URL,"ui_demo",System.getenv("ALPHA_DEMO_PASSWORD")); }
    private DemoMatchingService.Preview preview(DemoMatchingService service,DemoOperationState state) {
        return service.preview(state,LocalDate.of(2026,10,1),LocalDate.of(2026,10,1),true,true);
    }

    @Test void everyPresentationDecisionEqualsDirectEngineIncludingEvidence() throws Exception {
        var data=source();var service=new DemoMatchingService(data);
        int links=data.state().linkedCount();
        var preview=preview(service,data.state());
        assertEquals(20,preview.rows().size());
        try(var c=connection()) {
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
            for(var row:preview.rows()) {
                assertNotNull(row.decision());
                long id=Long.parseLong(row.operationId().substring(4));
                assertEquals(engine.match(c,id),row.decision());
                assertEquals(row.decision().candidateIds().stream().map(data::contractReference).toList(),row.candidates());
            }
            c.rollback();
        }
        var eligible=service.preview(data.state(),LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),false,true);
        assertNull(eligible.find("EXP-011"));assertNull(eligible.find("EXP-020"));
        assertEquals(links,data.state().linkedCount());
        assertEquals(MatchingModel.Status.SAFE,preview.find("EXP-001").decision().status());
        assertEquals(MatchingModel.Status.REVIEW,preview.find("EXP-002").decision().status());
        assertEquals(MatchingModel.Status.NO_CONTRACT,preview.find("EXP-003").decision().status());
        assertTrue(preview.find("EXP-007").decision().features().thirdParty());
    }

    @Test void confirmationPersistsThenUnlinkAndManualQueueRemainCoherent() throws Exception {
        var data=source();var state=data.state();var service=new DemoMatchingService(data);
        var proposal=preview(service,state);
        assertFalse(state.isLinked("EXP-001"));
        service.apply(state,proposal,Set.of("EXP-001"),DemoUser.ISAEV.fullName());
        assertEquals("DB-101",state.match("EXP-001").contractId());
        assertEquals(state.match("EXP-001"),source().state().match("EXP-001"));
        assertEquals(state.auditEvents(),source().state().auditEvents());
        assertEquals(MatchingModel.Status.SKIPPED,data.analyze("EXP-001").status());
        assertThrows(IllegalStateException.class,()->service.apply(state,proposal,Set.of("EXP-001"),"Test"));
        state.unlinkIfUnchanged("EXP-001",state.match("EXP-001"));
        assertEquals(MatchingModel.Status.SAFE,data.analyze("EXP-001").status());
        assertEquals(DemoMatchingService.Status.ALREADY_MANUAL,preview(service,state).find("EXP-001").status());
        assertFalse(state.queueForManual("EXP-001"));
        assertEquals(1,state.batchRuns().size());
        new DemoManualReviewService(data).confirm("EXP-001","DB-101","Проверены синтетические документы",true,state);
        assertEquals("Вручную",state.match("EXP-001").method());
        state.unlinkIfUnchanged("EXP-001",state.match("EXP-001"));
    }

    @Test void failedBatchRollsBackBothSqlAndEmployeeState() throws Exception {
        var data=source();var state=data.state();
        assertThrows(IllegalStateException.class,()->data.confirmAtomically(state,()->{
            try(var other=connection();var s=other.createStatement()) {
                s.execute("SET SESSION innodb_lock_wait_timeout=1");
                assertThrows(SQLException.class,()->s.executeUpdate("UPDATE operation SET payment_purpose='Concurrent change' WHERE id=1"));
            } catch(Exception e) { throw new AssertionError(e); }
            state.link("EXP-001","DB-101","Вручную",data.contracts(),"Проверены синтетические документы");
            throw new IllegalStateException("Injected failure after SQL update");
        }));
        assertNull(state.match("EXP-001"));assertTrue(state.auditEvents().isEmpty());
        assertEquals(MatchingModel.Status.SAFE,data.analyze("EXP-001").status());
    }

    @Test void changedSqlInputRejectsOldPreviewWithoutPartialApply() throws Exception {
        var data=source();var state=data.state();var service=new DemoMatchingService(data);
        var old=preview(service,state);
        String original=state.operation("EXP-001").purpose();
        try(var c=connection();var update=c.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=1")) {
            update.setString(1,"Оплата за третье лицо по договору D-001");update.executeUpdate();
            try {
                assertThrows(IllegalStateException.class,()->service.apply(state,old,Set.of("EXP-001"),"Test"));
                assertThrows(IllegalStateException.class,()->new DemoManualReviewService(data).confirm("EXP-001","DB-101","Проверены документы",true,state,old.find("EXP-001").decision()));
            }
            finally { update.setString(1,original);update.executeUpdate(); }
        }
        assertNull(state.match("EXP-001"));assertTrue(state.batchRuns().isEmpty());
    }

    @Test void changedAmountRejectsOldPreview() throws Exception {
        var data = source();
        var state = data.state();
        var service = new DemoMatchingService(data);
        var old = preview(service, state);
        var originalAmount = state.operation("EXP-001").amount();
        try (var c = connection();
             var update = c.prepareStatement("UPDATE operation SET amount_rub=? WHERE id=1")) {
            update.setBigDecimal(1, originalAmount.add(java.math.BigDecimal.ONE));
            assertEquals(1, update.executeUpdate());
            try {
                assertThrows(IllegalStateException.class,
                        () -> service.apply(state, old, Set.of("EXP-001"), "Test"));
            } finally {
                update.setBigDecimal(1, originalAmount);
                update.executeUpdate();
            }
        }
        assertFalse(state.isLinked("EXP-001"));
        assertTrue(state.auditEvents().isEmpty());
        assertTrue(state.batchRuns().isEmpty());
        assertEquals(MatchingModel.Status.SAFE, source().analyze("EXP-001").status());
    }

    @Test void staleServiceCannotOverwriteCommittedAudit() throws Exception {
        var first = source();
        var second = source();
        var applying = new DemoMatchingService(first);
        var queuing = new DemoMatchingService(second);
        var old = preview(queuing, second.state());
        applying.apply(first.state(), preview(applying, first.state()), Set.of("EXP-001"), DemoUser.ISAEV.fullName());

        assertThrows(IllegalStateException.class,
                () -> queuing.sendToManual(second.state(), old, Set.of("EXP-002"), DemoUser.ROZHKOV.fullName()));
        assertFalse(second.state().isManualQueued("EXP-002"));
        assertTrue(second.state().auditEvents().isEmpty());
        var fresh = source().state();
        assertEquals(first.state().auditEvents(), fresh.auditEvents());
        assertEquals(first.state().batchRuns(), fresh.batchRuns());
        assertEquals(first.state().match("EXP-001"), fresh.match("EXP-001"));
    }

    @Test void changeLinkExcludesConflictingInnEvenWithTheSameMasterClientIds() throws Exception {
        var data = source();
        var service = new DemoMatchingService(data);
        var state = data.state();
        service.apply(state, preview(service, state), Set.of("EXP-001"), DemoUser.ISAEV.fullName());
        var original = state.match("EXP-001");
        assertFalse(state.contractsForLinkChange("EXP-001", data.contracts()).contains("DB-107"));
        assertThrows(IllegalArgumentException.class, () -> state.changeLinkIfUnchanged("EXP-001", original,
                "DB-107", data.contracts(), "Обоснование синтетического решения"));
        assertEquals(original, state.match("EXP-001"));
        assertEquals(1, state.auditEvents().size());
        state.changeLinkIfUnchanged("EXP-001", original, "DB-109", data.contracts(), "Проверены стороны и документы");
        assertEquals("DB-109", source().state().match("EXP-001").contractId());
    }

    @Test void contractRegistrySortsJdbcDatesAndRetainsRawPartyRequisites() throws Exception {
        var data = source();
        var contracts = new DemoContractService(data);
        for (boolean descending : new boolean[]{false, true}) {
            var sorted = contracts.search(new DemoContractService.Query("", "", "date", descending),
                    0, 20, data.state()).rows();
            var dated = sorted.stream().map(DemoDataService.Contract::date).filter(date -> !date.isBlank()).toList();
            assertEquals(dated.stream().sorted(descending ? java.util.Comparator.reverseOrder()
                    : java.util.Comparator.naturalOrder()).toList(), dated);
            assertTrue(sorted.getLast().date().isBlank());
        }
        var raw = data.contract("DB-105");
        assertEquals("9900000017", data.contractParty(raw, true, data.state()).inn());
        assertEquals("9900000024", data.contractParty(raw, false, data.state()).inn());
    }

    @Test void sqlRegistryMatchesLegacySearchAcrossPagesAndBoundaryInputs() throws Exception {
        var data = source();
        var legacy = new DemoOperationState(data.fixtures());
        var jdbc = data.state();
        for (String sort : java.util.List.of("date", "id", "amount", "payer", "recipient", "unknown")) {
            for (boolean descending : new boolean[]{false,true}) {
                for (int page : new int[]{-5,0,1,Integer.MAX_VALUE}) {
                    var query = new DemoOperationState.Query("", null, null, "", "", sort, descending);
                    assertEquals(legacy.search(query,page,5), jdbc.registry(query,page,5).page(), query.toString());
                }
            }
        }
        for (String text : java.util.List.of("EXP-00", "9900000017", "ООО", "null", "%_", "row missing")) {
            var query = new DemoOperationState.Query(text,LocalDate.of(2026,10,1),LocalDate.of(2026,10,1),
                    "Не обработана","","date",true);
            var result=jdbc.registry(query,900,3);
            assertEquals(legacy.search(query,900,3),result.page());
            assertEquals(20,result.catalogTotal());
        }
        assertThrows(IllegalArgumentException.class,()->jdbc.registry(
                new DemoOperationState.Query("",null,null,"","","id",false),0,Integer.MAX_VALUE));
    }

    @Test void unicodeSearchAndOpenedCardUseExactVersions() throws Exception {
        var data=source();
        var state=data.state();
        var shown=state.operation("EXP-005");
        try (var c=connection(); var update=c.prepareStatement(
                "UPDATE operation SET payment_purpose=?,updated_at=updated_at WHERE id=5")) {
            try {
                update.setString(1,"ΙΣ İ Ё É");
                assertEquals(1,update.executeUpdate());
                assertThrows(IllegalStateException.class,()->data.requireCurrentOperation(shown));
                assertEquals("ΙΣ İ Ё É", state.operation("EXP-005").purpose());
                var legacy=new DemoOperationState(data.fixtures());
                for (String word : java.util.List.of("ις","i\u0307","ё","é","a")) {
                    var query=new DemoOperationState.Query(word,null,null,"","","id",false);
                    assertEquals(legacy.search(query,0,2),state.registry(query,0,2).page(),word);
                }
            } finally {
                update.setString(1,shown.purpose());
                assertEquals(1,update.executeUpdate());
            }
        }
    }

    @Test void enteringPeriodAfterSelectionRejectsTheEntireOldBatch() throws Exception {
        var data=source();
        var matching=new DemoMatchingService(data);
        var state=data.state();
        var original=state.operation("EXP-020").timestamp();
        try (var c=connection(); var update=c.prepareStatement(
                "UPDATE operation SET operation_at=?,updated_at=updated_at WHERE id=20")) {
            try {
                update.setObject(1,original.plusDays(1));
                assertEquals(1,update.executeUpdate());
                var selection=preview(matching,state);
                assertNull(selection.find("EXP-020"));
                update.setObject(1,original);
                assertEquals(1,update.executeUpdate());
                assertThrows(IllegalStateException.class,()->matching.apply(state,selection,Set.of("EXP-001"),"QA"));
                assertFalse(state.isLinked("EXP-001"));
                assertTrue(state.auditEvents().isEmpty());
                assertTrue(state.batchRuns().isEmpty());
            } finally {
                update.setObject(1,original);
                update.executeUpdate();
            }
        }
    }

    @Test void methodFilterUsesConfirmedAuditStateAndLivePagesClampAfterChange() throws Exception {
        var data=source();
        var state=data.state();
        var matching=new DemoMatchingService(data);
        matching.apply(state,preview(matching,state),Set.of("EXP-001"),"QA");
        for (String method : java.util.List.of("Автоматически","Вручную")) {
            var filter=new DemoOperationState.Query("",null,null,"Обработана",method,"date",true);
            var rows=state.registry(filter,100,1);
            assertEquals(0,rows.page().page());
            assertEquals(1,rows.page().total());
            assertEquals("Автоматически".equals(method)?"EXP-001":"EXP-010",rows.page().rows().getFirst().id());
        }
        var query=new DemoOperationState.Query("",LocalDate.MAX,LocalDate.MAX,"","","date",true);
        assertEquals(0,state.registry(query,0,10).page().total());
    }
}
