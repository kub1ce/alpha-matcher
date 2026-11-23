package ru.urfu.alphamatcher.matching;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import ru.urfu.alphamatcher.demo.DemoOperationState.EmployeeSnapshot;
import ru.urfu.alphamatcher.demo.DemoPermissions;
import ru.urfu.alphamatcher.demo.DemoPermissions.Action;
import ru.urfu.alphamatcher.demo.DemoUser;
import ru.urfu.alphamatcher.experiment.MatchingModel.Status;

import static ru.urfu.alphamatcher.matching.JdbcBulkPreview.change;
import static ru.urfu.alphamatcher.matching.JdbcDemoRegistry.prepare;

/** A persisted cursor is advanced in the same transaction as all financial effects of a package. */
public final class JdbcBulkExecution {
    private final int chunk = JdbcBulkPreview.bulkSetting("alpha.bulk.transaction-batch", 64, JdbcDemoOperationLoader.PAGE_SIZE);
    private final JdbcBulkPreview storage;

    public record Progress(String status,long processed,long applied,long packages,String reason) { }
    public record JournalEntry(long operationId,String reference,long packageNumber,String outcome,Long contract,
                               String actor,java.time.LocalDateTime time) { }
    private record Job(String status,long selected,long epoch,boolean all,Long revision,long choice) { }
    private record Run(Progress progress,long revision,Long cursor) { }
    private record Row(long id,String reference,String version,String status,Long contract,String decision) { }

    JdbcBulkExecution(JdbcBulkPreview storage) { this.storage=storage; }

