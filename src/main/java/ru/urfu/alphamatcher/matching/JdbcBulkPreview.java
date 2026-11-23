package ru.urfu.alphamatcher.matching;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import ru.urfu.alphamatcher.demo.DemoOperationState.Match;
import ru.urfu.alphamatcher.demo.DemoOperationState.Query;
import ru.urfu.alphamatcher.demo.DemoPermissions;
import ru.urfu.alphamatcher.demo.DemoPermissions.Action;
import ru.urfu.alphamatcher.demo.DemoUser;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;

/** Durable, read-only financial preview in the owned JDBC catalog. */
public final class JdbcBulkPreview {
    private final int chunk = bulkSetting("alpha.bulk.preview-chunk", 64, JdbcDemoOperationLoader.PAGE_SIZE);
    private final int workers = bulkSetting("alpha.bulk.workers", 1, 8);
    private static final int VIEW_PAGE = 10;
    private final DataSource source;
    private final AtomicReference<String> active = new AtomicReference<>();
    private final JdbcBulkExecution execution;

    public record Job(String id, String status, long inspected, long selected, long safe,
                      long review, long absent, long skipped, String reason, long selectionToken) { }
    public record Result(long id, String reference, String status, Long contract,
                         boolean selected, String evidence) { }
    public record Page(Job job, long index, List<Result> rows) { }

    JdbcBulkPreview(DataSource source) {
        this.source = source;
        this.execution = new JdbcBulkExecution(this);
    }

    public JdbcBulkExecution execution() { return execution; }

    static int bulkSetting(String name, int fallback, int maximum) {
        String value = System.getProperty(name);
        int setting = value == null ? fallback : Integer.parseInt(value);
        if (setting < 1 || setting > maximum) throw new IllegalArgumentException("Недопустимое значение " + name);
        return setting;
    }

    JdbcBulkMatcher matcher() throws SQLException { return new JdbcBulkMatcher(source, workers); }

    public boolean available() {
        return tx(true, c -> {
            try (var q = c.prepareStatement("""
                    SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bulk_preview_job','bulk_preview_row')
                    """); var r=q.executeQuery()) {
                return r.next() && r.getInt(1)==2;
            }
        });
    }

    public String start(DemoUser user, LocalDate from, LocalDate to, boolean includeProcessed) {
        return start(user, from, to, includeProcessed, null);
    }

    public String startFiltered(DemoUser user, Query filter) {
        authorize(user);
        if (JdbcDemoRegistry.outsideCatalog(filter.from(), filter.to()))
            throw new IllegalArgumentException("Укажите допустимый период.");
        LocalDate from = filter.from() == null || filter.from().isBefore(JdbcDemoRegistry.MIN_DATE)
                ? JdbcDemoRegistry.MIN_DATE : filter.from();
        LocalDate to = filter.to() == null || filter.to().isAfter(JdbcDemoRegistry.MAX_DATE)
                ? JdbcDemoRegistry.MAX_DATE : filter.to();
        return start(user, from, to, !"Не обработана".equals(filter.status()), filter);
    }

