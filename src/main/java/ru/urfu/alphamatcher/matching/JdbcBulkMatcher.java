package ru.urfu.alphamatcher.matching;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import ru.urfu.alphamatcher.experiment.JdbcMatchingRepository;
import ru.urfu.alphamatcher.experiment.MatchingEngine;
import ru.urfu.alphamatcher.experiment.MatchingModel.Decision;
import ru.urfu.alphamatcher.experiment.PurposeParser;

/** Reuses up to W read-only connections and returns decisions in input order. */
final class JdbcBulkMatcher implements AutoCloseable {
    private final List<Connection> readers = new ArrayList<>();
    private final ExecutorService executor;

    JdbcBulkMatcher(DataSource source, int workers) throws SQLException {
        if (workers == 1) {
            executor = null;
            return;
        }
        executor = Executors.newFixedThreadPool(workers, Thread.ofPlatform().name("bulk-jdbc-", 0).factory());
        try {
            for (int i = 0; i < workers; i++) {
                Connection reader = source.getConnection();
                readers.add(reader);
                reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                reader.setReadOnly(true);
                reader.setAutoCommit(false);
            }
        } catch (SQLException | RuntimeException error) {
            try { close(); } catch (SQLException closing) { error.addSuppressed(closing); }
            throw error;
        }
    }

    List<Decision> match(Connection serial, List<Long> ids, Long revision) throws SQLException {
        if (executor == null || revision == null || ids.size() < 2) return match(serial, ids);
        int lanes = Math.min(ids.size(), readers.size());
        var tasks = new ArrayList<Callable<List<Decision>>>(lanes);
        for (int lane = 0; lane < lanes; lane++) {
            Connection reader = readers.get(lane);
            List<Long> part = ids.subList(lane * ids.size() / lanes, (lane + 1) * ids.size() / lanes);
            tasks.add(() -> {
                try {
                    if (JdbcBulkExecution.revision(reader, false) != revision)
                        throw new SQLException("Версия источника изменилась во время пакетного расчёта.");
                    return match(reader, part);
                } finally {
                    reader.rollback();
                }
            });
        }
        var ordered = new ArrayList<Decision>(ids.size());
        try {
            for (var result : executor.invokeAll(tasks)) ordered.addAll(result.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("Пакетный расчёт прерван.", interrupted);
        } catch (ExecutionException failed) {
            throw new SQLException("Не выполнен пакетный расчёт.", failed.getCause());
        }
        return ordered;
    }

    private static List<Decision> match(Connection connection, List<Long> ids) throws SQLException {
        var engine = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());
        var result = new ArrayList<Decision>(ids.size());
        for (long id : ids) result.add(engine.match(connection, id));
        return result;
    }

    @Override public void close() throws SQLException {
        if (executor != null) executor.close();
        SQLException failure = null;
        for (var reader : readers) {
            try { reader.close(); }
            catch (SQLException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
