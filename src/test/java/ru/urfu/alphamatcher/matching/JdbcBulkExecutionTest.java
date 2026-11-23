package ru.urfu.alphamatcher.matching;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.urfu.alphamatcher.demo.DemoOperationState.Query;
import ru.urfu.alphamatcher.demo.DemoUser;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="M7_EXECUTION_QA", matches="true")
class JdbcBulkExecutionTest {
    private static final String URL="jdbc:mysql://127.0.0.1:33368/alpha_matcher_ui_demo"
            +"?useSSL=false&allowPublicKeyRetrieval=true";
    private static final DemoUser USER=DemoUser.ROZHKOV;
    private enum Fault { BEFORE_COMMIT, AFTER_COMMIT }

    @Test void commitReconciliationProtectsLinksAndAudit() throws Exception {
        var fault=new AtomicReference<Fault>();
        var preview=new JdbcBulkPreview(source(fault));
        var run=preview.execution();
        String id=ready(preview);
        preview.selectAll(USER,id,false);
        long safe=preview.page(USER,id,0).rows().stream()
                .filter(row -> "EXP-001".equals(row.reference()) && "SAFE".equals(row.status()))
                .findFirst().orElseThrow().id();
        preview.select(USER,id,safe,true);
        long token=preview.page(USER,id,0).job().selectionToken();
        assertThrows(IllegalStateException.class,() -> run.authorize(USER,id,token-1));
        assertEquals("RUNNING",run.authorize(USER,id,token).status());
        assertThrows(IllegalStateException.class,() -> preview.select(USER,id,safe,false));
        fault.set(Fault.BEFORE_COMMIT);
        assertThrows(IllegalStateException.class,() -> run.step(USER,id));
        assertEquals(0,run.progress(USER,id).processed());
        assertEquals(0,query("SELECT COUNT(*) FROM bulk_execution_result"));
        assertEquals(0,query("SELECT COUNT(*) FROM operation WHERE reference='EXP-001' AND contract_id IS NOT NULL"));

        fault.set(Fault.AFTER_COMMIT);
        assertThrows(IllegalStateException.class,() -> run.step(USER,id));
        var restarted=new JdbcBulkPreview(source(new AtomicReference<>())).execution();
        assertEquals(1,restarted.progress(USER,id).applied());
        assertEquals("COMPLETED",restarted.step(USER,id).status());
        assertEquals("COMPLETED",restarted.authorize(USER,id,token).status());
        assertEquals(1,query("SELECT COUNT(*) FROM bulk_execution_result WHERE outcome='APPLIED' AND applied_contract=101"));
        assertEquals(1,query("SELECT COUNT(*) FROM operation WHERE reference='EXP-001' AND contract_id=101"));
        var application=new JdbcDemoDataService(URL,"ui_demo",System.getenv("ALPHA_DEMO_PASSWORD"),
                System.getenv("ALPHA_DEMO_OWNER"),true);
        assertEquals("DB-101",application.state().match("EXP-001").contractId());
        assertEquals(1,application.state().auditEventsForOperation("EXP-001").stream()
                .filter(event -> id.equals(event.runId())).count());
        var audit = new ru.urfu.alphamatcher.demo.DemoReportingService();
        var firstPage = audit.audit(application.state(),"EXP-001",USER.email(),"Автоматическая привязка",true,0,10);
        assertEquals(1,firstPage.total());
        assertEquals(id,firstPage.rows().getFirst().runId());
        assertTrue(audit.actors(application.state()).contains(USER.email()));

        String stale=ready(preview);
        update("UPDATE contract SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=101");
        assertEquals("REQUIRES_REVIEW",run.authorize(USER,stale,preview.page(USER,stale,0).job().selectionToken()).status());
        String permissions=ready(preview);
        run.authorize(USER,permissions,preview.page(USER,permissions,0).job().selectionToken());
        update("UPDATE bulk_executor_permission SET can_apply=FALSE WHERE employee='a.rozhkov@bank.internal'");
        try {
            assertEquals("REQUIRES_REVIEW",run.step(USER,permissions).status());
            assertEquals(0,run.progress(USER,permissions).processed());
        } finally {
            update("UPDATE bulk_executor_permission SET can_apply=TRUE WHERE employee='a.rozhkov@bank.internal'");
        }
        String cancelled=ready(preview);
        run.authorize(USER,cancelled,preview.page(USER,cancelled,0).job().selectionToken());
        assertEquals("CANCELLED",run.cancel(USER,cancelled).status());
        assertEquals("CANCELLED",run.step(USER,cancelled).status());
        assertEquals(1,query("SELECT COUNT(*) FROM bulk_execution_result"));

        String mixed=ready(preview);
        run.authorize(USER,mixed,preview.page(USER,mixed,0).job().selectionToken());
        try (var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=workers.submit(() -> run.step(USER,mixed));
            var second=workers.submit(() -> restarted.step(USER,mixed));
            assertEquals(java.util.Set.of(64L,128L),java.util.Set.of(first.get().processed(),second.get().processed()));
        }
        assertEquals(129,query("SELECT COUNT(*) FROM bulk_execution_result"));
        assertEquals(2,run.progress(USER,mixed).packages());
        var stopped=run.cancel(USER,mixed);
        assertEquals("PARTIALLY_COMPLETED",stopped.status());
        assertEquals(128,run.step(USER,mixed).processed());
        assertEquals(stopped.applied()+1,query("SELECT COUNT(*) FROM bulk_execution_result WHERE outcome='APPLIED'"));

        LocalDate day=LocalDate.of(2026,10,5);
        var sparse=new Query(" m65-00140 ",null,null,"","","recipient",true);
        var filtered=waitForReady(preview,preview.startFiltered(USER,sparse));
        assertEquals(java.util.List.of("M65-00140"),filtered.rows().stream().map(JdbcBulkPreview.Result::reference).toList());
        var automatic=new Query("exp-001",null,null,"Обработана","Автоматически","date",false);
        var applied=waitForReady(preview,preview.startFiltered(USER,automatic));
        assertEquals("SKIPPED",applied.rows().getFirst().status());
        var incompatible=new Query("exp-001",day,day,"Не обработана","Автоматически","date",false);
        assertEquals(0,waitForReady(preview,preview.startFiltered(USER,incompatible)).job().inspected());

        var opens=new AtomicInteger();
        var workerPreview=new JdbcBulkPreview(source(new AtomicReference<>(),opens));
        var review=waitForReady(workerPreview,workerPreview.startFiltered(USER,
                new Query("exp-002",null,null,"","","date",false)));
        assertEquals("REVIEW",review.rows().getFirst().status());
        var worker=workerPreview.execution();
        worker.authorize(USER,review.job().id(),review.job().selectionToken());
        int previousOpens=opens.get();
        worker.execute(USER,review.job().id(),() -> true);
        assertEquals(previousOpens+1,opens.get());
        assertEquals(1,worker.progress(USER,review.job().id()).processed());
        assertEquals(0,worker.progress(USER,review.job().id()).applied());
        var partialReview=waitForReady(workerPreview,workerPreview.startFiltered(USER,
                new Query("exp-002",null,null,"","","date",false)));
        worker.authorize(USER,partialReview.job().id(),partialReview.job().selectionToken());
        worker.step(USER,partialReview.job().id());
        assertEquals("PARTIALLY_COMPLETED",worker.cancel(USER,partialReview.job().id()).status());
    }