    private String start(DemoUser user, LocalDate from, LocalDate to, boolean includeProcessed, Query filter) {
        authorize(user);
        if (from == null || to == null || JdbcDemoRegistry.outsideCatalog(from, to)
                || from.isBefore(JdbcDemoRegistry.MIN_DATE) || to.isAfter(JdbcDemoRegistry.MAX_DATE))
            throw new IllegalArgumentException("Укажите допустимый период.");
        String id = UUID.randomUUID().toString();
        if (!active.compareAndSet(null, id))
            throw new IllegalStateException("Подготовка задания уже выполняется.");
        try {
            tx(false, c -> {
                change(c, """
                        INSERT INTO bulk_preview_job(id,initiator,from_date,to_date,include_processed,status)
                        VALUES (?,?,?,?,?,'PREPARING')
                        """, id,user.email(),from,to,includeProcessed);
                return null;
            });
            Thread.ofVirtual().name("bulk-preview-" + id).start(() -> {
                try {
                    prepare(id, from, to, includeProcessed, filter);
                } catch (RuntimeException failure) {
                    // A lost writer connection can leave PREPARING. The owner can abandon it
                    // once its heartbeat expires, without affecting any business data.
                    active.compareAndSet(id, null);
                    try { finish(id, "FAILED", "Подготовка остановлена из-за технической ошибки."); }
                    catch (RuntimeException secondary) { failure.addSuppressed(secondary); }
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                            "Подготовка " + id + " завершилась технической ошибкой.", failure);
                } finally { active.compareAndSet(id, null); }
            });
            return id;
        } catch (RuntimeException failure) {
            active.compareAndSet(id, null);
            throw failure;
        }
    }

    private void prepare(String id, LocalDate from, LocalDate to, boolean includeProcessed, Query filter) {
        connection(false, writer -> tx(true, reader -> {
            Long revision = JdbcBulkExecution.installed(reader) ? JdbcBulkExecution.revision(reader,false) : null;
            var matches = java.util.Map.<String, Match>of();
            if (filter != null && ("Автоматически".equals(filter.method()) || "Вручную".equals(filter.method()))) {
                var employee = JdbcBulkExecution.employee(reader, false);
                if (employee != null) matches = employee.matches();
            }
            try (var matcher = matcher()) {
                Long cursor = null;
                while (true) {
                    List<Long> ids;
                    if (filter == null) {
                        ids = next(reader, from, to, includeProcessed, cursor);
                        cursor = ids.isEmpty() ? null : ids.getLast();
                    } else {
                        var next = JdbcDemoRegistry.nextFiltered(reader, filter, matches, revision != null, cursor, chunk);
                        ids = next.ids();
                        cursor = next.cursor();
                    }
                    if (cursor == null) break;
                    var batch = new ArrayList<Prepared>(chunk);
                    var loaded = JdbcDemoOperationLoader.byIds(reader, ids);
                    var decisions = matcher.match(reader, loaded.stream().map(row -> row.operation().id()).toList(), revision);
                    for (int i = 0; i < loaded.size(); i++) {
                        var row = loaded.get(i);
                        var decision = decisions.get(i);
                        batch.add(new Prepared(row.operation().id(), row.reference(), JdbcDemoOperationLoader.version(row),
                                decision.status(), decision.proposedContractId(), decisionEvidence(decision)));
                    }
                    if (!append(writer, id, batch)) return null;
                }
            }
            // No more matching or appends can occur. READY callers may start the next job.
            active.compareAndSet(id, null);
            write(writer,c -> {
                if (revision == null) change(c, """
                        UPDATE bulk_preview_job SET status='READY',finished_at=CURRENT_TIMESTAMP(6)
                        WHERE id=? AND status='PREPARING'
                        """,id);
                else change(c, """
                        UPDATE bulk_preview_job SET status='READY',source_revision=?,
                        finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PREPARING'
                        """,revision,id);
                return null;
            });
            return null;
        }));
    }

    private record Prepared(long id, String reference, String version, Status status, Long contract, String evidence) { }

    static String decisionEvidence(ru.urfu.alphamatcher.experiment.MatchingModel.Decision decision) {
        return decision + "\n" + decision.features().mentions();
    }

    private List<Long> next(Connection c, LocalDate from, LocalDate to, boolean all, Long cursor)
            throws SQLException {
        var parameters = new ArrayList<Object>();
        var query = new StringBuilder("SELECT o.id FROM operation o");
        if (!all) query.append(" JOIN operation_status s ON s.id=o.status_id");
        query.append(" WHERE o.operation_at >= ?");
        parameters.add(from.atStartOfDay());
        if (to.isBefore(JdbcDemoRegistry.MAX_DATE)) {
            query.append(" AND o.operation_at < ?");
            parameters.add(to.plusDays(1).atStartOfDay());
        }
        if (cursor != null) {
            query.append(" AND o.id > ?");
            parameters.add(cursor);
        }
        if (!all) query.append(" AND o.contract_id IS NULL AND s.code='LOADED'");
        query.append(" ORDER BY o.id LIMIT ?");
        parameters.add(chunk);
        return JdbcDemoRegistry.ids(c, query.toString(), parameters);
    }

    private boolean append(Connection writer, String id, List<Prepared> rows) throws SQLException {
        return write(writer, c -> {
            if (change(c, """
                    UPDATE bulk_preview_job SET inspected=inspected+?, selected_count=selected_count+?,
                    safe_count=safe_count+?, review_count=review_count+?, absent_count=absent_count+?,
                    skipped_count=skipped_count+?, heartbeat_at=CURRENT_TIMESTAMP(6)
                    WHERE id=? AND status='PREPARING'
                    """, rows.size(),rows.size(),count(rows,Status.SAFE),count(rows,Status.REVIEW),
                    count(rows,Status.NO_CONTRACT),count(rows,Status.SKIPPED),id) != 1) return false;
            try (var q = c.prepareStatement("""
                    INSERT INTO bulk_preview_row(job_id,operation_id,operation_reference,source_version,
                    decision_status,proposed_contract,decision) VALUES (?,?,?,?,?,?,?)
                    """)) {
                for (var row : rows) {
                    q.setString(1,id);
                    q.setLong(2,row.id());
                    q.setString(3,row.reference());
                    q.setString(4,row.version());
                    q.setString(5,row.status().name());
                    q.setObject(6,row.contract());
                    q.setString(7,encodeEvidence(row.evidence()));
                    q.addBatch();
                }
                q.executeBatch();
            }
            return true;
        });
    }

    private static long count(List<Prepared> rows, Status status) {
        return rows.stream().filter(r -> r.status()==status).count();
    }

    private static String encodeEvidence(String text) throws SQLException {
        try {
            var bytes=new java.io.ByteArrayOutputStream();
            try (var gzip=new java.util.zip.GZIPOutputStream(bytes)) {
                gzip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return "gzip1:"+java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException failure) {
            throw new SQLException("Не удалось упаковать диагностические данные.",failure);
        }
    }

    static String decodeEvidence(String stored) throws SQLException {
        if (!stored.startsWith("gzip1:")) return stored;
        int limit=16*1024*1024;
        try (var gzip=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(
                java.util.Base64.getDecoder().decode(stored.substring(6))))) {
            byte[] bytes=gzip.readNBytes(limit+1);
            if (bytes.length>limit) throw new SQLException("Диагностика превысила допустимый размер.");
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new SQLException("Диагностика задания повреждена.",failure);
        }
    }

    private void finish(String id, String status, String reason) {
        tx(false, c -> {
            change(c, """
                    UPDATE bulk_preview_job SET status=?, reason=?, finished_at=CURRENT_TIMESTAMP(6)
                    WHERE id=? AND status='PREPARING'
                    """,status,reason,id);
            return null;
        });
    }

    public List<String> recent(DemoUser user) {
        authorize(user);
        return tx(true, c -> {
            try (var q = JdbcDemoRegistry.prepare(c, """
                    SELECT id FROM bulk_preview_job WHERE initiator=? ORDER BY started_at DESC,id LIMIT 20
                    """,List.of(user.email())); var rows=q.executeQuery()) {
                var ids=new ArrayList<String>();
                while (rows.next()) ids.add(rows.getString(1));
                return List.copyOf(ids);
            }
        });
    }

    public Page page(DemoUser user, String id, long requested) {
        authorize(user);
        return tx(true, c -> {
            var stored = job(c,user,id,false);
            long index = Math.min(Math.max(0,requested),Math.max(0,Math.ceilDiv(stored.summary().inspected(),VIEW_PAGE)-1));
            try (var q = JdbcDemoRegistry.prepare(c, """
                    SELECT operation_id,operation_reference,decision_status,proposed_contract,
                           CASE WHEN override_epoch=? THEN override_value ELSE ? END,decision
                    FROM bulk_preview_row WHERE job_id=? ORDER BY operation_id LIMIT ? OFFSET ?
                    """,List.of(stored.epoch(),stored.all(),id,VIEW_PAGE,index*VIEW_PAGE));
                 var rows=q.executeQuery()) {
                var results=new ArrayList<Result>(VIEW_PAGE);
                while (rows.next()) results.add(new Result(rows.getLong(1),rows.getString(2),rows.getString(3),
                        rows.getObject(4,Long.class),rows.getBoolean(5),decodeEvidence(rows.getString(6))));
                return new Page(stored.summary(),index,List.copyOf(results));
            }
        });
    }

    public void selectAll(DemoUser user, String id, boolean selected) {
        authorize(user);
        tx(false, c -> {
            ready(job(c,user,id,true));
            JdbcBulkExecution.requireEditable(c,id);
            change(c, """
                    UPDATE bulk_preview_job SET select_all=?, selection_epoch=selection_epoch+1,
                    selected_count=IF(?,inspected,0) WHERE id=?
                    """,selected,selected,id);
            JdbcBulkExecution.changedSelection(c,id);
            return null;
        });
    }

    public void select(DemoUser user, String id, long operation, boolean selected) {
        authorize(user);
        tx(false, c -> {
            var stored=job(c,user,id,true);
            ready(stored);
            JdbcBulkExecution.requireEditable(c,id);
            boolean previous;
            try (var q=JdbcDemoRegistry.prepare(c, """
                    SELECT CASE WHEN override_epoch=? THEN override_value ELSE ? END
                    FROM bulk_preview_row WHERE job_id=? AND operation_id=?
                    """,List.of(stored.epoch(),stored.all(),id,operation)); var rows=q.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Операция отсутствует в задании.");
                previous=rows.getBoolean(1);
            }
            if (previous!=selected) {
                change(c,"UPDATE bulk_preview_row SET override_epoch=?,override_value=? WHERE job_id=? AND operation_id=?",
                        stored.epoch(),selected,id,operation);
                change(c,"UPDATE bulk_preview_job SET selected_count=selected_count+? WHERE id=?",selected?1:-1,id);
                JdbcBulkExecution.changedSelection(c,id);
            }
            return null;
        });
    }

    public void cancel(DemoUser user,String id) {
        authorize(user);
        tx(false,c -> {
            change(c, """
                    UPDATE bulk_preview_job SET status='CANCELLED', reason='Отменено сотрудником.',
                    finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND initiator=? AND status='PREPARING'
                    """,id,user.email());
            return null;
        });
    }

    public boolean abandonInterrupted(DemoUser user,String id) {
        authorize(user);
        return tx(false,c -> change(c, """
                UPDATE bulk_preview_job SET status='INTERRUPTED', reason='Подготовьте новое задание.',
                finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND initiator=? AND status='PREPARING'
                AND heartbeat_at<CURRENT_TIMESTAMP(6)-INTERVAL 2 MINUTE
                """,id,user.email())==1);
    }

    private record Stored(Job summary,long epoch,boolean all) { }

    private static Stored job(Connection c,DemoUser user,String id,boolean lock) throws SQLException {
        String token=JdbcBulkExecution.installed(c)?"choice_revision":"0";
        try (var q=JdbcDemoRegistry.prepare(c, """
                SELECT status,inspected,selected_count,safe_count,review_count,absent_count,skipped_count,
                       reason,selection_epoch,select_all,
                """+token+" FROM bulk_preview_job WHERE id=? AND initiator=?"+(lock?" FOR UPDATE":""),
                List.of(id,user.email())); var rows=q.executeQuery()) {
            if (!rows.next()) throw new IllegalArgumentException("Задание не найдено.");
            return new Stored(new Job(id,rows.getString(1),rows.getLong(2),rows.getLong(3),
                    rows.getLong(4),rows.getLong(5),rows.getLong(6),rows.getLong(7),rows.getString(8),rows.getLong(11)),
                    rows.getLong(9),rows.getBoolean(10));
        }
    }

    private static void authorize(DemoUser user) { DemoPermissions.require(user,Action.PREVIEW); }
    private static void ready(Stored job) {
        if (!"READY".equals(job.summary().status())) throw new IllegalStateException("Предпросмотр ещё не готов.");
    }

    @FunctionalInterface
    interface Sql<T> { T run(Connection c) throws SQLException; }

    <T> T tx(boolean readOnly,Sql<T> action) {
        return connection(readOnly,c -> complete(c,readOnly,action));
    }

    <T> T connection(boolean readOnly,Sql<T> action) {
        try (var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(readOnly);
            c.setAutoCommit(false);
            return action.run(c);
        } catch (SQLException error) {
            throw new IllegalStateException("Не удалось сохранить или прочитать задание MySQL.",error);
        }
    }

    static <T> T write(Connection c,Sql<T> action) throws SQLException {
        return complete(c,false,action);
    }

    private static <T> T complete(Connection c,boolean readOnly,Sql<T> action) throws SQLException {
        try {
            var result=action.run(c);
            if (readOnly) c.rollback(); else c.commit();
            return result;
        } catch (SQLException | RuntimeException error) {
            try { c.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }

    static int change(Connection c,String query,Object... args) throws SQLException {
        try (var q=c.prepareStatement(query)) {
            for (int i=0;i<args.length;i++) q.setObject(i+1,args[i]);
            return q.executeUpdate();
        }
    }
}