    static boolean installed(Connection c) throws SQLException {
        try (var q=c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bulk_source_revision'
                """); var r=q.executeQuery()) {
            return r.next() && r.getInt(1)==1;
        }
    }

    static long revision(Connection c,boolean lock) throws SQLException {
        try (var q=c.prepareStatement("SELECT revision FROM bulk_source_revision WHERE id=1"+(lock?" FOR UPDATE":""));
             var rows=q.executeQuery()) {
            if (!rows.next()) throw new SQLException("Отсутствует версия источника.");
            return rows.getLong(1);
        }
    }

    static void requireEditable(Connection c,String id) throws SQLException {
        if (installed(c) && run(c,id,false)!=null) throw new IllegalStateException("Выбор уже подтверждён.");
    }

    static void changedSelection(Connection c,String id) throws SQLException {
        if (installed(c)) change(c,"UPDATE bulk_preview_job SET choice_revision=choice_revision+1 WHERE id=?",id);
    }

    public boolean available() { return storage.tx(true,JdbcBulkExecution::installed); }

    public Progress progress(DemoUser user,String id) {
        DemoPermissions.require(user,Action.PREVIEW);
        return storage.tx(true,c -> {
            job(c,user,id,false);
            var run=run(c,id,false);
            return run==null?null:run.progress();
        });
    }

    public List<JournalEntry> journal(DemoUser user,String id,long offset) {
        DemoPermissions.require(user,Action.PREVIEW);
        return storage.tx(true,c -> {
            job(c,user,id,false);
            try (var q=prepare(c,"""
                    SELECT operation_id,operation_reference,package_number,outcome,applied_contract,actor,recorded_at
                    FROM bulk_execution_result WHERE job_id=? ORDER BY operation_id LIMIT 10 OFFSET ?
                    """,List.of(id,Math.max(0,offset))); var rows=q.executeQuery()) {
                var result=new ArrayList<JournalEntry>();
                while (rows.next()) result.add(new JournalEntry(rows.getLong(1),rows.getString(2),rows.getLong(3),
                        rows.getString(4),rows.getObject(5,Long.class),rows.getString(6),
                        rows.getObject(7,java.time.LocalDateTime.class)));
                return List.copyOf(result);
            }
        });
    }

    public Progress authorize(DemoUser user,String id,long selectionToken) {
        DemoPermissions.require(user,Action.APPLY_SAFE);
        return storage.tx(false,c -> {
            var job=job(c,user,id,true);
            var old=run(c,id,true);
            if (old!=null) return old.progress();
            if (job.choice()!=selectionToken) throw new IllegalStateException("Выбор изменился. Подтвердите его заново.");
            if (!allowed(c,user)) throw new IllegalStateException("Право пакетного подтверждения отсутствует.");
            if (!"READY".equals(job.status()) || job.selected()==0 || job.revision()==null)
                throw new IllegalStateException("Подготовьте актуальный предпросмотр и выберите операции.");
            long current=revision(c,true);
            String status=job.revision()==current?"RUNNING":"REQUIRES_REVIEW";
            String reason="RUNNING".equals(status)?null:"Источник изменился после предпросмотра.";
            change(c,"INSERT INTO bulk_execution(job_id,status,expected_revision,reason) VALUES(?,?,?,?)",
                    id,status,current,reason);
            return new Progress(status,0,0,0,reason);
        });
    }

    public Progress step(DemoUser user,String id) {
        DemoPermissions.require(user,Action.PREVIEW);
        try (var matcher = storage.matcher()) {
            return storage.tx(false,c -> step(c,user,id,matcher));
        } catch (SQLException error) {
            throw new IllegalStateException("Не удалось открыть или закрыть JDBC-расчёт.", error);
        }
    }

    public void execute(DemoUser user,String id,java.util.function.BooleanSupplier active) {
        DemoPermissions.require(user,Action.PREVIEW);
        storage.connection(false,c -> {
            try (var matcher = storage.matcher()) {
                while (active.getAsBoolean()
                        && "RUNNING".equals(JdbcBulkPreview.write(c,writer -> step(writer,user,id,matcher)).status())) { }
            }
            return null;
        });
    }

    private Progress step(Connection c,DemoUser user,String id,JdbcBulkMatcher matcher) throws SQLException {
            var job=job(c,user,id,true);
            var run=run(c,id,true);
            if (run==null) throw new IllegalStateException("Задание ещё не подтверждено.");
            if (!"RUNNING".equals(run.progress().status())) return run.progress();
            if (!DemoPermissions.allows(user,Action.APPLY_SAFE) || !allowed(c,user))
                return stop(c,id,run,"REQUIRES_REVIEW","Право подтверждения отозвано.");
            // Legacy confirmations acquire this lock first. Pending manual reviews still live here.
            var employee=employee(c,true);
            if (revision(c,true)!=run.revision())
                return stop(c,id,run,"REQUIRES_REVIEW","Источник изменился после предпросмотра.");
            var rows=next(c,id,job,run.cursor());
            if (rows.isEmpty()) {
                if (run.progress().processed()!=job.selected()) throw new SQLException("Не сходится состав задания.");
                return stop(c,id,run,"COMPLETED",null);
            }

            var hydrated=JdbcDemoOperationLoader.byIds(c,rows.stream().map(Row::id).toList());
            var decisions=matcher.match(c, rows.stream().map(Row::id).toList(), run.revision());
            var safeRows=rows.stream().filter(row -> "SAFE".equals(row.status())).toList();
            var previouslyApplied=appliedAudit(c,safeRows);
            for (int index=0;index<rows.size();index++) {
                var row=rows.get(index);
                if (!row.version().equals(JdbcDemoOperationLoader.version(hydrated.get(index))))
                    return stop(c,id,run,"REQUIRES_REVIEW","Операция изменилась после предпросмотра.");
                var decision=decisions.get(index);
                if (!row.decision().equals(JdbcBulkPreview.decisionEvidence(decision))
                        || !row.status().equals(decision.status().name()) || !Objects.equals(row.contract(),decision.proposedContractId()))
                    return stop(c,id,run,"REQUIRES_REVIEW","Решение изменилось после предпросмотра.");
                if (decision.status()==Status.SAFE && employee!=null && employee.manualQueue().contains(row.reference()))
                    return stop(c,id,run,"REQUIRES_REVIEW","Операция находится на ручном разборе.");
                if (decision.status()==Status.SAFE && previouslyApplied.contains(row.id()))
                    return stop(c,id,run,"REQUIRES_REVIEW","Состояние операции расходится с пакетным аудитом.");
            }
            long applied=applyLinks(c,safeRows);
            long packageNumber=run.progress().packages()+1;
            try (var journal=c.prepareStatement("""
                    INSERT INTO bulk_execution_result(job_id,operation_id,package_number,operation_reference,actor,outcome,applied_contract)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
            for (var row:rows) {
                boolean safe="SAFE".equals(row.status());
                journal.setString(1,id);
                journal.setLong(2,row.id());
                journal.setLong(3,packageNumber);
                journal.setString(4,row.reference());
                journal.setString(5,user.email());
                journal.setString(6,safe?"APPLIED":"SKIPPED");
                journal.setObject(7,safe?row.contract():null);
                journal.addBatch();
            }
            int[] written=journal.executeBatch();
            if (written.length!=rows.size()
                    || java.util.Arrays.stream(written).anyMatch(n -> n!=1 && n!=Statement.SUCCESS_NO_INFO))
                throw new SQLException("Не записан полный журнал пакета.");
            }
            change(c,"""
                    UPDATE bulk_execution SET last_operation=?,processed=processed+?,applied=applied+?,
                    packages=?,expected_revision=? WHERE job_id=?
                    """,rows.getLast().id(),rows.size(),applied,packageNumber,revision(c,true),id);
            return new Progress("RUNNING",run.progress().processed()+rows.size(),
                    run.progress().applied()+applied,packageNumber,null);
    }

    public Progress cancel(DemoUser user,String id) {
        DemoPermissions.require(user,Action.PREVIEW);
        return storage.tx(false,c -> {
            job(c,user,id,true);
            var run=run(c,id,true);
            if (run==null) throw new IllegalStateException("Нет исполнения для отмены.");
            if (!"RUNNING".equals(run.progress().status())) return run.progress();
            return stop(c,id,run,run.progress().processed()==0?"CANCELLED":"PARTIALLY_COMPLETED","Остановлено сотрудником.");
        });
    }

