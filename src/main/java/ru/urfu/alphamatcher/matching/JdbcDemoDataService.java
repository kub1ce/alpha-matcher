package ru.urfu.alphamatcher.matching;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.demo.*;
import ru.urfu.alphamatcher.experiment.*;

/** One owned JDBC fixture source and one application-scoped employee state, shared by all tabs. */
@Service
@Primary
@Profile("jdbc-demo")
public class JdbcDemoDataService extends DemoDataService {
    private final DataSource source;
    private final JdbcBulkPreview bulkPreview;
    private final boolean durableLinks;
    private final JdbcRegistryIndex registryIndex;
    private final boolean relatedReadIndexes;
    private final String queuedOperationIdsSql;
    private final List<Party> clients;
    private final Map<String, Party> clientsById;
    private volatile ContractCatalog contractCatalog;
    private final JdbcState state;
    private final ThreadLocal<Connection> confirmation = new ThreadLocal<>();
    private final ThreadLocal<Connection> readSnapshot = new ThreadLocal<>();
    private byte[] persistedEmployeeSnapshot;
    private volatile boolean commitUncertain;

    private static final String RESTART_REQUIRED =
            "Результат подтверждения неизвестен. Перезапустите тестовую среду и проверьте сохранённую связь и журнал.";

    @Override public <T> T confirmAtomically(DemoOperationState state, java.util.function.Supplier<T> action) {
        if (confirmation.get() != null) return action.get();
        synchronized (state) {
            if (commitUncertain) throw new IllegalStateException(RESTART_REQUIRED);
            try (Connection c = source.getConnection()) {
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                c.setAutoCommit(false);
                // This profile owns a small fixture catalog. Lock its complete matching input
                // before revalidation so a concurrent SQL writer cannot change a SAFE premise.
                try (var statement = c.createStatement()) {
                    statement.execute("SET SESSION innodb_lock_wait_timeout=5");
                    if (!Arrays.equals(persistedEmployeeSnapshot, readEmployeeState(c, true)))
                        throw new IllegalStateException("История изменилась в другом экземпляре приложения. Обновите тестовую среду.");
                    statement.setFetchSize(Integer.MIN_VALUE); // Stream lock rows instead of buffering IDs in Connector/J.
                    for (String table : List.of("operation", "operation_party", "contract", "contract_party",
                            "client", "operation_status", "operation_party_role", "contract_party_role", "contract_type")) {
                        try (var rows = statement.executeQuery("SELECT id FROM " + table + " ORDER BY id FOR SHARE")) {
                            while (rows.next()) { /* Consume the complete locked input; no LIMIT. */ }
                        }
                    }
                }
                if (!catalog().inputs().equals(loadContractInputs(c)))
                    throw new IllegalStateException("Данные договоров изменились в MySQL. Обновите тестовую среду.");
                confirmation.set(c);
                try {
                    return state.atomically(() -> {
                        T result = action.get();
                        byte[] saved;
                        try { saved = saveEmployeeState(c, state); }
                        catch (SQLException e) {
                            throw new IllegalStateException("Не удалось сохранить пакет подтверждений", e);
                        }
                        try { c.commit(); }
                        catch (SQLException e) {
                            commitUncertain = true;
                            throw new IllegalStateException(RESTART_REQUIRED, e);
                        }
                        persistedEmployeeSnapshot = saved;
                        return result;
                    });
                } catch (RuntimeException | Error failure) {
                    try { c.rollback(); }
                    catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                    throw failure;
                }
                finally { confirmation.remove(); }
            } catch (SQLException e) { throw new IllegalStateException("Технический отказ сохранения подтверждений", e); }
        }
    }


    @Autowired
    public JdbcDemoDataService(@Value("${alpha.demo.url}") String url,
                              @Value("${alpha.demo.user}") String user,
                              @Value("${alpha.demo.password}") String password,
                              @Value("${alpha.demo.owner}") String owner,
                              @Value("${alpha.demo.startup-fixture:false}") boolean startupFixture) throws SQLException {
        this(localSource(url, user, password), owner, startupFixture);
    }

    public JdbcDemoDataService(String url, String user, String password, String owner) throws SQLException {
        this(url, user, password, owner, false);
    }
    private static DataSource localSource(String url, String user, String password) {
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:33368/(?:alpha_matcher_ui_demo|matching_engine_exp)(?:\\?[^;]*)?"))
            throw new IllegalArgumentException("Разрешена только изолированная локальная UI demo БД на порту 33368");
        return new LocalSource(url, user, password);
    }

