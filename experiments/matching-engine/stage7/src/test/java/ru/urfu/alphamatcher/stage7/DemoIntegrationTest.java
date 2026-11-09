package ru.urfu.alphamatcher.stage7;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.sql.SQLException;
import ru.urfu.alphamatcher.experiment.*;
@EnabledIfEnvironmentVariable(named="STAGE7_RUN_DIR",matches=".+")
class DemoIntegrationTest {
    DemoStore store;DemoRuns runs;DataImport.Dataset data;
    @BeforeEach void open() throws Exception{
        store=new DemoStore();runs=new DemoRuns(store,new EngineIdentity());data=DataImport.parse(DataImportTest.sample());
        store.replace(data);
        runs.prepare(data).get(15,TimeUnit.SECONDS);assertEquals(DemoRuns.State.READY,runs.snapshot().state(),runs.snapshot().message());
    }
    @AfterEach void close(){runs.close();}
    @Test void completeRealEngineRunAndDashboard() throws Exception{
        runs.run().get(30,TimeUnit.SECONDS);var s=runs.snapshot();assertEquals(DemoRuns.State.COMPLETE,s.state(),s.message());assertTrue(s.databaseUnchanged());
        assertEquals(List.of("SAFE","REVIEW","NO_CONTRACT","REVIEW","REVIEW","REVIEW","TECHNICAL_ERROR"),s.results().stream().map(DemoRuns.Result::status).toList());
        var m=DemoRuns.Metrics.of(s.results());assertEquals(7,m.total());assertEquals(1,m.safe());assertEquals(4,m.review());assertEquals(1,m.noContract());assertEquals(1,m.errors());assertEquals(100.0/7,m.safePercent(),0.00001);
        assertTrue(s.elapsedNanos()>0);assertNull(s.results().getLast().decision());assertTrue(s.results().getLast().injectedFault());
        assertEquals(PurposeParser.Completeness.INCOMPLETE,s.results().get(4).decision().searchEvidence().completeness());
    }
    @Test void fullDecisionsMatchDirectCanonicalCallsAndDatabaseIsUnchanged() throws Exception{
        var before=store.snapshot();runs.run().get(30,TimeUnit.SECONDS);
        try(var direct=MatchingEngine.openReadOnlySession()){
            for(var row:runs.snapshot().results())if(!row.injectedFault())assertEquals(direct.match(row.id()),row.decision());
        }
        assertEquals(before,store.snapshot());
        for(var row:runs.snapshot().results())if(row.decision()!=null){
            assertEquals(row.decision().candidateIds(),row.candidates().stream().map(c->c.id()).toList());
            if(row.status().equals("SAFE"))assertTrue(row.decision().checks().stream().allMatch(c->c.passed()));
        }
    }
    @Test void validOwnImportAndInvalidImportDoNotDestroyPreparedState() throws Exception{
        String json=new String(DataImportTest.sample(),java.nio.charset.StandardCharsets.UTF_8).replace("Северные системы","Пользовательский набор");
        var own=DataImport.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));runs.prepare(own).get(15,TimeUnit.SECONDS);
        var before=store.snapshot();assertThrows(IllegalArgumentException.class,()->DataImport.parse("{}".getBytes()));
        assertEquals(before,store.snapshot());assertEquals(DemoRuns.State.READY,runs.snapshot().state());
        runs.run().get(30,TimeUnit.SECONDS);assertEquals("SAFE",runs.snapshot().results().getFirst().status());
    }
    @Test void repeatRunResetAndReaderCannotWrite() throws Exception{
        runs.run().get(30,TimeUnit.SECONDS);var first=runs.snapshot().results().stream().map(DemoRuns.Result::status).toList();
        runs.run().get(30,TimeUnit.SECONDS);assertEquals(first,runs.snapshot().results().stream().map(DemoRuns.Result::status).toList());
        var before=store.snapshot();
        try(var c=ExperimentDatabase.connect();var statement=c.createStatement()){
            assertThrows(SQLException.class,()->statement.executeUpdate("DELETE FROM client"));c.rollback();
        }
        assertEquals(before,store.snapshot());runs.reset();assertEquals(DemoRuns.State.EMPTY,runs.snapshot().state());assertTrue(runs.snapshot().results().isEmpty());
    }
    @Test void emptyContractCatalogueProducesRealAbsenceRatherThanFakeSuccess() throws Exception{
        runs.prepare(new DataImport.Dataset("Пустой каталог",data.clients(),List.of(),data.payments())).get(15,TimeUnit.SECONDS);
        runs.run().get(30,TimeUnit.SECONDS);
        assertEquals("NO_CONTRACT",runs.snapshot().results().getFirst().status());
        assertTrue(runs.snapshot().results().getFirst().candidates().isEmpty());
        assertTrue(runs.snapshot().databaseUnchanged());
    }
    @Test void overlappingStartsAndResetAreDenied() throws Exception{
        var many=new ArrayList<DataImport.Payment>();var template=data.payments().getFirst();
        for(int n=1;n<=300;n++)many.add(new DataImport.Payment(n,template.purpose(),template.payer(),template.recipient(),DataImport.Fault.NONE));
        runs.prepare(new DataImport.Dataset("Bounded concurrency",data.clients(),data.contracts(),many)).get(20,TimeUnit.SECONDS);
        var task=runs.run();assertThrows(IllegalStateException.class,()->runs.run());assertThrows(IllegalStateException.class,runs::reset);
        task.get(40,TimeUnit.SECONDS);assertEquals(300,runs.snapshot().results().size());assertTrue(runs.snapshot().databaseUnchanged());
    }
}