    private static Progress stop(Connection c,String id,Run run,String status,String reason) throws SQLException {
        change(c,"UPDATE bulk_execution SET status=?,reason=?,completed_at=CURRENT_TIMESTAMP(6) WHERE job_id=?",
                status,reason,id);
        return new Progress(status,run.progress().processed(),run.progress().applied(),run.progress().packages(),reason);
    }

    private static Job job(Connection c,DemoUser user,String id,boolean lock) throws SQLException {
        try (var q=prepare(c,"""
                SELECT status,selected_count,selection_epoch,select_all,source_revision,choice_revision FROM bulk_preview_job
                WHERE id=? AND initiator=?
                """+(lock?" FOR UPDATE":""),List.of(id,user.email())); var rows=q.executeQuery()) {
            if (!rows.next()) throw new IllegalArgumentException("Задание не найдено.");
            return new Job(rows.getString(1),rows.getLong(2),rows.getLong(3),rows.getBoolean(4),rows.getObject(5,Long.class),rows.getLong(6));
        }
    }

    private static Run run(Connection c,String id,boolean lock) throws SQLException {
        try (var q=prepare(c,"""
                SELECT status,processed,applied,packages,reason,expected_revision,last_operation
                FROM bulk_execution WHERE job_id=?
                """+(lock?" FOR UPDATE":""),List.of(id)); var rows=q.executeQuery()) {
            if (!rows.next()) return null;
            return new Run(new Progress(rows.getString(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),
                    rows.getString(5)),rows.getLong(6),rows.getObject(7,Long.class));
        }
    }

    private List<Row> next(Connection c,String id,Job job,Long cursor) throws SQLException {
        var parameters=new ArrayList<Object>(List.of(id,job.epoch(),job.all()));
        if (cursor!=null) parameters.add(cursor);
        parameters.add(chunk);
        try (var q=prepare(c,"""
                SELECT operation_id,operation_reference,source_version,decision_status,proposed_contract,decision
                FROM bulk_preview_row WHERE job_id=? AND (CASE WHEN override_epoch=? THEN override_value ELSE ? END)
                """+(cursor==null?"":" AND operation_id > ?")+" ORDER BY operation_id LIMIT ?",parameters);
             var result=q.executeQuery()) {
            var rows=new ArrayList<Row>(chunk);
            while (result.next()) rows.add(new Row(result.getLong(1),result.getString(2),result.getString(3),
                    result.getString(4),result.getObject(5,Long.class),JdbcBulkPreview.decodeEvidence(result.getString(6))));
            return rows;
        }
    }

    private static boolean allowed(Connection c,DemoUser user) throws SQLException {
        try (var q=prepare(c,"SELECT can_apply FROM bulk_executor_permission WHERE employee=? FOR SHARE",
                           List.of(user.email())); var rows=q.executeQuery()) {
            return rows.next() && rows.getBoolean(1);
        }
    }

    private static Set<Long> appliedAudit(Connection c,List<Row> safeRows) throws SQLException {
        if (safeRows.isEmpty()) return Set.of();
        var prior=new HashSet<Long>();
        var ids=safeRows.stream().map(Row::id).toList();
        try (var q=prepare(c,"SELECT DISTINCT operation_id FROM bulk_execution_result "
                +"WHERE applied_contract IS NOT NULL AND operation_id IN ("+parameters(ids.size())+")",ids);
             var r=q.executeQuery()) {
            while (r.next()) prior.add(r.getLong(1));
        }
        return prior;
    }

    private static int applyLinks(Connection c,List<Row> safeRows) throws SQLException {
        if (safeRows.isEmpty()) return 0;
        String cases=String.join("",Collections.nCopies(safeRows.size()," WHEN ? THEN ?"));
        var values=new ArrayList<Object>(safeRows.size()*3);
        for (var row:safeRows) {
            values.add(row.id());
            values.add(Objects.requireNonNull(row.contract()));
        }
        for (var row:safeRows) values.add(row.id());
        // Bind the contract IDs revalidated in this transaction, independently of later preview reads.
        try (var q=prepare(c,"UPDATE operation SET contract_id=CASE id"+cases
                +" ELSE contract_id END WHERE contract_id IS NULL AND id IN ("+parameters(safeRows.size())+")",values)) {
            int count=q.executeUpdate();
            if (count!=safeRows.size()) throw new SQLException("Нарушено условие записи связи.");
            return count;
        }
    }

    private static String parameters(int count) {
        return String.join(",",Collections.nCopies(count,"?"));
    }

    static EmployeeSnapshot employee(Connection c, boolean lock) throws SQLException {
        try (var q=c.prepareStatement("SELECT employee_snapshot FROM ui_demo_state WHERE id=1"
                + (lock ? " FOR UPDATE" : ""));
             var rows=q.executeQuery()) {
            if (!rows.next()) throw new SQLException("Нет состояния сотрудников.");
            byte[] payload=rows.getBytes(1);
            if (payload==null) return null;
            try { return EmployeeSnapshotCodec.decode(payload); }
            catch (java.io.IOException | ClassNotFoundException failure) {
                throw new SQLException("Не удалось проверить очередь сотрудников.",failure);
            }
        }
    }
}
