package ru.urfu.alphamatcher.matching;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import ru.urfu.alphamatcher.demo.DemoOperationState.Match;
import ru.urfu.alphamatcher.demo.DemoOperationState.Query;

/** Server-side catalog projection. Selection and labels use the same transaction. */
final class JdbcDemoRegistry {
    static final LocalDate MIN_DATE = LocalDate.of(1000,1,1);
    static final LocalDate MAX_DATE = LocalDate.of(9999,12,31);
    private static final String JAVA_BLANK = "[\\\\x{0009}-\\\\x{000d}\\\\x{001c}-\\\\x{0020}\\\\x{1680}"
            + "\\\\x{2000}-\\\\x{2006}\\\\x{2008}-\\\\x{200a}\\\\x{2028}\\\\x{2029}\\\\x{205f}\\\\x{3000}]";
    private static final String JAVA_TRIM = "[\\\\x{0000}-\\\\x{0020}]";
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{3,}");
    private static final int ANCHOR_PROBE_ROWS = 256;
    private static final int ANCHOR_SAMPLE_COUNT = 8;
    private static final String PARTIES = """
              LEFT JOIN operation_party_role pr ON pr.code='PAYER'
              LEFT JOIN operation_party p ON p.operation_id=o.id AND p.role_id=pr.id
              LEFT JOIN client pc ON pc.id=p.client_id
              LEFT JOIN operation_party_role rr ON rr.code='RECIPIENT'
              LEFT JOIN operation_party r ON r.operation_id=o.id AND r.role_id=rr.id
              LEFT JOIN client rc ON rc.id=r.client_id
            """;

    record Slice(List<Long> ids, int total, int catalogTotal, int page, int pages) { }
    record FilteredBatch(List<Long> ids, Long cursor) { }

    private JdbcDemoRegistry() { }

    static Slice page(Connection c, Query query, int requested, int size, Map<String, Match> matches,
                      boolean durableLinks, int indexFormat) throws SQLException {
        if (size < 1 || size > JdbcDemoOperationLoader.PAGE_SIZE)
            throw new IllegalArgumentException("Недопустимый размер страницы");
        requested = Math.max(0, requested);
        if (outsideCatalog(query.from(), query.to())) return new Slice(List.of(),0,total(c),0,1);
        String search = Objects.toString(query.text(), "").trim().toLowerCase(Locale.ROOT);
        String sort = Objects.toString(query.sort(), "date");
        boolean partyColumns = !search.isEmpty() || sort.equals("payer") || sort.equals("recipient");
        String relation = " FROM operation o " + (partyColumns ? PARTIES : "");
        String payer = partyName("p", "pc"), recipient = partyName("r", "rc");
        List<Object> parameters = new ArrayList<>();
        String filter = filter(query, matches, durableLinks, parameters);
        String key = switch (sort) {
            case "id" -> javaOrder("o.reference");
            case "amount" -> "COALESCE(o.amount_rub,0)";
            case "payer" -> javaOrder(payer);
            case "recipient" -> javaOrder(recipient);
            default -> "o.operation_at";
        };
        String order = " ORDER BY " + key + (query.descending() ? " DESC" : " ASC") + ", o.id ASC";
        String scope = relation + filter;
        if (!search.isEmpty())
            return searchPage(c, filter, order, parameters, search, requested, size,indexFormat);
        int catalog = total(c);
        int count = filter.equals(" WHERE TRUE") ? catalog
                : Math.toIntExact(number(c, "SELECT COUNT(*) FROM operation o" + filter, parameters));
        int pages = pages(count, size), page = Math.min(requested, pages - 1);
        if (count == 0) return new Slice(List.of(), 0, catalog, page, pages);
        parameters.add(size);
        parameters.add((long)page * size);
        return new Slice(ids(c, "SELECT o.id" + scope + order + " LIMIT ? OFFSET ?", parameters),
                count, catalog, page, pages);
    }

    static FilteredBatch nextFiltered(Connection c, Query query, Map<String, Match> matches,
                                      boolean durableLinks, Long after, int size) throws SQLException {
        String search = Objects.toString(query.text(), "").trim().toLowerCase(Locale.ROOT);
        List<Object> parameters = new ArrayList<>();
        String relation = " FROM operation o ";
        String projection = "SELECT o.id";
        if (!search.isEmpty()) {
            relation += PARTIES;
            projection = searchProjection();
        }
        String scope = filter(query, matches, durableLinks, parameters);
        if (after != null) {
            scope += " AND o.id > ?";
            parameters.add(after);
        }
        parameters.add(size);
        var ids = new ArrayList<Long>(size);
        Long cursor = null;
        try (var q = prepare(c, projection + relation + scope + " ORDER BY o.id LIMIT ?", parameters);
             var rows = q.executeQuery()) {
            while (rows.next()) {
                cursor = rows.getLong(1);
                if (search.isEmpty() || rawSearchText(rows).toLowerCase(Locale.ROOT).contains(search))
                    ids.add(cursor);
            }
        }
        return new FilteredBatch(ids, cursor);
    }

