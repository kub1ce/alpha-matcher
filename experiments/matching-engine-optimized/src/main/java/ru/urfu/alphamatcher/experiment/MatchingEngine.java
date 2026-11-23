package ru.urfu.alphamatcher.experiment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

public final class MatchingEngine {
    private final MatchingRepository repository;
    private final PurposeParser parser;

    public MatchingEngine(MatchingRepository repository, PurposeParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    /** Caller supplies a single repeatable-read transaction for a consistent, read-only proposal. */
    public Decision match(Connection connection, long operationId) throws SQLException {
        return measure(connection, operationId).decision();
    }

    public record TimedDecision(Decision decision, long totalNanos, long parsingNanos, long sqlNanos, long rulesNanos) { }

    public TimedDecision measure(Connection connection, long operationId) throws SQLException {
        long start = System.nanoTime();
        if (connection.getAutoCommit()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
            throw new IllegalArgumentException("Нужна транзакция REPEATABLE_READ с autoCommit=false");
        return measureSnapshot(connection, operationId, start);
    }

    private TimedDecision measureSnapshot(Connection connection, long operationId, long start) throws SQLException {
        Operation operation = repository.operation(connection, operationId)
                .orElseThrow(() -> new IllegalArgumentException("Операция не найдена: " + operationId));
        long loaded = System.nanoTime();
        PurposeParser.Features features = parser.parse(operation.purpose());
        long parsed = System.nanoTime();
        CandidateSearch.Result search = operation.linkedContractId() != null || !"LOADED".equals(operation.status())
                ? CandidateSearch.unknown(features,List.of(),"Skipped operation; no search required") : repository.discover(connection, features);
        long searched = System.nanoTime();
        Decision decision = evaluate(operation, features, search);
        long finished = System.nanoTime();
        return new TimedDecision(decision, finished - start, parsed - loaded,
                loaded - start + searched - parsed, finished - searched);
    }

    /** Owns the connection exclusively; no arbitrary borrowed Connection or callback is accepted. */
    public static ReadOnlySession openReadOnlySession() throws SQLException { return openReadOnlySession(null); }

    static ReadOnlySession openReadOnlySession(JdbcProfile probe) throws SQLException {
        Connection raw = ExperimentDatabase.connect();
        try {
            raw.setReadOnly(true);
            validateReadOnlyTransaction(raw);
            return new ReadOnlySession(probe == null ? raw : probe.wrap(raw));
        } catch (SQLException | RuntimeException failure) {
            try { raw.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    static void validateReadOnlyTransaction(Connection connection) throws SQLException {
        if (connection.isClosed() || connection.getAutoCommit() || !connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
            throw new IllegalArgumentException("Нужна открытая read-only REPEATABLE_READ транзакция с autoCommit=false");
        // Verify server state too: JDBC flags can be stale or propagation can be disabled in a URL.
        try (var query = connection.createStatement(); var rs = query.executeQuery(
                "SELECT @@session.transaction_isolation,@@session.transaction_read_only,@@session.autocommit")) {
            if (!rs.next() || !"REPEATABLE-READ".equals(rs.getString(1)) || !rs.getBoolean(2) || rs.getBoolean(3))
                throw new IllegalArgumentException("Сервер не подтвердил read-only REPEATABLE_READ транзакцию");
        }
    }

    /** A single thread and snapshot. Rollback/close end the capability permanently; open a new session to retry. */
    public static final class ReadOnlySession implements AutoCloseable {
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        private final MatchingEngine engine = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser());
        private boolean closed;
        private ReadOnlySession(Connection connection) { this.connection = connection; }
        private void checkOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Сессия принадлежит другому потоку");
        }
        public TimedDecision measure(long operationId) throws SQLException {
            checkOwner();
            if (closed) throw new IllegalStateException("Read-only snapshot уже закрыт");
            try { return engine.measureSnapshot(connection, operationId, System.nanoTime()); }
            catch (SQLException | RuntimeException failure) {
                try { close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }
        public Decision match(long operationId) throws SQLException { return measure(operationId).decision(); }
        long connectionId() throws SQLException {
            checkOwner();
            if (closed) throw new IllegalStateException("Snapshot закрыт");
            try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rs.next(); return rs.getLong(1);
            }
        }
        /** Profiling only, owner thread; snapshot is unchanged. Current SELECT is not yet in the counter. */
        long completedSelects() throws SQLException {
            checkOwner();
            if(closed) throw new IllegalStateException("Snapshot закрыт");
            try(var statement=connection.createStatement();var rs=statement.executeQuery("""
                    SELECT s.COUNT_STAR FROM performance_schema.events_statements_summary_by_thread_by_event_name s
                    JOIN performance_schema.threads t ON t.THREAD_ID=s.THREAD_ID
                    WHERE t.PROCESSLIST_ID=CONNECTION_ID() AND s.EVENT_NAME='statement/sql/select'
                    """)) { if(!rs.next()) throw new SQLException("SELECT instrumentation unavailable");return rs.getLong(1); }
        }
        public void rollback() throws SQLException { close(); }
        @Override public void close() throws SQLException {
            checkOwner();
            if (closed) return;
            closed = true;
            try { connection.rollback(); } finally { connection.close(); }
        }
    }

    /** A bare list is not proof of search completeness. */
    static Decision evaluate(Operation operation,PurposeParser.Features features,List<Candidate> candidates){
        return evaluate(operation,features,CandidateSearch.unknown(features,candidates,"Bare candidate list without coverage evidence"));
    }
    static Decision evaluate(Operation operation, PurposeParser.Features features, CandidateSearch.Result search) {
        List<Candidate> candidates=search.candidates().stream().distinct().toList();
        List<Check> checks = new ArrayList<>();
        boolean eligible = operation.linkedContractId() == null && "LOADED".equals(operation.status());
        checks.add(new Check("OPERATION_ELIGIBLE", eligible, eligible ? "Операция загружена и не привязана"
                : "Операция уже привязана или имеет статус " + operation.status()));
        if (!eligible) return result(operation, Status.SKIPPED, features, search, checks);
        boolean analysisComplete=features.contractAnalysis()==PurposeParser.Completeness.COMPLETE;
        checks.add(new Check("CONTRACT_ANALYSIS_COMPLETE",analysisComplete,"Explicit reference analysis: "+features.contractAnalysis()+(analysisComplete?"":"; "+features.issues().stream().filter(i->i.startsWith("UNSUPPORTED_CONTRACT")||i.startsWith("MATCH_LIMIT")||i.startsWith("TIME_LIMIT")).toList())));
        if(!analysisComplete)return result(operation,Status.REVIEW,features,search,checks);
        // Unresolved name observations are retained and denied by the identity guard below,
        // including the absence-proof branch. They are not silently converted to absent names.
        boolean validInput = operation.activeStatus() && features.invalidInns().isEmpty()
                && features.issues().stream().allMatch(PurposeParser::identityIssue);
        checks.add(new Check("INPUT_VALID", validInput, validInput ? "Извлечённые значения имеют допустимый формат"
                : "Есть некорректные ИНН, неподдерживаемые значения или неактивный статус"));
        if (!validInput) return result(operation, Status.REVIEW, features, search, checks);
        boolean operationPartiesValid = List.of("PAYER", "RECIPIENT").stream()
                .allMatch(role -> operation.parties().containsKey(role) && operation.parties().get(role).consistent());
        checks.add(new Check("OPERATION_PARTIES", operationPartiesValid, operationPartiesValid
                ? "Обе роли операции имеют согласованные корректные ИНН"
                : "Данные участников операции отсутствуют или противоречивы"));
        if (!operationPartiesValid) return result(operation, Status.REVIEW, features, search, checks);
        boolean thirdPartyAbsent = !features.thirdParty();
        checks.add(new Check("NO_THIRD_PARTY", thirdPartyAbsent, thirdPartyAbsent
                ? "Поддерживаемые текстовые признаки платежа за третье лицо не найдены"
                : "Есть признак платежа за третье лицо; требуется ручная проверка"));
        if (!thirdPartyAbsent) return result(operation, Status.REVIEW, features, search, checks);
        if (!features.hasSearchKeys()) {
            checks.add(new Check("SEARCH_KEYS", false, "Недостаточно поддерживаемых идентификаторов для поиска"));
            return result(operation, Status.REVIEW, features, search, checks);
        }
        boolean coverage=CandidateSearch.complete(features,search.evidence());
        checks.add(new Check("CANDIDATE_SEARCH_COMPLETE",coverage,"Search coverage: "+search.evidence()));
        if(!coverage)return result(operation,Status.REVIEW,features,search,checks);
        boolean consistentRows=candidates.stream().map(Candidate::id).distinct().count()==candidates.size();
        checks.add(new Check("CANDIDATE_ROWS_CONSISTENT",consistentRows,"One coherent hydrated record per contract_id"));
        if(!consistentRows)return result(operation,Status.REVIEW,features,search,checks);
        checks.add(new Check("CANDIDATES_FOUND", !candidates.isEmpty(), "Договоров-кандидатов: " + candidates.size()));
        if (candidates.isEmpty()) {
            var operationInns=operation.parties().values().stream().map(Party::effectiveInn).collect(java.util.stream.Collectors.toSet());
            boolean reliable=features.contractNumbers().size()<=1 && (!features.contractNumbers().isEmpty()||!features.inns().isEmpty())
                    && operationInns.containsAll(features.inns()) && namesAgree(features,operation)
                    && organizationsAgree(features,operation) && rolesAgree(features,operation,null);
            checks.add(new Check("ABSENCE_EVIDENCE_COMPLETE",reliable,reliable?"Надёжные ключи не дали договоров":"Отсутствие кандидатов при слабых или противоречивых признаках не доказывает отсутствие договора"));
            return result(operation,reliable?Status.NO_CONTRACT:Status.REVIEW,features,search,checks);
        }
        boolean unique = candidates.size() == 1 && !features.contractReferences().isEmpty()
                && features.acceptsContractNumber(candidates.getFirst().number());
        checks.add(new Check("EXPLICIT_UNIQUE_CONTRACT", unique,
                unique ? "Один явный номер и один договор" : "Нет единственного явно указанного договора"));
        if (!unique) return result(operation, Status.REVIEW, features, search, checks);
        Candidate candidate = candidates.getFirst();
        boolean exactNumber = features.acceptsContractNumber(candidate.number());
        checks.add(new Check("CONTRACT_NUMBER", exactNumber, "Сравнение с наблюдаемыми вариантами границы номера: " + features.contractReferences()));
        var knownInns = new HashSet<String>();
        boolean sameParties = true;
        for (String role : List.of("PAYER", "RECIPIENT")) {
            Party actual = operation.parties().get(role);
            Party expected = candidate.parties().get(role);
            boolean same = sameIdentity(actual, expected);
            checks.add(new Check("PARTY_" + role, same,
                    same ? role + ": ИНН и доступные client_id согласованы"
                            : role + (sameIdentifiers(actual, expected) ? ": подтверждённые ФИО сторон различаются"
                            : ": отсутствуют данные, есть конфликт идентификаторов или ролей")));
            sameParties &= same;
            if (actual != null && actual.effectiveInn() != null) knownInns.add(actual.effectiveInn());
        }
        boolean purposeConsistent = !features.inns().isEmpty() && knownInns.containsAll(features.inns());
        checks.add(new Check("PURPOSE_INN", purposeConsistent, purposeConsistent
                ? "ИНН назначения подтверждены участниками операции, роли проверены отдельно"
                : "В назначении нет корректного ИНН либо он противоречит участникам"));
        boolean namesConsistent = namesAgree(features,operation);
        checks.add(new Check("PURPOSE_NAMES", namesConsistent, namesConsistent
                ? "Извлечённые ФИО не противоречат участникам" : "ФИО не распознано надёжно или относится к другому лицу"));
        boolean organizationsConsistent = organizationsAgree(features,operation);
        checks.add(new Check("PURPOSE_ORGANIZATIONS", organizationsConsistent, organizationsConsistent
                ? "Извлечённые организации не противоречат участникам" : "Организация из назначения не подтверждена участниками"));
        boolean rolesConsistent=rolesAgree(features,operation,candidate);
        checks.add(new Check("ROLE_CLAIMS",rolesConsistent,rolesConsistent?"Явные роли в назначении согласованы":"Реквизит указан в другой роли или роль не подтверждена"));
        Party beneficiary = candidate.parties().get("BENEFICIARY");
        boolean beneficiaryClear = beneficiary == null || sameIdentity(operation.parties().get("PAYER"), beneficiary);
        checks.add(new Check("BENEFICIARY", beneficiaryClear, beneficiaryClear
                ? "Отдельный от плательщика выгодоприобретатель не выявлен"
                : "Выгодоприобретатель отличается от плательщика или не подтверждён; нужна ручная проверка"));
        return result(operation, exactNumber && sameParties && purposeConsistent && beneficiaryClear && namesConsistent && organizationsConsistent && rolesConsistent
                ? Status.SAFE : Status.REVIEW, features, search, checks);
    }

    private static boolean namesAgree(PurposeParser.Features features,Operation operation) {
        var names=operation.parties().values().stream().map(Party::personKey).toList();
        return features.issues().stream().noneMatch(PurposeParser::identityIssue)
                && features.names().stream().allMatch(name->names.stream().anyMatch(known->NameNormalizer.matches(name.surface(),known)));
    }
    private static boolean organizationsAgree(PurposeParser.Features features,Operation operation) {
        return operation.parties().values().stream().map(Party::organizationKey).collect(java.util.stream.Collectors.toSet()).containsAll(features.organizations());
    }
    private static boolean rolesAgree(PurposeParser.Features features,Operation operation,Candidate candidate) {
        return features.roleClaims().stream().allMatch(claim -> {
            Party party=claim.role().equals("BENEFICIARY")?(candidate==null?null:candidate.parties().get(claim.role())):operation.parties().get(claim.role());
            if(party==null) return false;
            return switch(claim.kind()) {
                case "INN" -> party.consistent() && claim.value().equals(party.effectiveInn());
                case "NAME" -> NameNormalizer.matches(claim.value(),party.personKey());
                case "ORGANIZATION" -> claim.value().equals(party.organizationKey());
                default -> false;
            };
        });
    }

    private static boolean sameIdentity(Party actual, Party expected) {
        return sameIdentifiers(actual, expected) && namesDoNotConflict(actual, expected);
    }

    private static boolean sameIdentifiers(Party actual, Party expected) {
        return actual != null && expected != null && actual.consistent() && expected.consistent()
                && actual.effectiveInn().equals(expected.effectiveInn())
                && (actual.clientId() == null || expected.clientId() == null
                    || actual.clientId().equals(expected.clientId()));
    }

    private static boolean namesDoNotConflict(Party actual, Party expected) {
        if (actual.effectiveInn().length() != 12) return true;
        String actualName = actual.personKey();
        String expectedName = expected.personKey();
        return actualName.isEmpty() || expectedName.isEmpty() || actualName.equals(expectedName);
    }

    private static Decision result(Operation operation, Status status, PurposeParser.Features features,
                                   CandidateSearch.Result search, List<Check> checks) {
        var candidates=search.candidates();
        return new Decision(operation.id(), status, status == Status.SAFE ? candidates.getFirst().id() : null,
                candidates.stream().map(Candidate::id).distinct().toList(), features, checks,search.evidence());
    }
}
