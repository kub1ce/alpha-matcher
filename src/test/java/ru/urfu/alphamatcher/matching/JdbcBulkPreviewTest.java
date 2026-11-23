package ru.urfu.alphamatcher.matching;

import java.sql.DriverManager;
import java.sql.Connection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.urfu.alphamatcher.demo.DemoUser;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="M7_BULK_QA", matches="true")
class JdbcBulkPreviewTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:33368/alpha_matcher_ui_demo"
            + "?useSSL=false&allowPublicKeyRetrieval=true";
    private static final DemoUser OWNER = DemoUser.ROZHKOV;
    private static final LocalDate FROM = LocalDate.of(2026,10,1);
    private static final LocalDate TO = LocalDate.of(2026,10,31);

    private JdbcBulkPreview service() throws Exception {
        var data = new JdbcDemoDataService(URL,"ui_demo",System.getenv("ALPHA_DEMO_PASSWORD"),
                System.getenv("ALPHA_DEMO_OWNER"),true);
        assertTrue(data.bulkPreview().available());
        return data.bulkPreview();
    }

    private static JdbcBulkPreview.Page finished(JdbcBulkPreview jobs, String id) {
        return assertTimeoutPreemptively(Duration.ofSeconds(40),() -> {
            var page=jobs.page(OWNER,id,0);
            while ("PREPARING".equals(page.job().status())) {
                Thread.sleep(50);
                page=jobs.page(OWNER,id,0);
            }
            return page;
        });
    }

    @Test void persistsCompleteResultsAndSelectionAcrossServiceRestart() throws Exception {
        var jobs=service();
        var id=jobs.start(OWNER,FROM,TO,true);
        var ready=finished(jobs,id);
        assertEquals("READY",ready.job().status());
        assertEquals(140,ready.job().inspected());
        assertEquals(140,ready.job().selected());
        assertTrue(ready.job().safe() > 0);
        assertTrue(ready.job().review() > 0);
        assertTrue(ready.job().absent() > 0);
        assertEquals(140,ready.job().safe()+ready.job().review()+ready.job().absent()+ready.job().skipped());
        var ids=new HashSet<Long>();
        for (int index=0;index<14;index++)
            for (var row : jobs.page(OWNER,id,index).rows()) {
                assertTrue(ids.add(row.id()));
                assertTrue(row.evidence().contains("Decision["));
            }
        assertEquals(140,ids.size());
        var first=ready.rows().getFirst();
        jobs.select(OWNER,id,first.id(),false);
        jobs.select(OWNER,id,first.id(),false);
        var restarted=service();
        assertTrue(restarted.recent(OWNER).contains(id));
        assertEquals(139,restarted.page(OWNER,id,0).job().selected());
        assertFalse(restarted.page(OWNER,id,0).rows().getFirst().selected());
        assertEquals(13,restarted.page(OWNER,id,Long.MAX_VALUE).index());
        assertThrows(IllegalArgumentException.class, () -> restarted.page(DemoUser.ISAEV,id,0));
        restarted.selectAll(OWNER,id,false);
        restarted.select(OWNER,id,first.id(),true);
        assertEquals(1,restarted.page(OWNER,id,0).job().selected());
        restarted.selectAll(OWNER,id,true);
        assertEquals(140,restarted.page(OWNER,id,0).job().selected());
        assertEquals(0,restarted.recent(DemoUser.ISAEV).size());
    }

    @Test void abandonedPreparationCanBeClosedOnlyByInitiator() throws Exception {
        var jobs=service();
        String id=java.util.UUID.randomUUID().toString();
        try (var c=DriverManager.getConnection(URL,"ui_demo",System.getenv("ALPHA_DEMO_PASSWORD"));
             var q=c.prepareStatement("""
                INSERT INTO bulk_preview_job(id,initiator,from_date,to_date,include_processed,status,heartbeat_at)
                VALUES (?,?,?,?,TRUE,'PREPARING',CURRENT_TIMESTAMP-INTERVAL 3 MINUTE)
                """)) {
            q.setString(1,id);
            q.setString(2,OWNER.email());
            q.setObject(3,FROM);
            q.setObject(4,TO);
            q.executeUpdate();
        }
        assertFalse(jobs.abandonInterrupted(DemoUser.ISAEV,id));
        assertTrue(jobs.abandonInterrupted(OWNER,id));
        assertEquals("INTERRUPTED",jobs.page(OWNER,id,0).job().status());
        assertThrows(IllegalStateException.class, () -> jobs.selectAll(OWNER,id,true));
    }

    @Test void externalDateChangeBetweenPagesDoesNotChangeTheSnapshotSelection() throws Exception {
        var barrier=new BetweenPages();
        var jobs=new JdbcBulkPreview(barrier.source());
        var id=jobs.start(OWNER,FROM,TO,true);
        try (var c=connection(); var q=c.createStatement()) {
            assertTrue(barrier.reached.await(20,TimeUnit.SECONDS));
            try (var rows=q.executeQuery("SELECT id,operation_at FROM operation ORDER BY id DESC LIMIT 1")) {
                assertTrue(rows.next());
                long operation=rows.getLong(1);
                var date=rows.getTimestamp(2);
                try (var update=c.prepareStatement("UPDATE operation SET operation_at=?, updated_at=updated_at WHERE id=?")) {
                    update.setObject(1,LocalDate.of(2027,1,1).atStartOfDay());
                    update.setLong(2,operation);
                    assertEquals(1,update.executeUpdate());
                    try {
                        barrier.release.countDown();
                        var ready=finished(jobs,id);
                        assertEquals("READY",ready.job().status());
                        assertEquals(140,ready.job().inspected());
                        assertEquals(operation,jobs.page(OWNER,id,13).rows().getLast().id());
                    } finally {
                        update.setTimestamp(1,date);
                        assertEquals(1,update.executeUpdate());
                    }
                }
            }
        } finally {
            barrier.release.countDown();
        }
    }

    @Test void cancelledPreparationCannotPublishTheNextPackage() throws Exception {
        var barrier=new BetweenPages();
        var jobs=new JdbcBulkPreview(barrier.source());
        var id=jobs.start(OWNER,FROM,TO,true);
        try {
            assertTrue(barrier.reached.await(20,TimeUnit.SECONDS));
            jobs.cancel(OWNER,id);
            var cancelled=jobs.page(OWNER,id,0);
            assertEquals("CANCELLED",cancelled.job().status());
            assertEquals(64,cancelled.job().inspected());
        } finally {
            barrier.release.countDown();
        }
        assertTrue(barrier.closed.await(20,TimeUnit.SECONDS));
        assertEquals(64,jobs.page(OWNER,id,0).job().inspected());
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(URL,"ui_demo",System.getenv("ALPHA_DEMO_PASSWORD"));
    }

    private static final class BetweenPages {
        final CountDownLatch reached=new CountDownLatch(1);
        final CountDownLatch release=new CountDownLatch(1);
        final CountDownLatch closed=new CountDownLatch(1);

        DataSource source() {
            var original=new MysqlDataSource();
            original.setURL(URL);
            original.setUser("ui_demo");
            original.setPassword(System.getenv("ALPHA_DEMO_PASSWORD"));
            return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        var result=invoke(original,method,args);
                        if (!(result instanceof Connection connection)) return result;
                        var pages=new AtomicInteger();
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},
                                (unused, command, parameters) -> {
                                    if ("prepareStatement".equals(command.getName())
                                            && parameters[0] instanceof String sql && sql.startsWith("SELECT o.id FROM operation o")
                                            && pages.incrementAndGet()==2) {
                                        reached.countDown();
                                        if (!release.await(25,TimeUnit.SECONDS)) throw new AssertionError("Барьер не освобождён");
                                    }
                                    try { return invoke(connection,command,parameters); }
                                    finally {
                                        if ("close".equals(command.getName()) && pages.get()>=2) closed.countDown();
                                    }
                                });
                    });
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target,args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