    private static String ready(JdbcBulkPreview preview) {
        return waitForReady(preview,preview.start(USER,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),true)).job().id();
    }

    private static JdbcBulkPreview.Page waitForReady(JdbcBulkPreview preview, String id) {
        return assertTimeoutPreemptively(Duration.ofSeconds(20),() -> {
            while (true) {
                var page=preview.page(USER,id,0);
                if (!"PREPARING".equals(page.job().status())) {
                    assertEquals("READY",page.job().status());
                    return page;
                }
                Thread.sleep(30);
            }
        });
    }

    private static void update(String sql) throws SQLException {
        try (var c=connection();var stmt=c.createStatement()) { assertEquals(1,stmt.executeUpdate(sql)); }
    }

    private static long query(String sql) throws SQLException {
        try (var c=connection();var stmt=c.createStatement();var rows=stmt.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(URL,"ui_demo",System.getenv("ALPHA_DEMO_PASSWORD"));
    }

    private static DataSource source(AtomicReference<Fault> fault) {
        return source(fault,new AtomicInteger());
    }

    private static DataSource source(AtomicReference<Fault> fault,AtomicInteger opens) {
        var original=new MysqlDataSource();
        original.setURL(URL);
        original.setUser("ui_demo");
        original.setPassword(System.getenv("ALPHA_DEMO_PASSWORD"));
        return (DataSource)Proxy.newProxyInstance(JdbcBulkExecutionTest.class.getClassLoader(),new Class<?>[]{DataSource.class},
                (proxy,method,args) -> {
                    Object value=invoke(original,method,args);
                    if (!(value instanceof Connection connection)) return value;
                    opens.incrementAndGet();
                    return Proxy.newProxyInstance(JdbcBulkExecutionTest.class.getClassLoader(),new Class<?>[]{Connection.class},
                            (unused,command,parameters) -> {
                                if ("commit".equals(command.getName())) {
                                    Fault next=fault.getAndSet(null);
                                    if (next!=null) {
                                        if (next==Fault.AFTER_COMMIT) connection.commit();
                                        connection.close();
                                        throw new SQLException("Проверочный обрыв соединения","08S01");
                                    }
                                }
                                return invoke(connection,command,parameters);
                            });
                });
    }

    private static Object invoke(Object receiver,java.lang.reflect.Method method,Object[] args) throws Throwable {
        try { return method.invoke(receiver,args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
