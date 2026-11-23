package ru.urfu.alphamatcher.matching;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.urfu.alphamatcher.demo.DemoManualReviewService;
import ru.urfu.alphamatcher.demo.DemoOperationState.Query;
import ru.urfu.alphamatcher.demo.DemoReportingService;
import ru.urfu.alphamatcher.demo.DemoUser;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ALPHA_LINK_CORRECTION_QA", matches = "true")
class JdbcPackageCorrectionTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:33368/alpha_matcher_ui_demo"
            + "?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String REF = "EXP-001";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final DemoUser REVIEWER = DemoUser.ROZHKOV;

    @Test void parallelMatchesPreserveOrderAndDiscardStaleSnapshots() throws Exception {
        var source = new MysqlDataSource();
        source.setURL(URL);
        source.setUser("ui_demo");
        source.setPassword(System.getenv("ALPHA_DEMO_PASSWORD"));
        var ids = List.of(19L, 4L, 2L, 11L, 3L);
        try (var connection = source.getConnection();
             var serial = new JdbcBulkMatcher(source, 1);
             var parallel = new JdbcBulkMatcher(source, 4)) {
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            long revision = JdbcBulkExecution.revision(connection, false);
            var expected = serial.match(connection, ids, revision);
            assertThrows(SQLException.class, () -> parallel.match(connection, ids, revision + 1));
            assertEquals(expected, parallel.match(connection, ids, revision));
            connection.rollback();
        }
    }

    @Test void correctionsPreserveOriginalPackageAndRestartAgainstTheCurrentLink() throws Exception {
        var initial = data(true);
        var preview = initial.bulkPreview();
        String job = preview.startFiltered(REVIEWER, filter(""));
        var ready = awaitReady(preview, job);
        assertEquals(1, ready.job().inspected());
        var execution = preview.execution();
        execution.authorize(REVIEWER, job, ready.job().selectionToken());
        execution.step(REVIEWER, job);
        assertEquals("COMPLETED", execution.step(REVIEWER, job).status());
        var original = execution.journal(REVIEWER, job, 0);
        assertEquals(1, original.size());
        var data = data(true);
        var stale = data(true);
        var state = data.state();
        var applied = state.match(REF);
        assertEquals("DB-101", applied.contractId());
        assertEquals("Автоматически", applied.method());
        assertEquals(1, state.durableAutomaticCount());
        assertEquals(1, state.registry(filter("Автоматически"), 0, 10).page().total());

        assertThrows(IllegalArgumentException.class, () -> data.confirmAtomically(state, () -> {
            state.changeLinkIfUnchanged(REF, applied, "DB-109", data.contracts(), "Проверен другой договор");
            throw new IllegalArgumentException("Имитирован отказ до записи снимка");
        }));
        assertEquals(applied, data(true).state().match(REF));
        assertEquals(1, state.auditEventsForOperation(REF).size());

        state.changeLinkIfUnchanged(REF, applied, "DB-109", data.contracts(), "Проверен другой договор");
        assertThrows(IllegalStateException.class, () -> stale.state().unlinkIfUnchanged(REF, applied));
        var changed = state.match(REF);
        assertEquals("DB-109", changed.contractId());
        assertEquals("Вручную", changed.method());
        assertEquals(0, state.registry(filter("Автоматически"), 0, 10).page().total());
        assertEquals(1, state.registry(filter("Вручную"), 0, 10).page().total());
        assertEquals(changed, data(false).state().match(REF));
        assertEquals(job, state.auditEventsForOperation(REF).stream()
                .filter(event -> event.action().equals("Изменение договора")).findFirst().orElseThrow().runId());

        state.unlinkIfUnchanged(REF, changed);
        assertNull(data(false).state().match(REF));
        assertThrows(IllegalStateException.class, () -> state.unlinkIfUnchanged(REF, changed));
        var unprocessed = new Query(REF, DAY, DAY, "Не обработана", "", "id", false);
        assertEquals(1, state.registry(unprocessed, 0, 10).page().total());
        try (var db = DriverManager.getConnection(URL, "ui_demo", System.getenv("ALPHA_DEMO_PASSWORD"));
             var update = db.createStatement()) {
            assertEquals(1, update.executeUpdate("""
                    UPDATE operation SET status_id=(SELECT id FROM operation_status WHERE code='PROCESSED')
                    WHERE id=1
                    """));
        }
        var operation = state.operation(REF);
        assertEquals(Status.SKIPPED, data.analyze(operation).status());
        var repeated = data.reanalyze(operation);
        assertEquals(Status.SAFE, repeated.status());
        assertEquals(101L, repeated.proposedContractId());
        assertEquals("PROCESSED", data.operationStatus(REF));
        assertNull(state.match(REF));
        state.analysis(REF, repeated, true);
        var manual = new DemoManualReviewService(data);
        manual.confirm(REF, "DB-101", "Повторно сверены реквизиты", true, state, repeated);
        assertThrows(IllegalStateException.class,
                () -> manual.confirm(REF, "DB-101", "Повторное подтверждение", true, state, repeated));

        var restarted = data(false);
        var restored = restarted.state();
        assertEquals("PROCESSED", restarted.operationStatus(REF));
        assertEquals("DB-101", restored.match(REF).contractId());
        assertEquals("Вручную", restored.match(REF).method());
        assertEquals(0, restored.durableAutomaticCount());
        assertEquals(0, new DemoReportingService().statistics(restored).automatic());
        assertEquals(1, restored.registry(filter("Вручную"), 0, 10).page().total());
        assertEquals(0, restored.registry(filter("Автоматически"), 0, 10).page().total());
        assertEquals(4, restored.auditEventsForOperation(REF).size());
        assertEquals(original, execution.journal(REVIEWER, job, 0));
        assertEquals("COMPLETED", execution.step(REVIEWER, job).status());
    }

    private static JdbcBulkPreview.Page awaitReady(JdbcBulkPreview preview, String id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (true) {
            var page = preview.page(REVIEWER, id, 0);
            if (!"PREPARING".equals(page.job().status())) {
                assertEquals("READY", page.job().status());
                return page;
            }
            assertTrue(System.nanoTime() < deadline);
            Thread.sleep(50);
        }
    }

    private static Query filter(String method) {
        return new Query("EXP-001", DAY, DAY, "", method, "id", false);
    }

    private static JdbcDemoDataService data(boolean fixture) throws Exception {
        return new JdbcDemoDataService(URL, "ui_demo", System.getenv("ALPHA_DEMO_PASSWORD"),
                System.getenv("ALPHA_DEMO_OWNER"), fixture);
    }
}