    JdbcDemoDataService(DataSource source, String owner, boolean startupFixture) throws SQLException {
        this.source = source;
        this.bulkPreview = new JdbcBulkPreview(source);
        byte[] payload;
        DemoOperationState.EmployeeSnapshot employee;
        try (Connection c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT owner_token FROM ui_demo_owner")) {
                if (owner.isBlank() || !r.next() || !owner.equals(r.getString(1)) || r.next())
                    throw new SQLException("Не подтверждено владение синтетической UI demo БД");
            }
            queuedOperationIdsSql = queuedOperationIdsSql(c);
            clients = loadClients(c);
            clientsById = clients.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Party::id, p -> p));
            if (!Boolean.getBoolean("alpha.jdbc.lazy-contract-catalog"))
                contractCatalog = loadCatalog(c);
            payload = readEmployeeState(c, false);
            durableLinks = JdbcBulkExecution.installed(c);
            registryIndex = JdbcRegistryIndex.installed(c)
                    ?new JdbcRegistryIndex(source,JdbcRegistryIndex.supportsNumericKeys(c)):null;
            relatedReadIndexes = JdbcDemoRegistry.number(c,"""
                    SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND
                          ((table_name='operation' AND index_name='ix_m8_operation_recent_reference') OR
                           (table_name='operation_party' AND index_name='ix_m8_party_client_role_operation'))
                    """,List.of())==2;
            if (payload == null && !startupFixture)
                throw new SQLException("Снимок истории отсутствует. Подтвердите состояние из резервной копии.");
            if (payload != null) {
                try {
                    employee = EmployeeSnapshotCodec.decode(payload);
                    requireSameLinks(loadLinks(c, employee), employee.matches());
                } catch (java.io.IOException | ClassNotFoundException | RuntimeException e) {
                    throw new SQLException("Не удалось проверить сохранённую историю тестовой среды", e);
                }
            } else {
                employee = new DemoOperationState.EmployeeSnapshot(loadLinks(c, null), Map.of(), Set.of(), Map.of(),
                        List.of(), List.of(), 0, 0);
            }
            c.rollback();
        }
        state = new JdbcState(employee);
        persistedEmployeeSnapshot = payload;
    }

    private static String queuedOperationIdsSql(Connection c) throws SQLException {
        try (var s = c.createStatement(); var column = s.executeQuery("""
                SELECT CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='operation' AND COLUMN_NAME='reference'
                """)) {
            if (!column.next()) throw new SQLException("Не найдены реквизиты столбца operation.reference");
            String charset = column.getString(1);
            String collation = column.getString(2);
            if (charset == null || collation == null || !charset.matches("[a-zA-Z0-9_]+")
                    || !collation.matches("[a-zA-Z0-9_]+"))
                throw new SQLException("Не удалось определить кодировку и правила сравнения operation.reference");
            // Match the indexed column's collation for lookup, then require an exact reference.
            return """
                    SELECT o.id FROM JSON_TABLE(?, '$[*]' COLUMNS (
                        ref VARCHAR(128) CHARACTER SET %s COLLATE %s PATH '$'
                    )) q
                    JOIN operation o ON o.reference=q.ref AND CAST(o.reference AS BINARY)=CAST(q.ref AS BINARY)
                    ORDER BY o.id LIMIT ?
                    """.formatted(charset, collation);
        }
    }

    private final class JdbcState extends DemoOperationState {
        JdbcState(EmployeeSnapshot employee) {
            super(List.of());
            installEmployeeSnapshot(employee);
        }

        @Override public synchronized void restoreEmployeeSnapshot(EmployeeSnapshot employee) {
            requireSameLinks(read(c -> loadLinks(c, employee)), employee.matches());
            installEmployeeSnapshot(employee);
        }

        @Override public RegistryPage registry(Query query, int page, int size) {
            synchronized (this) {
                return read(c -> {
                    String method = query.method();
                    var matches = "Автоматически".equals(method) || "Вручную".equals(method)
                            ? confirmedMatches() : Map.<String, Match>of();
                    int indexFormat=0;
                    if (registryIndex!=null && Objects.toString(query.text(),"").trim().toLowerCase(Locale.ROOT).length()>=4) {
                        indexFormat=registryIndex.format(c);
                        if (registryIndex.needsRebuild(indexFormat)) registryIndex.request();
                    }
                    var slice = JdbcDemoRegistry.page(c, query, page, size, matches, durableLinks,indexFormat);
                    var operations = JdbcDemoOperationLoader.byIds(c, slice.ids()).stream()
                            .map(JdbcDemoDataService.this::view).toList();
                    return new RegistryPage(new Page(operations, slice.total(), slice.page(), slice.pages()),
                            slice.catalogTotal());
                });
            }
        }

        @Override public Page search(Query query, int page, int size) { return registry(query, page, size).page(); }
        @Override public synchronized Match match(String id) {
            var legacy=super.match(id);
            if (legacy!=null || !durableLinks) return legacy;
            return read(c -> {
                var entry=durableLink(c,id,true);
                return entry==null?null:new Match(contractReference(entry.contract()),"Автоматически","",
                        "Подтверждено пакетным сопоставлением",entry.actor(),entry.at(),"");
            });
        }
        @Override public synchronized boolean isLinked(String id) { return match(id)!=null; }
        @Override public int durableAutomaticCount() {
            if (!durableLinks) return 0;
            return read(c -> {
                return Math.toIntExact(JdbcDemoRegistry.number(c, """
                    SELECT COUNT(*) FROM bulk_execution_result r JOIN operation o
                        ON o.id=r.operation_id AND o.contract_id=r.applied_contract
                    WHERE r.outcome='APPLIED' AND NOT EXISTS (
                        SELECT 1 FROM JSON_TABLE(?, '$[*]' COLUMNS (ref VARCHAR(128) PATH '$')) a
                        WHERE CAST(a.ref AS BINARY)=CAST(o.reference AS BINARY))
                    """, List.of(JdbcDemoRegistry.referenceJson(confirmedMatches().keySet()))));
            });
        }

        @Override public synchronized List<AuditEvent> auditEventsForOperation(String id) {
            var prior=super.auditEventsForOperation(id);
            if (!durableLinks) return prior;
            return read(c -> {
                var entry=durableLink(c,id,false);
                if (entry==null) return prior;
                var events=new ArrayList<>(prior);
                events.add(durableEvent(entry,id));
                return List.copyOf(events);
            });
        }
        @Override public void visitDurableAudit(boolean descending, java.util.function.Consumer<AuditEvent> visitor) {
            if (!durableLinks) return;
            read(c -> {
                String direction = descending ? " DESC" : " ASC";
                try (var q = c.prepareStatement("""
                        SELECT job_id,operation_id,applied_contract,actor,recorded_at,operation_reference
                        FROM bulk_execution_result WHERE outcome='APPLIED' ORDER BY recorded_at
                        """ + direction + ", CAST(CONCAT('BULK-',job_id,'-',operation_id) AS BINARY)" + direction)) {
                    q.setFetchSize(Integer.MIN_VALUE);
                    try (var r = q.executeQuery()) {
                        while (r.next()) visitor.accept(durableEvent(new DurableLink(r.getString(1),r.getLong(2),
                                r.getLong(3),r.getString(4),r.getObject(5,LocalDateTime.class)),r.getString(6)));
                    }
                }
                return null;
            });
        }
        @Override public List<String> durableAuditActors() {
            if (!durableLinks) return List.of();
            return read(c -> {
                try (var q = c.prepareStatement("SELECT DISTINCT actor FROM bulk_execution_result WHERE outcome='APPLIED'");
                     var r = q.executeQuery()) {
                    var actors = new ArrayList<String>();
                    while (r.next()) actors.add(r.getString(1));
                    return List.copyOf(actors);
                }
            });
        }
        private AuditEvent durableEvent(DurableLink entry, String reference) {
            return new AuditEvent("BULK-"+entry.job()+"-"+entry.id(),entry.at(),reference,"Автоматическая привязка",
                    "",contractReference(entry.contract()),entry.actor(),"Автоматически",entry.job(),"",
                    "Подтверждено пакетным сопоставлением","","","");
        }
        @Override public int totalOperations() { return read(JdbcDemoRegistry::total); }
        @Override public Operation operation(String reference) {
            return read(c -> {
                var row = JdbcDemoOperationLoader.byReference(c, reference);
                return row == null ? null : view(row);
            });
        }
        @Override public List<Operation> all() { return read(c -> loadOperations(c, null, null)); }
        @Override public List<Operation> operationsInPeriod(LocalDate from, LocalDate to) {
            return read(c -> loadOperations(c, from, to));
        }
        @Override public void visitOperationsInPeriod(LocalDate from, LocalDate to,
                                                       java.util.function.Consumer<Operation> visitor) {
            read(c -> {
                JdbcDemoOperationLoader.load(c, from, to, row -> visitor.accept(view(row)));
                return null;
            });
        }
        @Override public synchronized List<Operation> queuedOperations() {
            return read(c -> loadByReferences(c, queuedIds()));
        }
        @Override public synchronized List<Operation> queuedOperations(int limit) {
            var keys = queueReasons().keySet();
            if (keys.isEmpty()) return List.of();
            return read(c -> JdbcDemoOperationLoader.byIds(c, JdbcDemoRegistry.ids(c, queuedOperationIdsSql,
                    List.of(JdbcDemoRegistry.referenceJson(keys), limit))).stream()
                    .map(JdbcDemoDataService.this::view).toList());
        }
        @Override public synchronized Map<String,String> queueReasons() {
            var result = new HashMap<String,String>();
            for (var id : queuedIds()) if (isManualQueued(id))
                result.put(id, Objects.requireNonNullElse(manualReason(id), ""));
            return result;
        }
        @Override public synchronized int pendingReviewCount() {
            return Math.toIntExact(queueReasons().entrySet().stream().filter(e ->
                    !e.getValue().isBlank() || manualReason(e.getKey()) == null && previousContract(e.getKey()) != null).count());
        }

        @Override public synchronized List<String> contractsForLinkChange(String id, List<Contract> contracts) {
                var permitted = super.contractsForLinkChange(id, contracts);
                var current = match(id);
                if (current == null) return permitted;
                var operation = read(c -> JdbcDemoOperationLoader.byReference(c, id));
                if (operation == null) return List.of();
                return permitted.stream().filter(reference -> current.contractId().equals(reference)
                        || catalog().inputs().stream().anyMatch(contract -> reference.equals(contractReference(contract.id()))
                        && List.of("PAYER", "RECIPIENT").stream()
                                .allMatch(role -> verifiedRole(operation.operation().parties().get(role), contract.roles().get(role)))))
                        .toList();
            }
            @Override protected void beforeLinkChange(String id, Match previous, String next) { persistLink(id, previous, next); }
            @Override protected String originalPackage(String id, Match previous) {
                if (!durableLinks || previous == null || super.match(id) != null) return null;
                return read(c -> {
                    var entry = durableLink(c, id, true);
                    if (entry == null || !contractReference(entry.contract()).equals(previous.contractId()))
                        throw new SQLException("Текущая пакетная привязка изменилась.");
                    return entry.job();
                });
            }
            @Override public synchronized void changeLinkIfUnchanged(String id, Match expected, String contract,
                                                                     List<Contract> contracts, String justification) {
                confirmAtomically(this, () -> {
                    super.changeLinkIfUnchanged(id, expected, contract, contracts, justification);
                    return null;
                });
            }
            @Override public synchronized void unlinkIfUnchanged(String id, Match expected) {
                confirmAtomically(this, () -> { super.unlinkIfUnchanged(id, expected); return null; });
            }
            @Override public synchronized void link(String id, String contract, String method, List<Contract> contracts, String explanation, String actor, String run) {
                confirmAtomically(this, () -> { super.link(id,contract,method,contracts,explanation,actor,run); return null; });
            }
            @Override public synchronized void unlink(String id, String actor) {
                confirmAtomically(this, () -> { super.unlink(id,actor); return null; });
            }
            @Override public synchronized boolean queueForManual(String id, String reason, String actor, String run) {
                return confirmAtomically(this, () -> super.queueForManual(id,reason,actor,run));
            }
            @Override public synchronized void recordRun(BatchRun run) {
                confirmAtomically(this, () -> { super.recordRun(run); return null; });
            }
    }

    private Map<String, DemoOperationState.Match> loadLinks(Connection c, DemoOperationState.EmployeeSnapshot snapshot)
            throws SQLException {
        var result = new HashMap<String, DemoOperationState.Match>();
        var corrections = new HashSet<String>();
        if (durableLinks && snapshot != null) {
            for (var event : snapshot.audit()) {
                if (event.runId() == null || !event.runId().matches("[0-9a-f-]{36}")
                        || !"Вручную".equals(event.method())
                        || !"Обработана (Автоматически)".equals(event.previousState())
                        || !List.of("Изменение договора", "Отвязка договора").contains(event.action())) continue;
                var original = durableLink(c, event.operationId(), false);
                if (original == null || !event.runId().equals(original.job())
                        || !contractReference(original.contract()).equals(event.previous())
                        || !corrections.add(event.operationId()))
                    throw new SQLException("Ручная коррекция не соответствует исходному событию пакета.");
            }
        }
        String corrected = """
                EXISTS (SELECT 1 FROM JSON_TABLE(?, '$[*]' COLUMNS (ref VARCHAR(128) PATH '$')) p
                        WHERE CAST(p.ref AS BINARY)=CAST(o.reference AS BINARY))
                """;
        String references = JdbcDemoRegistry.referenceJson(corrections);
        if (durableLinks) {
            try (var q=JdbcDemoRegistry.prepare(c,"""
                    SELECT 1 FROM bulk_execution_result r LEFT JOIN operation o ON o.id=r.operation_id
                    WHERE r.outcome='APPLIED' AND (o.id IS NULL
                       OR CAST(o.reference AS BINARY)<>CAST(r.operation_reference AS BINARY)
                       OR (NOT (o.contract_id <=> r.applied_contract) AND NOT %s)) LIMIT 1
                    """.formatted(corrected), List.of(references)); var rows=q.executeQuery()) {
                if (rows.next()) throw new SQLException("Финансовая связь расходится с пакетным аудитом.");
            }
        }
        String legacyOnly=durableLinks?"""
                 AND (%s OR NOT EXISTS (SELECT 1 FROM bulk_execution_result r
                                     WHERE r.operation_id=o.id AND r.applied_contract=o.contract_id))
                """.formatted(corrected):"";
        try (var q = JdbcDemoRegistry.prepare(c, "SELECT reference,contract_id FROM operation o WHERE contract_id IS NOT NULL"
                + legacyOnly, durableLinks ? List.of(references) : List.of());
             var rows = q.executeQuery()) {
            while (rows.next()) result.put(rows.getString(1),
                    new DemoOperationState.Match(contractReference(rows.getLong(2)), "Вручную",
                            "Исходная синтетическая привязка", "", "Fixture", LocalDateTime.of(2026,10,1,10,0), ""));
        }
        return result;
    }

    private record DurableLink(String job,long id,long contract,String actor,LocalDateTime at) { }

    private static DurableLink durableLink(Connection c,String reference,boolean current) throws SQLException {
        try (var q=JdbcDemoRegistry.prepare(c,"""
                SELECT r.job_id,o.id,r.applied_contract,r.actor,r.recorded_at
                FROM operation o JOIN bulk_execution_result r ON r.operation_id=o.id
                WHERE o.reference=? AND CAST(o.reference AS BINARY)=CAST(? AS BINARY) AND r.outcome='APPLIED'
                """+(current ? " AND r.applied_contract=o.contract_id" : ""),List.of(reference,reference)); var rows=q.executeQuery()) {
            return rows.next()?new DurableLink(rows.getString(1),rows.getLong(2),rows.getLong(3),rows.getString(4),
                    rows.getObject(5,LocalDateTime.class)):null;
        }
    }

    private static void requireSameLinks(Map<String, DemoOperationState.Match> physical,
                                         Map<String, DemoOperationState.Match> saved) {
        if (!physical.keySet().equals(saved.keySet()) || physical.entrySet().stream().anyMatch(entry ->
                !entry.getValue().contractId().equals(saved.get(entry.getKey()).contractId())))
            throw new IllegalStateException("Подтверждённые связи MySQL не соответствуют сохранённой истории сотрудников");
    }

    @FunctionalInterface
    private interface Read<T> { T run(Connection connection) throws SQLException; }

    @Override public <T> T readMatchingSnapshot(java.util.function.Supplier<T> action) {
        if (confirmation.get() != null || readSnapshot.get() != null) return action.get();
        return read(c -> {
            readSnapshot.set(c);
            try { return action.get(); }
            finally { readSnapshot.remove(); }
        });
    }

    private <T> T read(Read<T> action) {
        try {
            Connection c = confirmation.get();
            if (c == null) c = readSnapshot.get();
            if (c != null) return action.run(c);
            try (var owned = source.getConnection()) {
                owned.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                owned.setReadOnly(true);
                owned.setAutoCommit(false);
                try { return action.run(owned); }
                finally { owned.rollback(); }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Технический отказ чтения JDBC. Повторите действие после восстановления БД.", e);
        }
    }

    private static boolean verifiedRole(MatchingModel.Party current, MatchingModel.Party replacement) {
        return current != null && replacement != null && current.consistent() && replacement.consistent()
                && current.effectiveInn().equals(replacement.effectiveInn());
    }

    private byte[] readEmployeeState(Connection c, boolean lock) throws SQLException {
        String sql = "SELECT employee_snapshot FROM ui_demo_state WHERE id=1" + (lock ? " FOR UPDATE" : "");
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            if (!r.next()) throw new SQLException("Нет строки состояния owned UI demo");
            return r.getBytes(1);
        }
    }

    private byte[] saveEmployeeState(Connection c, DemoOperationState state) throws SQLException {
        try {
            byte[] payload = EmployeeSnapshotCodec.encode(state.employeeSnapshot());
            try (var update = c.prepareStatement("UPDATE ui_demo_state SET employee_snapshot=? WHERE id=1")) {
                update.setBytes(1, payload);
                if (update.executeUpdate() != 1) throw new SQLException("Не сохранена история подтверждений");
            }
            return payload;
        } catch (java.io.IOException e) { throw new SQLException("Не удалось сериализовать историю подтверждений",e); }
    }

    @Override public DemoOperationState state() { return state; }
    @Override public List<Operation> fixtures() { return state.all(); }
    @Override public List<Operation> operations() {
        return state.search(new DemoOperationState.Query("", null, null, "", "", "date", true),0,6).rows();
    }

    @Override public DemoOperationState.Page linkedOperations(String contractId, DemoOperationState ignored,
                                                               int requested, int size) {
        Long id=detailId(contractId,"DB-");
        return id==null ? new DemoOperationState.Page(List.of(),0,0,1) :
                read(c -> relationPage(c,"o.contract_id=?",List.of(id),"o.operation_at DESC,o.id",requested,size));
    }

    @Override public int linkedCount(String contractId, DemoOperationState ignored) {
        Long id=detailId(contractId,"DB-");
        return id==null ? 0 : read(c -> relationCount(c,"o.contract_id=?",List.of(id)));
    }

    private static final String CLIENT_PARTY_OPERATION = """
            EXISTS (SELECT 1 FROM operation_party p JOIN operation_party_role r ON r.id=p.role_id
                    WHERE p.operation_id=o.id AND p.client_id=? AND r.code IN ('PAYER','RECIPIENT'))
            """;

    @Override public DemoOperationState.Page clientOperations(String clientId, Set<String> beneficiaryContracts,
                                                               DemoOperationState ignored, int requested, int size) {
        Long id=detailId(clientId,"DB-CL-");
        if (id==null) return new DemoOperationState.Page(List.of(),0,0,1);
        var beneficiaryIds=beneficiaryContracts.stream().map(contract -> detailId(contract,"DB-"))
                .filter(Objects::nonNull).map(String::valueOf).toList();
        if (relatedReadIndexes)
            return read(c -> indexedClientPage(c,id,beneficiaryIds,requested,size));
        String where=CLIENT_PARTY_OPERATION;
        List<Object> parameters=new ArrayList<>(List.of(id));
        if (!beneficiaryIds.isEmpty()) {
            where+=" OR o.contract_id IN (SELECT id FROM JSON_TABLE(?, '$[*]' COLUMNS (id BIGINT PATH '$')) b)";
            parameters.add("["+String.join(",",beneficiaryIds)+"]");
        }
        String condition=where;
        return read(c -> relationPage(c,condition,parameters,
                "o.operation_at DESC,CAST(CONVERT(o.reference USING utf16) AS BINARY),o.id",requested,size));
    }

    private DemoOperationState.Page indexedClientPage(Connection c,long client,List<String> beneficiaryIds,
                                                       int requested,int size) throws SQLException {
        String partyScope="""
                FROM operation_party p
                WHERE p.client_id=? AND p.role_id IN
                    (SELECT id FROM operation_party_role WHERE code IN ('PAYER','RECIPIENT'))
                """;
        List<Object> parameters=new ArrayList<>(List.of(client));
        String condition=CLIENT_PARTY_OPERATION;
        String countQuery="SELECT COUNT(DISTINCT p.operation_id) "+partyScope;
        if (!beneficiaryIds.isEmpty()) {
            String beneficiary="o.contract_id IN (SELECT id FROM JSON_TABLE(?, '$[*]' COLUMNS (id BIGINT PATH '$')) b)";
            parameters.add("["+String.join(",",beneficiaryIds)+"]");
            condition+=" OR "+beneficiary;
            countQuery="SELECT COUNT(*) FROM (SELECT p.operation_id "+partyScope+" UNION SELECT o.id FROM operation o WHERE "
                    +beneficiary+") related";
        }
        int total=Math.toIntExact(JdbcDemoRegistry.number(c,countQuery,parameters));
        int pages=Math.max(1,Math.ceilDiv(total,size));
        int page=Math.min(Math.max(0,requested),pages-1);
        if (total==0) return new DemoOperationState.Page(List.of(),0,page,pages);

        long offset=(long)page*size;
        int limit=(int)Math.min(size,total-offset);
        long tail=total-offset-limit;
        boolean reverse=tail<offset;
        String order=reverse?"o.operation_at ASC,o.registry_reference_key DESC,o.id DESC"
                :"o.operation_at DESC,o.registry_reference_key,o.id";
        String from=" FROM operation o ";
        if (total>=4096) {
            // For dense relations the ordered covering index can stop after the requested window.
            // Keep the membership test correlated so the planner cannot materialize all members first.
            from+="FORCE INDEX (ix_m8_operation_recent_reference) ";
            condition="CASE WHEN ("+condition+") THEN TRUE ELSE FALSE END";
        }
        parameters.add(limit);
        parameters.add(Math.min(offset,tail));
        var ids=JdbcDemoRegistry.ids(c,"SELECT o.id"+from+"WHERE "+condition+" ORDER BY "+order+" LIMIT ? OFFSET ?",
                parameters);
        var rows=JdbcDemoOperationLoader.byIds(c,reverse?ids.reversed():ids).stream().map(this::view).toList();
        return new DemoOperationState.Page(rows,total,page,pages);
    }

    @Override public List<String> clientOperationContracts(String clientId, DemoOperationState ignored) {
        Long id=detailId(clientId,"DB-CL-");
        if (id==null) return List.of();
        return read(c -> JdbcDemoRegistry.ids(c,"SELECT o.contract_id FROM operation o WHERE o.contract_id IS NOT NULL AND "
                +CLIENT_PARTY_OPERATION+" GROUP BY o.contract_id ORDER BY MIN(o.id)",List.of(id))
                .stream().map(this::contractReference).toList());
    }

    private DemoOperationState.Page relationPage(Connection c,String condition,List<?> parameters,
                                                 String order,int requested,int size) throws SQLException {
        int total=relationCount(c,condition,parameters);
        int pages=Math.max(1,Math.ceilDiv(total,size));
        int page=Math.min(Math.max(0,requested),pages-1);
        if (total==0) return new DemoOperationState.Page(List.of(),total,page,pages);
        List<Object> bindings=new ArrayList<>(parameters);
        bindings.add(size);
        bindings.add((long)page*size);
        var ids=JdbcDemoRegistry.ids(c,"SELECT o.id FROM operation o WHERE "+condition
                +" ORDER BY "+order+" LIMIT ? OFFSET ?",bindings);
        var operations=JdbcDemoOperationLoader.byIds(c,ids).stream().map(this::view).toList();
        return new DemoOperationState.Page(operations,total,page,pages);
    }

    private static int relationCount(Connection c,String condition,List<?> parameters) throws SQLException {
        return Math.toIntExact(JdbcDemoRegistry.number(c,"SELECT COUNT(*) FROM operation o WHERE "+condition,parameters));
    }

    private static Long detailId(String reference,String prefix) {
        if (reference==null || !reference.startsWith(prefix)) return null;
        try { return Long.valueOf(reference.substring(prefix.length())); }
        catch (NumberFormatException invalid) { return null; }
    }
    @Override public List<Operation> matchingBatch() { return List.of(); }
    @Override public List<Contract> contracts() { return catalog().contracts(); }
    @Override public Contract contract(String id) { return contracts().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null); }
    @Override public String contractNumber(Contract contract) { return contract.name(); }
    @Override public String contractDescription(Contract contract) { return contract.type(); }
    @Override public Party contractParty(Contract contract, boolean payer, DemoOperationState state) {
        return catalog().inputs().stream().filter(input -> contractReference(input.id()).equals(contract.id()))
                .map(input -> input.roles().get(payer ? "PAYER" : "RECIPIENT"))
                .filter(Objects::nonNull).findFirst().map(this::party).orElse(null);
    }
    @Override public List<Party> parties() { return clients; }
    @Override public List<Party> parties(DemoOperationState ignored) { return clients; }
    @Override public Party party(String name) { return clients.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null); }
    @Override public Party client(String id) { return id == null ? null : clientsById.get(id); }
    @Override public Party client(String id, DemoOperationState ignored) { return client(id); }
    @Override public boolean hasMatchingSource() { return true; }
    public JdbcBulkPreview bulkPreview() { return bulkPreview; }
    @Override public String operationStatus(String reference) {
        return read(c -> {
            try (var q = JdbcDemoRegistry.prepare(c, """
                    SELECT s.code FROM operation o JOIN operation_status s ON s.id=o.status_id
                     WHERE o.reference=? AND CAST(o.reference AS BINARY)=CAST(? AS BINARY)
                    """,List.of(reference,reference)); var rows = q.executeQuery()) {
                return rows.next() ? rows.getString(1) : "UNKNOWN";
            }
        });
    }
    @Override public String sourceLabel() { return ""; }

    @Override public MatchingModel.Decision analyze(String reference) {
        return analyzeReference(reference, null, false);
    }

    @Override public MatchingModel.Decision analyze(Operation shown) {
        return analyzeReference(shown.id(), shown, false);
    }

    @Override public MatchingModel.Decision reanalyze(Operation shown) {
        return analyzeReference(shown.id(), shown, true);
    }

    private MatchingModel.Decision analyzeReference(String reference, Operation shown, boolean repeat) {
        if (commitUncertain) throw new IllegalStateException(RESTART_REQUIRED);
        return read(c -> {
            var row = JdbcDemoOperationLoader.byReference(c, reference);
            if (row == null) throw new IllegalArgumentException("Неизвестный внешний номер операции");
            if (shown != null) requireCurrentOperation(shown, row);
            var repository = new JdbcMatchingRepository();
            MatchingRepository input = repeat ? repeatInput(repository) : repository;
            return new MatchingEngine(input, new PurposeParser()).match(c, row.operation().id());
        });
    }

    private static MatchingRepository repeatInput(JdbcMatchingRepository source) {
        return new MatchingRepository() {
            @Override public Optional<MatchingModel.Operation> operation(Connection c, long id) throws SQLException {
                return source.operation(c, id).map(op -> {
                    if (!op.activeStatus() || !Set.of("LOADED", "PROCESSED").contains(op.status())) return op;
                    // Preview eligibility only. The payment's status and confirmed link stay in the source.
                    return new MatchingModel.Operation(op.id(), op.purpose(), "LOADED", true, null, op.parties());
                });
            }
            @Override public List<MatchingModel.Candidate> candidates(Connection c, PurposeParser.Features features)
                    throws SQLException {
                return source.candidates(c, features);
            }
            @Override public CandidateSearch.Result discover(Connection c, PurposeParser.Features features)
                    throws SQLException {
                return source.discover(c, features);
            }
        };
    }

    @Override public void requireCurrentOperation(Operation shown) {
        read(c -> {
            requireCurrentOperation(shown, JdbcDemoOperationLoader.byReference(c, shown.id()));
            return null;
        });
    }

    private static void requireCurrentOperation(Operation shown, JdbcDemoOperationLoader.Row row) {
        if (row == null || !shown.sourceVersion().equals(JdbcDemoOperationLoader.version(row)))
            throw new IllegalStateException("Данные операции изменились в MySQL. Откройте операцию заново.");
    }

    private void persistLink(String reference, DemoOperationState.Match previous, String next) {
        Connection existing = confirmation.get();
        if (existing == null) throw new IllegalStateException("Нет транзакции подтверждения");
        persistLink(existing, reference, previous, next);
    }

    private void persistLink(Connection c, String reference, DemoOperationState.Match previous, String next) {
            try {
                var row = JdbcDemoOperationLoader.byReference(c, reference);
                if (row == null) throw new IllegalStateException("Операция больше не существует");
                long id = row.operation().id();
                try (var s = c.prepareStatement("SELECT contract_id FROM operation WHERE id=? FOR UPDATE")) {
                    s.setLong(1, id);
                    try (var r = s.executeQuery()) {
                        if (!r.next() || !Objects.equals(r.getObject(1, Long.class),
                                previous == null ? null : numericContract(previous.contractId())))
                            throw new IllegalStateException("Состояние операции в MySQL изменилось. Подтверждение отменено.");
                    }
                }
                try (var s = c.prepareStatement("UPDATE operation SET contract_id=? WHERE id=?")) {
                    if (next == null) s.setNull(1, Types.BIGINT); else s.setLong(1, numericContract(next));
                    s.setLong(2, id);
                    if (s.executeUpdate() != 1) throw new SQLException("Операция не обновлена");
                }
            } catch (SQLException e) { throw new IllegalStateException("Не удалось сохранить подтверждённую связь в тестовой MySQL", e); }
    }

    private long numericContract(String id) {
        if (contract(id) == null || !id.matches("DB-[0-9]+")) throw new IllegalArgumentException("Неизвестный договор");
        return Long.parseLong(id.substring(3));
    }

    private List<Party> loadClients(Connection c) throws SQLException {
        var result = new ArrayList<Party>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT c.*,t.name type_name FROM client c JOIN client_type t ON t.id=c.client_type_id ORDER BY c.id")) {
            while (r.next()) {
                String name = r.getString("name");
                if (name == null || name.isBlank()) name = String.join(" ", value(r,"last_name"), value(r,"first_name"), value(r,"middle_name")).trim();
                result.add(new Party("DB-CL-" + r.getLong("id"), name, r.getString("type_name"), value(r,"inn"), "", "", value(r,"pin"), r.getBoolean("is_bank_client"), r.getBoolean("is_in_terrorist_extremist_list")));
            }
        }
        return List.copyOf(result);
    }

    private Party party(MatchingModel.Party p) {
        if (p == null) return new Party("", "Не определено", "Не указано", "", "", "", "");
        if (p.clientId() != null) {
            Party known = client("DB-CL-" + p.clientId());
            if (known != null) return new Party(known.id(), known.name(), known.type(), Objects.toString(p.effectiveInn(), ""), "", "", known.pin(), known.bankClient(), known.terroristOrExtremistListed());
        }
        return new Party("", Objects.toString(p.name(), "Не определено"), "Внешний участник", Objects.toString(p.effectiveInn(), ""), "", "", "");
    }

    private record ContractCatalog(List<ContractInput> inputs, List<Contract> contracts) {}

    private ContractCatalog catalog() {
        ContractCatalog current = contractCatalog;
        if (current != null) return current;
        synchronized (this) {
            if (contractCatalog == null) contractCatalog = read(this::loadCatalog);
            return contractCatalog;
        }
    }

    private ContractCatalog loadCatalog(Connection c) throws SQLException {
        List<ContractInput> inputs = loadContractInputs(c);
        return new ContractCatalog(inputs, inputs.stream().map(this::contractView).toList());
    }

    private record ContractInput(long id, String number, String date, String type,
                                 Map<String, MatchingModel.Party> roles) { }

    private Contract contractView(ContractInput input) {
        var roles = input.roles();
        Party payer = party(roles.get("PAYER")), recipient = party(roles.get("RECIPIENT"));
        return new Contract(contractReference(input.id()), input.number(), input.date(), payer.name(), recipient.name(),
                input.type(), roles.containsKey("BENEFICIARY") ? party(roles.get("BENEFICIARY")) : null, payer.id(), recipient.id());
    }

    private List<ContractInput> loadContractInputs(Connection c) throws SQLException {
        var result = new ArrayList<ContractInput>();
        var parties = new LinkedHashMap<Long, Map<String, MatchingModel.Party>>();
        try (var s = c.createStatement()) {
            s.setFetchSize(Integer.MIN_VALUE);
            try (var r = s.executeQuery("""
                    SELECT p.contract_id,r.code,p.client_id,p.inn,c.inn client_inn,p.name,
                           c.name client_name,c.last_name,c.first_name,c.middle_name,r.is_active
                    FROM contract_party p JOIN contract_party_role r ON r.id=p.role_id
                    LEFT JOIN client c ON c.id=p.client_id ORDER BY p.contract_id,p.id
                    """)) {
                while (r.next()) parties.computeIfAbsent(r.getLong(1), k -> new LinkedHashMap<>()).put(r.getString(2),
                        new MatchingModel.Party(r.getObject(3,Long.class),r.getString(4),r.getString(5),r.getBoolean(11),
                                r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10)));
            }
        }
        try (var s = c.createStatement()) {
            s.setFetchSize(Integer.MIN_VALUE);
            try (var r = s.executeQuery("""
                    SELECT c.id,c.contract_number,c.contract_date,t.name FROM contract c
                    JOIN contract_type t ON t.id=c.contract_type_id ORDER BY c.id
                    """)) {
                while (r.next()) {
                    long id = r.getLong(1);
                    var roles = parties.remove(id);
                    result.add(new ContractInput(id, r.getString(2), Objects.toString(r.getDate(3), ""), r.getString(4),
                            roles == null ? Map.of() : Map.copyOf(roles)));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<Operation> loadOperations(Connection c, LocalDate from, LocalDate to) throws SQLException {
        var result = new ArrayList<Operation>();
        JdbcDemoOperationLoader.load(c, from, to, row -> {
            if (from != null && result.size() == 10_000)
                throw new IllegalArgumentException("Период содержит более 10 000 операций. Сузьте период и повторите поиск.");
            result.add(view(row));
        });
        return List.copyOf(result);
    }

    private List<Operation> loadByReferences(Connection c, Set<String> references) throws SQLException {
        var keys = List.copyOf(references);
        var rows = new ArrayList<Operation>();
        for (int start=0; start<keys.size(); start+=JdbcDemoOperationLoader.PAGE_SIZE) {
            var page = keys.subList(start, Math.min(keys.size(), start+JdbcDemoOperationLoader.PAGE_SIZE));
            String marks = String.join(",", Collections.nCopies(page.size(), "?"));
            var ids = JdbcDemoRegistry.ids(c,
                    "SELECT id FROM operation WHERE CAST(reference AS BINARY) IN (" + marks + ") ORDER BY id", page);
            for (var row : JdbcDemoOperationLoader.byIds(c, ids)) rows.add(view(row));
        }
        return List.copyOf(rows);
    }

    private Operation view(JdbcDemoOperationLoader.Row row) {
        var operation = row.operation();
        Long linked = operation.linkedContractId();
        return new Operation(row.reference(), row.timestamp(), party(operation.parties().get("PAYER")),
                party(operation.parties().get("RECIPIENT")),
                Objects.requireNonNullElse(row.amount(), BigDecimal.ZERO), operation.purpose(),
                linked == null ? "" : contractReference(linked), linked == null ? "" : "Вручную",
                "", List.of(), linked == null ? "" : "Исходная синтетическая привязка",
                linked == null ? "" : "Fixture", linked == null ? null : LocalDateTime.of(2026,10,1,10,0),
                JdbcDemoOperationLoader.version(row));
    }

    private static String value(ResultSet r,String column) throws SQLException { return Objects.toString(r.getString(column),""); }

    private record LocalSource(String url,String user,String password) implements DataSource {
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url,user,password); }
        @Override public Connection getConnection(String u,String p) throws SQLException { return DriverManager.getConnection(url,u,p); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter writer) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 10; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("alpha-matcher"); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { if(type.isInstance(this))return type.cast(this);throw new SQLException("Unsupported unwrap"); }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
        @Override public String toString() { return "Owned UI demo DataSource"; }
    }
}
