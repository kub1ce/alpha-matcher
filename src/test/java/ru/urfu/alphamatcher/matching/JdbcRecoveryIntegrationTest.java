package ru.urfu.alphamatcher.matching;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.urfu.alphamatcher.demo.*;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "M51_QA_DISPOSABLE", matches = "true")
class JdbcRecoveryIntegrationTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:33368/alpha_matcher_ui_demo"
            + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=3000";
    private static final String OWNER = System.getenv("ALPHA_DEMO_OWNER");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final String ID = "EXP-001";
    private static final String ACTOR = DemoUser.ISAEV.fullName();
    private final AtomicReference<Fault> next = new AtomicReference<>(Fault.NONE);
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicReference<CountDownLatch> observedSnapshotLock = new AtomicReference<>();
    private final MysqlDataSource jdbc = jdbc(URL);

    JdbcRecoveryIntegrationTest() throws SQLException { }

    private enum Fault { NONE, CONNECTION, READ_ABORT, WRITE_ABORT, HISTORY, BEFORE_COMMIT, AFTER_COMMIT, OBSERVE_LOCK }
    private enum Corruption { TRUNCATED, JSON, WRONG_TYPE, INCOMPATIBLE_VERSION, CHECKSUM, TRAILING_BYTES, NULL_AFTER_COMMIT }

    @BeforeEach @AfterEach
    void resetOwnedRows() throws Exception {
        try (var c = jdbc.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE operation SET contract_id=NULL WHERE id=1");
            s.executeUpdate("UPDATE ui_demo_state SET employee_snapshot=NULL WHERE id=1");
        }
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"CONNECTION", "WRITE_ABORT", "HISTORY"})
    void failuresBeforeCommitDoNotLeaveLinkOrAuditAndAllowExplicitRetry(Fault fault) throws Exception {
        var data = service();
        var stateBefore = data.state().employeeSnapshot();
        var old = preview(data);
        next.set(fault);
        assertThrows(IllegalStateException.class, () -> apply(data, old, ACTOR));
        assertEquals(stateBefore, data.state().employeeSnapshot());
        assertNull(contract());
        assertNull(blob());
        apply(data, old, ACTOR);
        assertCommittedOnce();
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"BEFORE_COMMIT", "AFTER_COMMIT"})
    void ambiguousCommitBlocksRetryUntilReloadAndReportsActualState(Fault fault) throws Exception {
        var data = service();
        var old = preview(data);
        var oldState = data.state().employeeSnapshot();
        next.set(fault);
        var err = assertThrows(IllegalStateException.class, () -> apply(data, old, ACTOR));
        assertTrue(err.getMessage().contains("Результат подтверждения неизвестен"), err.toString());
        assertEquals(oldState, data.state().employeeSnapshot());
        int attempts = connections.get();
        var retry = assertThrows(IllegalStateException.class, () -> apply(data, old, ACTOR));
        assertTrue(retry.getMessage().contains("Результат подтверждения неизвестен"), retry.toString());
        assertThrows(IllegalStateException.class, () -> data.analyze(ID));
        assertEquals(attempts, connections.get(), "Retry must stop before opening a new transaction");
        var fresh = service();
        if (fault == Fault.AFTER_COMMIT) {
            assertCommittedOnce();
            assertThrows(IllegalStateException.class, () -> apply(fresh, old, DemoUser.ROZHKOV.fullName()));
            assertCommittedOnce();
        } else {
            assertNull(contract());
            assertNull(blob());
            assertTrue(fresh.state().auditEvents().isEmpty());
            apply(fresh, preview(fresh), ACTOR);
            assertCommittedOnce();
        }
    }

    @ParameterizedTest
    @EnumSource(Corruption.class)
    void corruptedSnapshotRefusesStartupWithoutOverwritingOriginalEvidence(Corruption corruption) throws Exception {
        var data = service();
        apply(data, preview(data), ACTOR);
        byte[] valid = blob();
        byte[] corrupt = switch (corruption) {
            case TRUNCATED -> Arrays.copyOf(valid, valid.length - 7);
            case JSON -> "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case WRONG_TYPE -> wrongType();
            case INCOMPATIBLE_VERSION -> {
                byte[] modified = valid.clone();
                modified[7] ^= 1;
                yield modified;
            }
            case CHECKSUM -> {
                byte[] modified = valid.clone();
                modified[modified.length - 1] ^= 1;
                yield modified;
            }
            case TRAILING_BYTES -> Arrays.copyOf(valid, valid.length + 1);
            case NULL_AFTER_COMMIT -> null;
        };
        store(corrupt);
        try {
            assertThrows(SQLException.class, () -> new JdbcDemoDataService(URL, "ui_demo",
                    System.getenv("ALPHA_DEMO_PASSWORD"), OWNER));
            assertArrayEquals(corrupt, blob());
            assertEquals(101L, contract());
        } finally {
            store(valid);
        }
        assertCommittedOnce();
    }

    @Test void legacySerializedSnapshotRemainsReadableWithoutRewritingIt() throws Exception {
        var data = service();
        apply(data, preview(data), ACTOR);
        var original = data.state().employeeSnapshot();
        var legacy = serialized(original);
        store(legacy);
        var recovered = new JdbcDemoDataService(URL, "ui_demo", System.getenv("ALPHA_DEMO_PASSWORD"), OWNER);
        assertEquals(original, recovered.state().employeeSnapshot());
        assertArrayEquals(legacy, blob());
    }

    @Test void missingStateRowAndMismatchedLinkBothFailClosed() throws Exception {
        var data = service();
        apply(data, preview(data), ACTOR);
        var saved = blob();
        try (var c = jdbc.getConnection(); var stmt = c.createStatement()) {
            stmt.executeUpdate("UPDATE ui_demo_state SET id=2 WHERE id=1");
            try {
                assertThrows(SQLException.class, this::service);
                assertEquals(101L, contract());
            } finally {
                stmt.executeUpdate("UPDATE ui_demo_state SET id=1 WHERE id=2");
            }
            stmt.executeUpdate("UPDATE operation SET contract_id=102 WHERE id=1");
            try {
                assertThrows(SQLException.class, this::service);
                assertEquals(102L, contract());
                assertArrayEquals(saved, blob());
            } finally {
                stmt.executeUpdate("UPDATE operation SET contract_id=101 WHERE id=1");
            }
        }
        assertCommittedOnce();
    }

    @Test void readAbortIsTechnicalFailureAndFreshReadRecovers() throws Exception {
        var data = service();
        next.set(Fault.READ_ABORT);
        assertThrows(IllegalStateException.class, () -> data.analyze(ID));
        assertEquals("SAFE", data.analyze(ID).status().name());
        assertNull(contract());
        assertNull(blob());
    }

    @Test void realSocketRefusalAndJdbcQueryTimeout() throws Exception {
        int port;
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort();
        }
        var refused = jdbc("jdbc:mysql://127.0.0.1:" + port + "/alpha_matcher_ui_demo?connectTimeout=1000");
        var err = assertThrows(SQLException.class, refused::getConnection);
        assertTrue(err.getCause() instanceof java.net.ConnectException
                || err.getCause() != null && err.getCause().getCause() instanceof java.net.ConnectException, err.toString());
        try (var c = jdbc.getConnection(); var s = c.createStatement()) {
            s.setQueryTimeout(1);
            assertThrows(SQLTimeoutException.class, () -> s.executeQuery("SELECT SLEEP(8)"));
        }
        assertNull(contract());
        assertNull(blob());
    }

    @Test void mysqlHandshakeReadTimesOutAgainstSilentLoopbackListener() throws Exception {
        try (var silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var url = "jdbc:mysql://127.0.0.1:" + silent.getLocalPort() + "/alpha_matcher_ui_demo"
                    + "?connectTimeout=1000&socketTimeout=250";
            Throwable cause = assertThrows(SQLException.class, () -> jdbc(url).getConnection());
            while (cause.getCause() != null) cause = cause.getCause();
            assertInstanceOf(java.net.SocketTimeoutException.class, cause);
        }
    }

    @Test void twoEmployeesOverlapAndOnlyOneAuditCommitWins() throws Exception {
        var first = service();
        var second = service();
        var p1 = preview(first);
        var p2 = preview(second);
        var firstOpen = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);
        observedSnapshotLock.set(secondReady);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var winning = threads.submit(() -> first.confirmAtomically(first.state(), () -> {
                firstOpen.countDown();
                await(secondReady);
                return apply(first, p1, ACTOR);
            }));
            var competing = threads.submit(() -> {
                await(firstOpen);
                next.set(Fault.OBSERVE_LOCK);
                assertThrows(IllegalStateException.class, () -> apply(second, p2, DemoUser.ROZHKOV.fullName()));
            });
            winning.get(15, TimeUnit.SECONDS);
            competing.get(15, TimeUnit.SECONDS);
        }
        assertCommittedOnce();
    }

    @Test void changedPayerOnBothSidesCannotConfirmAnOldIdentityWithSameDecision() throws Exception {
        String original = "Оплата по договору № D-001, ИНН 9900000017";
        try (var c = jdbc.getConnection(); var stmt = c.createStatement()) {
            stmt.executeUpdate("UPDATE operation SET payment_purpose='Договор № D-001; ИНН 9900000024' WHERE id=1");
            try {
                var data = service();
                var old = preview(data);
                var oldDecision = old.find(ID).decision();
                stmt.executeUpdate("""
                        UPDATE operation_party SET client_id=3,inn='9900000031'
                        WHERE operation_id=1 AND role_id=(SELECT id FROM operation_party_role WHERE code='PAYER')
                        """);
                stmt.executeUpdate("""
                        UPDATE contract_party SET client_id=3
                        WHERE contract_id=101 AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER')
                        """);
                assertEquals(oldDecision, data.analyze(ID), "Status and checks alone lack both changed identities");
                assertThrows(IllegalStateException.class, () -> apply(data, old, ACTOR));
                assertNull(contract());
                assertTrue(data.state().auditEvents().isEmpty());
            } finally {
                stmt.executeUpdate("""
                        UPDATE contract_party SET client_id=1
                        WHERE contract_id=101 AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER')
                        """);
                stmt.executeUpdate("""
                        UPDATE operation_party SET client_id=1,inn='9900000017'
                        WHERE operation_id=1 AND role_id=(SELECT id FROM operation_party_role WHERE code='PAYER')
                        """);
                try (var stmt2 = c.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=1")) {
                    stmt2.setString(1, original);
                    stmt2.executeUpdate();
                }
            }
        }
    }

    private void assertCommittedOnce() throws Exception {
        assertEquals(101L, contract());
        var recovered = service().state();
        assertEquals(1, recovered.auditEvents().size());
        assertEquals(ACTOR, recovered.auditEvents().getFirst().actor());
        assertEquals(ACTOR, recovered.batchRuns().getFirst().initiator());
        assertEquals(ACTOR, recovered.match(ID).actor());
        assertNotNull(recovered.match(ID).decisionAt());
        assertNotNull(recovered.auditEvents().getFirst().time());
        assertEquals(recovered.employeeSnapshot(), service().state().employeeSnapshot());
        assertEquals("DB-101", recovered.match(ID).contractId());
    }

    private static DemoMatchingService.Preview preview(JdbcDemoDataService data) {
        return new DemoMatchingService(data).preview(data.state(), DAY, DAY, true);
    }

    private static DemoMatchingService.BatchResult apply(JdbcDemoDataService data,
                                                          DemoMatchingService.Preview preview, String actor) {
        return new DemoMatchingService(data).apply(data.state(), preview, Set.of(ID), actor);
    }

    private JdbcDemoDataService service() throws SQLException {
        var faulting = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) return call(jdbc, method, args);
                    connections.incrementAndGet();
                    var fault = next.getAndSet(Fault.NONE);
                    if (fault == Fault.CONNECTION) throw new SQLRecoverableException("Injected pre-transaction failure");
                    return connection(jdbc.getConnection(), fault);
                });
        return new JdbcDemoDataService(faulting, OWNER, true);
    }

    private Connection connection(Connection raw, Fault fault) {
        if (fault == Fault.NONE) return raw;
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (fault == Fault.OBSERVE_LOCK && method.getName().equals("createStatement")) {
                        var statement = (Statement) call(raw, method, args);
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                                (inner, command, parameters) -> {
                                    if (command.getName().equals("executeQuery") && parameters[0] instanceof String sql
                                            && sql.endsWith("ui_demo_state WHERE id=1 FOR UPDATE"))
                                        observedSnapshotLock.get().countDown();
                                    return call(statement, command, parameters);
                                });
                    }
                    if (method.getName().equals("commit") && (fault == Fault.AFTER_COMMIT || fault == Fault.BEFORE_COMMIT)) {
                        if (fault == Fault.AFTER_COMMIT) raw.commit();
                        raw.abort(Runnable::run);
                        throw new SQLRecoverableException("Injected missing commit acknowledgement");
                    }
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        if (fault == Fault.HISTORY && sql.startsWith("UPDATE ui_demo_state"))
                            throw new SQLException("Injected snapshot write failure");
                        if (fault == Fault.READ_ABORT && sql.contains("FROM operation o")
                                || fault == Fault.WRITE_ABORT && sql.startsWith("UPDATE ui_demo_state"))
                            raw.abort(Runnable::run);
                    }
                    return call(raw, method, args);
                });
    }

    private static Object call(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static MysqlDataSource jdbc(String url) throws SQLException {
        var source = new MysqlDataSource();
        source.setUrl(url);
        source.setUser("ui_demo");
        source.setPassword(System.getenv("ALPHA_DEMO_PASSWORD"));
        return source;
    }

    private Long contract() throws Exception {
        try (var c = jdbc.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT contract_id FROM operation WHERE id=1")) {
            assertTrue(r.next());
            return r.getObject(1, Long.class);
        }
    }

    private byte[] blob() throws Exception {
        try (var c = jdbc.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT employee_snapshot FROM ui_demo_state WHERE id=1")) {
            assertTrue(r.next());
            return r.getBytes(1);
        }
    }

    private void store(byte[] bytes) throws Exception {
        try (var c = jdbc.getConnection();
             var s = c.prepareStatement("UPDATE ui_demo_state SET employee_snapshot=? WHERE id=1")) {
            s.setBytes(1, bytes);
            assertEquals(1, s.executeUpdate());
        }
    }

    private static byte[] wrongType() throws Exception {
        return serialized(List.of("wrong payload type"));
    }

    private static byte[] serialized(Object value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var wrapper = new ObjectOutputStream(bytes)) {
            wrapper.writeObject(value);
        }
        return bytes.toByteArray();
    }
}
