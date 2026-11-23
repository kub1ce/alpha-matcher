package ru.urfu.alphamatcher.experiment;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Exclusive, thread-confined read-only snapshot obtained from an application-owned DataSource. */
public final class DataSourceMatchingSession implements AutoCloseable {
    private final Connection connection;
    private final Thread owner = Thread.currentThread();
    private final MatchingEngine engine = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());
    private boolean closed;

    private DataSourceMatchingSession(Connection connection) {
        this.connection = connection;
    }

    public static DataSourceMatchingSession open(DataSource source) throws SQLException {
        Connection connection = source.getConnection();
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            MatchingEngine.validateReadOnlyTransaction(connection);
            return new DataSourceMatchingSession(connection);
        } catch (SQLException | RuntimeException failure) {
            try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public MatchingModel.Decision match(long operationId) throws SQLException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Сессия принадлежит другому потоку");
        if (closed) throw new IllegalStateException("Read-only snapshot уже закрыт");
        try {
            return engine.match(connection, operationId);
        } catch (SQLException | RuntimeException failure) {
            try { close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    @Override
    public void close() throws SQLException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Сессия принадлежит другому потоку");
        if (closed) return;
        closed = true;
        try { connection.rollback(); } finally { connection.close(); }
    }
}
