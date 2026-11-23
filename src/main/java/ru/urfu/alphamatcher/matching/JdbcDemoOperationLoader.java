package ru.urfu.alphamatcher.matching;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ru.urfu.alphamatcher.experiment.MatchingModel;

/** Application preload. Financial matching and confirmation still read through the accepted engine. */
final class JdbcDemoOperationLoader {
    static final int PAGE_SIZE = 256;
    // Match the single-operation hydration guard in the accepted runtime.
    private static final int MAX_OPERATION_ROWS = 10_000;
    private static final String SELECT = """
            SELECT o.id, o.reference, o.operation_at, o.amount_rub, o.payment_purpose, o.contract_id,
                   s.code AS status_code, s.is_active AS status_active,
                   p.id IS NOT NULL AS has_party, r.code AS role_code, r.is_active AS role_active,
                   p.client_id, p.inn, cl.inn AS client_inn, p.name AS party_name,
                   cl.name AS client_name, cl.last_name, cl.first_name, cl.middle_name
              FROM %s o
              LEFT JOIN operation_status s ON s.id = o.status_id
              LEFT JOIN operation_party p ON p.operation_id = o.id
              LEFT JOIN operation_party_role r ON r.id = p.role_id
              LEFT JOIN client cl ON cl.id = p.client_id
             %s ORDER BY o.id, p.id
            """;

    record Row(String reference, LocalDateTime timestamp, BigDecimal amount, MatchingModel.Operation operation) { }

    @FunctionalInterface
    interface Sink {
        void accept(Row row) throws SQLException;
    }

    private JdbcDemoOperationLoader() { }

    static void load(Connection connection, Sink sink) throws SQLException {
        load(connection, null, null, sink);
    }

    static void load(Connection connection, LocalDate from, LocalDate to, Sink sink) throws SQLException {
        if (JdbcDemoRegistry.outsideCatalog(from, to)) return;
        boolean lower = from != null && from.isAfter(JdbcDemoRegistry.MIN_DATE);
        boolean upper = to != null && to.isBefore(JdbcDemoRegistry.MAX_DATE);
        String range = (lower ? " AND operation_at >= ?" : "") + (upper ? " AND operation_at < ?" : "");
        String chunk = "(SELECT * FROM operation WHERE (? IS NULL OR id > ?)" + range + " ORDER BY id LIMIT ?)";
        try (var query = connection.prepareStatement(SELECT.formatted(chunk, ""))) {
            requireCompleteRead(query);
            Long after = null;
            List<Row> page;
            do {
                if (after == null) {
                    query.setNull(1, Types.BIGINT);
                    query.setNull(2, Types.BIGINT);
                } else {
                    query.setLong(1, after);
                    query.setLong(2, after);
                }
                int limit = 3;
                if (lower) query.setObject(limit++, from.atStartOfDay());
                if (upper) query.setObject(limit++, to.plusDays(1).atStartOfDay());
                query.setInt(limit, PAGE_SIZE);
                try (var rows = query.executeQuery()) {
                    page = readPage(rows);
                }
                for (Row row : page) sink.accept(row);
                if (!page.isEmpty()) after = page.getLast().operation().id();
            } while (page.size() == PAGE_SIZE);
        }
    }

    static Row byReference(Connection connection, String reference) throws SQLException {
        try (var query = connection.prepareStatement(SELECT.formatted("operation",
                "WHERE o.reference=? AND CAST(o.reference AS BINARY)=CAST(? AS BINARY)"))) {
            requireCompleteRead(query);
            query.setString(1, reference);
            query.setString(2, reference);
            try (var rows = query.executeQuery()) {
                return readPage(rows).stream().findFirst().orElse(null);
            }
        }
    }

    static List<Row> byIds(Connection connection, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) return List.of();
        if (ids.size() > PAGE_SIZE) throw new IllegalArgumentException("Hydration page exceeds configured size");
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        try (var query = connection.prepareStatement(SELECT.formatted("operation", "WHERE o.id IN (" + marks + ")"))) {
            requireCompleteRead(query);
            for (int i = 0; i < ids.size(); i++) query.setLong(i + 1, ids.get(i));
            try (var rows = query.executeQuery()) {
                var byId = new LinkedHashMap<Long, Row>();
                for (Row row : readPage(rows)) byId.put(row.operation().id(), row);
                if (!byId.keySet().containsAll(ids))
                    throw new SQLException("Page and operation hydration refer to different rows");
                return ids.stream().map(byId::get).toList();
            }
        }
    }

    private static void requireCompleteRead(java.sql.Statement query) throws SQLException {
        if (query.getMaxRows() != 0 || query.getLargeMaxRows() != 0)
            throw new SQLException("JDBC row limit would truncate the operation snapshot");
    }

    static String version(Row row) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            var op = row.operation();
            append(hash, op.id(), row.reference(), row.timestamp(), row.amount(), op.purpose(),
                    op.status(), op.activeStatus(), op.linkedContractId(), op.parties().size());
            new TreeMap<>(op.parties()).forEach((role, party) -> append(hash, role, party.clientId(),
                    party.inn(), party.clientInn(), party.activeRole(), party.name(), party.clientName(),
                    party.lastName(), party.firstName(), party.middleName()));
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void append(MessageDigest hash, Object... fields) {
        for (Object field : fields) {
            if (field == null) { hash.update((byte) '-'); continue; }
            byte[] data = field.toString().getBytes(StandardCharsets.UTF_8);
            hash.update((data.length + ":").getBytes(StandardCharsets.US_ASCII));
            hash.update(data);
        }
    }

    private static List<Row> readPage(ResultSet rows) throws SQLException {
        List<Row> page = new ArrayList<>();
        boolean more = rows.next();
        while (more) {
            long id = rows.getLong("id");
            String reference = rows.getString("reference");
            LocalDateTime timestamp = rows.getTimestamp("operation_at").toLocalDateTime();
            BigDecimal amount = rows.getBigDecimal("amount_rub");
            String purpose = rows.getString("payment_purpose");
            Long contract = rows.getObject("contract_id", Long.class);
            String status = rows.getString("status_code");
            if (status == null) throw new SQLException("Missing status for operation " + id);
            boolean active = rows.getBoolean("status_active");
            Map<String, MatchingModel.Party> parties = new LinkedHashMap<>();
            int count = 0;
            do {
                if (++count > MAX_OPERATION_ROWS)
                    throw new SQLException("Operation hydration row budget exceeded for " + id);
                addParty(rows, parties);
                more = rows.next();
            } while (more && rows.getLong("id") == id);
            page.add(new Row(reference, timestamp, amount,
                    new MatchingModel.Operation(id, purpose, status, active, contract, parties)));
        }
        return page;
    }

    private static void addParty(ResultSet row, Map<String, MatchingModel.Party> parties) throws SQLException {
        if (!row.getBoolean("has_party")) return;
        String role = row.getString("role_code");
        if (role == null || parties.containsKey(role))
            throw new SQLException("Missing or duplicate operation party role");
        parties.put(role, new MatchingModel.Party(row.getObject("client_id", Long.class),
                row.getString("inn"), row.getString("client_inn"), row.getBoolean("role_active"),
                row.getString("party_name"), row.getString("client_name"), row.getString("last_name"),
                row.getString("first_name"), row.getString("middle_name")));
    }
}
