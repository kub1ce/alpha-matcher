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
        Operation operation = repository.operation(connection, operationId)
                .orElseThrow(() -> new IllegalArgumentException("Операция не найдена: " + operationId));
        long loaded = System.nanoTime();
        PurposeParser.Features features = operation.purpose() != null && operation.purpose().length() > PurposeParser.MAX_LENGTH
                ? new PurposeParser.Features("", List.of(), List.of(), List.of(), false, List.of("Назначение превышает поддерживаемые 4000 символов"))
                : parser.parse(operation.purpose());
        long parsed = System.nanoTime();
        List<Candidate> candidates = operation.linkedContractId() != null || !"LOADED".equals(operation.status())
                ? List.of() : repository.candidates(connection, features);
        long searched = System.nanoTime();
        Decision decision = evaluate(operation, features, candidates);
        long finished = System.nanoTime();
        return new TimedDecision(decision, finished - start, parsed - loaded,
                loaded - start + searched - parsed, finished - searched);
    }

    static Decision evaluate(Operation operation, PurposeParser.Features features, List<Candidate> candidates) {
        List<Check> checks = new ArrayList<>();
        boolean eligible = operation.linkedContractId() == null && "LOADED".equals(operation.status());
        checks.add(new Check("OPERATION_ELIGIBLE", eligible, eligible ? "Операция загружена и не привязана"
                : "Операция уже привязана или имеет статус " + operation.status()));
        if (!eligible) return result(operation, Status.SKIPPED, features, candidates, checks);
        boolean validInput = operation.activeStatus() && features.invalidInns().isEmpty() && features.issues().isEmpty();
        checks.add(new Check("INPUT_VALID", validInput, validInput ? "Извлечённые значения имеют допустимый формат"
                : "Есть некорректные ИНН, неподдерживаемые значения или неактивный статус"));
        if (!validInput) return result(operation, Status.REVIEW, features, candidates, checks);
        boolean operationPartiesValid = List.of("PAYER", "RECIPIENT").stream()
                .allMatch(role -> operation.parties().containsKey(role) && operation.parties().get(role).consistent());
        checks.add(new Check("OPERATION_PARTIES", operationPartiesValid, operationPartiesValid
                ? "Обе роли операции имеют согласованные корректные ИНН"
                : "Данные участников операции отсутствуют или противоречивы"));
        if (!operationPartiesValid) return result(operation, Status.REVIEW, features, candidates, checks);
        boolean thirdPartyAbsent = !features.thirdParty();
        checks.add(new Check("NO_THIRD_PARTY", thirdPartyAbsent, thirdPartyAbsent
                ? "Поддерживаемые текстовые признаки платежа за третье лицо не найдены"
                : "Есть признак платежа за третье лицо; требуется ручная проверка"));
        if (!thirdPartyAbsent) return result(operation, Status.REVIEW, features, candidates, checks);
        if (!features.hasSearchKeys()) {
            checks.add(new Check("SEARCH_KEYS", false, "Недостаточно поддерживаемых идентификаторов для поиска"));
            return result(operation, Status.REVIEW, features, candidates, checks);
        }
        checks.add(new Check("CANDIDATES_FOUND", !candidates.isEmpty(), "Договоров-кандидатов: " + candidates.size()));
        if (candidates.isEmpty()) return result(operation, Status.NO_CONTRACT, features, candidates, checks);
        boolean unique = candidates.size() == 1 && features.contractNumbers().size() == 1;
        checks.add(new Check("EXPLICIT_UNIQUE_CONTRACT", unique,
                unique ? "Один явный номер и один договор" : "Нет единственного явно указанного договора"));
        if (!unique) return result(operation, Status.REVIEW, features, candidates, checks);
        Candidate candidate = candidates.getFirst();
        boolean exactNumber = features.contractNumbers().getFirst().equals(PurposeParser.number(candidate.number()));
        checks.add(new Check("CONTRACT_NUMBER", exactNumber, "Сравнение нормализованных номеров без подмены букв"));
        var knownInns = new HashSet<String>();
        boolean sameParties = true;
        for (String role : List.of("PAYER", "RECIPIENT")) {
            Party actual = operation.parties().get(role);
            Party expected = candidate.parties().get(role);
            boolean same = sameIdentity(actual, expected);
            checks.add(new Check("PARTY_" + role, same,
                    same ? role + ": ИНН и доступные client_id согласованы"
                            : role + ": отсутствуют данные, есть конфликт идентификаторов или ролей"));
            sameParties &= same;
            if (actual != null && actual.effectiveInn() != null) knownInns.add(actual.effectiveInn());
        }
        boolean purposeConsistent = !features.inns().isEmpty() && knownInns.containsAll(features.inns());
        checks.add(new Check("PURPOSE_INN", purposeConsistent, purposeConsistent
                ? "ИНН назначения подтверждены участниками операции, роли проверены отдельно"
                : "В назначении нет корректного ИНН либо он противоречит участникам"));
        var knownNames = operation.parties().values().stream().map(Party::personKey).collect(java.util.stream.Collectors.toSet());
        boolean namesConsistent = features.names().stream().allMatch(name -> name.recognized() && knownNames.contains(name.key()));
        checks.add(new Check("PURPOSE_NAMES", namesConsistent, namesConsistent
                ? "Извлечённые ФИО не противоречат участникам" : "ФИО не распознано надёжно или относится к другому лицу"));
        var knownOrganizations = operation.parties().values().stream().map(Party::organizationKey).collect(java.util.stream.Collectors.toSet());
        boolean organizationsConsistent = knownOrganizations.containsAll(features.organizations());
        checks.add(new Check("PURPOSE_ORGANIZATIONS", organizationsConsistent, organizationsConsistent
                ? "Извлечённые организации не противоречат участникам" : "Организация из назначения не подтверждена участниками"));
        Party beneficiary = candidate.parties().get("BENEFICIARY");
        boolean beneficiaryClear = beneficiary == null || sameIdentity(operation.parties().get("PAYER"), beneficiary);
        checks.add(new Check("BENEFICIARY", beneficiaryClear, beneficiaryClear
                ? "Отдельный от плательщика выгодоприобретатель не выявлен"
                : "Выгодоприобретатель отличается от плательщика или не подтверждён; нужна ручная проверка"));
        return result(operation, exactNumber && sameParties && purposeConsistent && beneficiaryClear && namesConsistent && organizationsConsistent
                ? Status.SAFE : Status.REVIEW, features, candidates, checks);
    }

    private static boolean sameIdentity(Party actual, Party expected) {
        return actual != null && expected != null && actual.consistent() && expected.consistent()
                && actual.effectiveInn().equals(expected.effectiveInn())
                && (actual.clientId() == null || expected.clientId() == null
                    || actual.clientId().equals(expected.clientId()));
    }

    private static Decision result(Operation operation, Status status, PurposeParser.Features features,
                                   List<Candidate> candidates, List<Check> checks) {
        return new Decision(operation.id(), status, status == Status.SAFE ? candidates.getFirst().id() : null,
                candidates.stream().map(Candidate::id).toList(), features, checks);
    }
}