    private static String filter(Query query, Map<String, Match> matches, boolean durableLinks,
                                 List<Object> parameters) {
        var filter = new StringBuilder(" WHERE TRUE");
        if (query.from() != null && query.from().isAfter(MIN_DATE)) {
            filter.append(" AND o.operation_at >= ?");
            parameters.add(query.from().atStartOfDay());
        }
        if (query.to() != null && query.to().isBefore(MAX_DATE)) {
            filter.append(" AND o.operation_at < ?");
            parameters.add(query.to().plusDays(1).atStartOfDay());
        }
        if ("Обработана".equals(query.status())) filter.append(" AND o.contract_id IS NOT NULL");
        if ("Не обработана".equals(query.status())) filter.append(" AND o.contract_id IS NULL");
        String method = Objects.toString(query.method(), "");
        if (!method.isBlank()) {
            filter.append(" AND o.contract_id IS NOT NULL");
            if (!List.of("Автоматически", "Вручную").contains(method)) filter.append(" AND FALSE");
            else {
                filter.append("Автоматически".equals(method) ? " AND (EXISTS " : " AND NOT (EXISTS ");
                filter.append("(SELECT 1 FROM JSON_TABLE(?, '$[*]' COLUMNS (ref VARCHAR(128) PATH '$')) a"
                        + " WHERE CAST(a.ref AS BINARY)=CAST(o.reference AS BINARY))");
                parameters.add(referenceJson(matches.entrySet().stream()
                        .filter(e -> "Автоматически".equals(e.getValue().method())).map(Map.Entry::getKey).toList()));
                if (durableLinks) {
                    filter.append(" OR (EXISTS (SELECT 1 FROM bulk_execution_result e"
                            + " WHERE e.operation_id=o.id AND e.applied_contract=o.contract_id)"
                            + " AND NOT EXISTS (SELECT 1 FROM JSON_TABLE(?, '$[*]' COLUMNS (ref VARCHAR(128) PATH '$')) m"
                            + " WHERE CAST(m.ref AS BINARY)=CAST(o.reference AS BINARY)))");
                    parameters.add(referenceJson(matches.keySet()));
                }
                filter.append(")");
            }
        }
        return filter.toString();
    }

    private static Slice searchPage(Connection c, String filter, String order, List<Object> parameters,
                                    String search, int requested, int size,int indexFormat) throws SQLException {
        String relation = " FROM operation o ";
        var digits = DIGIT_RUN.matcher(search);
        boolean indexed=indexFormat>0;
        boolean anchored = !indexed && digits.find();
        if (indexed) {
            var bindings=new ArrayList<Object>();
            Long anchor=indexFormat==2?JdbcRegistryIndex.numericAnchor(search):null;
            if (anchor==null) relation += indexedRelation(c,search,bindings);
            else {
                relation += " JOIN registry_gram hits ON hits.gram=? AND hits.operation_id=o.id ";
                bindings.add(anchor);
            }
            bindings.addAll(parameters);
            parameters=bindings;
        } else if (anchored) {
            // ROOT lowercase never introduces ASCII digits. A run cannot cross the spaces
            // between fields (including FIO parts), so one raw field must contain it.
            String anchor = search.substring(digits.start(), Math.min(digits.end(), digits.start() + 16));
            var bindings = new ArrayList<Object>();
            relation += " JOIN (" + digitCandidates(anchor, bindings) + ") hits ON hits.id=o.id ";
            bindings.addAll(parameters);
            parameters = bindings;
        }
        var selected = new ArrayList<Long>(size);
        var last = new ArrayList<Long>(size);
        int count = 0;
        boolean completeScan = !anchored && !indexed && filter.equals(" WHERE TRUE");
        int catalog = completeScan ? 0 : total(c);
        // On selective numeric candidates the server can assemble a compact projection.
        // Broad scans keep Java assembly, avoiding per-row SQL whitespace checks.
        String projection = anchored ? "SELECT o.id,CONCAT(o.reference,' '," + partyName("p", "pc")
                + ",' '," + partyInn("p", "pc") + ",' '," + partyName("r", "rc")
                + ",' '," + partyInn("r", "rc") + ",' ',COALESCE(o.payment_purpose,'null'))"
                : searchProjection();
        try (var q = prepare(c, projection + relation + PARTIES + filter + order, parameters)) {
            q.setFetchSize(Integer.MIN_VALUE); // Connector/J streaming result, consumed before page hydration.
            try (var rows = q.executeQuery()) {
                while (rows.next()) {
                    if (completeScan) catalog = Math.incrementExact(catalog);
                    String text = anchored ? rows.getString(2) : rawSearchText(rows);
                    if (!text.toLowerCase(Locale.ROOT).contains(search)) continue;
                    if (count % size == 0) last.clear();
                    long id = rows.getLong(1);
                    last.add(id);
                    if (count / size == requested) selected.add(id);
                    count = Math.incrementExact(count);
                }
            }
        }
        int pages = pages(count, size);
        return new Slice(List.copyOf(requested >= pages ? last : selected), count, catalog,
                Math.min(requested, pages - 1), pages);
    }

