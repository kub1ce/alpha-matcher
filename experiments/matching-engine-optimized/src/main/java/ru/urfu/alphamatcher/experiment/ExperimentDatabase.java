package ru.urfu.alphamatcher.experiment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Fail closed: this executable may connect only to the dedicated disposable experiment catalog. */
public final class ExperimentDatabase {
    public static final String CATALOG = "matching_engine_exp";

    private ExperimentDatabase() { }

    public static Connection connect() throws SQLException {
        String url = required("MATCHING_DB_URL");
        if (!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/" + CATALOG + "(?:\\?[^;]*)?"))
            throw new IllegalArgumentException("Разрешена только локальная экспериментальная БД " + CATALOG);
        Connection connection = DriverManager.getConnection(url, required("MATCHING_DB_USER"), required("MATCHING_DB_PASSWORD"));
        try {
            if (!CATALOG.equals(connection.getCatalog())
                    || !"MySQL".equals(connection.getMetaData().getDatabaseProductName()))
                throw new SQLException("Ожидалась выделенная экспериментальная MySQL");
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Не задана переменная " + name);
        return value;
    }
}