    private record Anchor(long first,long second) {
        private Anchor(String search,int offset) {
            this(JdbcRegistryIndex.gram(search,offset),JdbcRegistryIndex.gram(search,offset+1));
        }
    }

    private static String indexedRelation(Connection c,String search,List<Object> parameters) throws SQLException {
        Anchor anchor=selectAnchor(c,search);
        parameters.add(anchor.first());
        parameters.add(anchor.second());
        return """
                 JOIN (SELECT DISTINCT operation_id FROM registry_gram WHERE gram IN (?,?)) hits
                   ON hits.operation_id=o.id
                """;
    }

    private static Anchor selectAnchor(Connection c,String search) throws SQLException {
        int last=search.length()-4;
        Anchor best=new Anchor(search,0);
        if (last==0) return best;

        // At either parity of a match, one of the two adjacent trigrams starts at an indexed even offset.
        // This bound applies only to the estimate. The query above exhausts the chosen candidate set.
        int samples=Math.min(ANCHOR_SAMPLE_COUNT,last+1);
        int bestEstimate=ANCHOR_PROBE_ROWS+1;
        for (int i=0;i<samples;i++) {
            int offset=i==0?0:last-(int)((long)(i-1)*last/(samples-1));
            Anchor option=new Anchor(search,offset);
            int estimate=(int)number(c,"""
                    SELECT COUNT(*) FROM (
                        SELECT operation_id FROM registry_gram FORCE INDEX (PRIMARY)
                        WHERE gram IN (?,?) LIMIT ?
                    ) candidates
                    """,List.of(option.first(),option.second(),ANCHOR_PROBE_ROWS));
            if (estimate<bestEstimate) {
                best=option;
                bestEstimate=estimate;
            }
            if (bestEstimate<=1) break;
        }
        return best;
    }

    private static String searchProjection() {
        return "SELECT o.id,o.reference,o.payment_purpose," + partyProjection("p", "pc")
                + "," + partyProjection("r", "rc");
    }

    private static String digitCandidates(String anchor, List<Object> parameters) {
        String operationMatch = digitMatch(anchor, parameters, "op.reference", "op.payment_purpose");
        String partyMatch = digitMatch(anchor, parameters, "op_party.name", "op_party.inn");
        String clientMatch = digitMatch(anchor, parameters, "cl.name", "cl.inn", "cl.last_name", "cl.first_name", "cl.middle_name");
        return """
                SELECT op.id FROM operation op WHERE %s
                UNION DISTINCT
                SELECT op_party.operation_id FROM operation_party op_party
                WHERE op_party.role_id IN (SELECT id FROM operation_party_role WHERE code IN ('PAYER','RECIPIENT'))
                  AND (%s OR op_party.client_id IN (SELECT cl.id FROM client cl WHERE %s))
                """.formatted(operationMatch, partyMatch, clientMatch);
    }

    private static String digitMatch(String anchor, List<Object> parameters, String... columns) {
        var predicate = new java.util.StringJoiner(" OR ");
        for (String column : columns) {
            predicate.add("LOCATE(CAST(? AS BINARY),CAST(" + column + " AS BINARY))>0");
            parameters.add(anchor);
        }
        return predicate.toString();
    }

    private static String partyInn(String party, String client) {
        return "CASE WHEN " + blank(party + ".inn") + " THEN COALESCE(" + client
                + ".inn,'') ELSE " + party + ".inn END";
    }

    private static String partyProjection(String party, String client) {
        return party + ".name," + party + ".inn," + client + ".id IS NOT NULL," + client + ".name,"
                + client + ".last_name," + client + ".first_name," + client + ".middle_name," + client + ".inn";
    }

    static String indexSource() {
        return "SELECT o.id,o.reference,o.payment_purpose," + partyProjection("p","pc") + ","
                + partyProjection("r","rc") + " FROM operation o " + PARTIES;
    }

    static String rawSearchText(ResultSet rows) throws SQLException {
        return rows.getString(2) + " " + partyText(rows,4) + " " + partyText(rows,12) + " " + rows.getString(3);
    }

    private static String partyText(ResultSet rows, int column) throws SQLException {
        String name = rows.getString(column), inn = rows.getString(column + 1);
        if (rows.getBoolean(column + 2)) {
            name = rows.getString(column + 3);
            if (name == null || name.isBlank()) {
                name = String.join(" ", Objects.toString(rows.getString(column + 4), ""),
                        Objects.toString(rows.getString(column + 5), ""),
                        Objects.toString(rows.getString(column + 6), "")).trim();
            }
        }
        if (inn == null || inn.isBlank()) inn = rows.getString(column + 7);
        return Objects.toString(name, "Не определено") + " " + Objects.toString(inn, "");
    }

    static int total(Connection c) throws SQLException {
        return Math.toIntExact(number(c, "SELECT COUNT(*) FROM operation", List.of()));
    }

    static boolean outsideCatalog(LocalDate from, LocalDate to) {
        return from != null && from.isAfter(MAX_DATE) || to != null && to.isBefore(MIN_DATE)
                || from != null && to != null && from.isAfter(to);
    }

    static List<Long> ids(Connection c, String sql, List<?> parameters) throws SQLException {
        try (var q = prepare(c, sql, parameters); var rows = q.executeQuery()) {
            var ids = new ArrayList<Long>();
            while (rows.next()) ids.add(rows.getLong(1));
            return ids;
        }
    }

    static long number(Connection c, String sql, List<?> parameters) throws SQLException {
        try (var q = prepare(c, sql, parameters); var rows = q.executeQuery()) {
            if (!rows.next()) throw new SQLException("Registry aggregate missing");
            return rows.getLong(1);
        }
    }

    static PreparedStatement prepare(Connection c, String sql, List<?> parameters) throws SQLException {
        var q = c.prepareStatement(sql);
        try {
            if (q.getMaxRows() != 0 || q.getLargeMaxRows() != 0)
                throw new SQLException("JDBC row limit would truncate registry results");
            for (int i = 0; i < parameters.size(); i++) q.setObject(i + 1, parameters.get(i));
            return q;
        } catch (SQLException | RuntimeException error) {
            try { q.close(); } catch (SQLException closing) { error.addSuppressed(closing); }
            throw error;
        }
    }

    static String referenceJson(Iterable<String> references) {
        var json = new java.util.StringJoiner(",", "[", "]");
        for (String reference : references) {
            var quoted = new StringBuilder("\"");
            for (int i=0; i<reference.length(); i++) {
                char unit=reference.charAt(i);
                if (unit == '\\' || unit == '"') quoted.append('\\').append(unit);
                else if (unit < 32) quoted.append("\\u").append(java.util.HexFormat.of().toHexDigits(unit));
                else quoted.append(unit);
            }
            json.add(quoted.append('"').toString());
        }
        return json.toString();
    }

    private static String partyName(String p, String c) {
        return "CASE WHEN " + c + ".id IS NULL THEN COALESCE(" + p + ".name,'Не определено')"
                + " WHEN " + blank(c + ".name") + " THEN REGEXP_REPLACE(CONCAT(COALESCE(" + c
                + ".last_name,''),' ',COALESCE(" + c + ".first_name,''),' ',COALESCE(" + c
                + ".middle_name,'')),'\\\\A" + JAVA_TRIM + "+|" + JAVA_TRIM + "+\\\\z','') ELSE " + c + ".name END";
    }

    private static String blank(String value) {
        // Visible ASCII and the D0/D1 UTF-8 lead bytes (Cyrillic) cannot start Java whitespace.
        return "(CASE WHEN " + value + " IS NULL THEN TRUE WHEN ASCII(" + value
                + ") BETWEEN 33 AND 127 OR ASCII(" + value + ") IN (208,209) THEN FALSE"
                + " ELSE REGEXP_LIKE(" + value + ",'\\\\A" + JAVA_BLANK + "*\\\\z','c') END)";
    }

    private static String javaOrder(String sql) { return "CAST(CONVERT(" + sql + " USING utf16) AS BINARY)"; }
    private static int pages(int total, int size) { return (int)Math.max(1, (total + (long)size - 1) / size); }
}
