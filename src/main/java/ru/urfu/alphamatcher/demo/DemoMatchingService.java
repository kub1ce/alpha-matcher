package ru.urfu.alphamatcher.demo;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.demo.DemoDataService.Contract;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;

/** Engine proposals and employee confirmations. A preview never changes confirmed matches or the manual queue. */
@Service
public class DemoMatchingService {
    public enum Status { SAFE, REVIEW, NO_CONTRACT, ALREADY_MANUAL, SKIPPED }
    public record Proposal(String operationId, Status status, String contractId,
                           List<String> candidates, String reason, boolean queued,
                           ru.urfu.alphamatcher.experiment.MatchingModel.Decision decision) implements java.io.Serializable {
        public Proposal(String operationId, Status status, String contractId, List<String> candidates, String reason, boolean queued) {
            this(operationId, status, contractId, candidates, reason, queued, null);
        }
    }
    public record Preview(LocalDate from, LocalDate to, boolean includeProcessed, boolean includeManual,
                          long revision, int fingerprint,
                          List<Proposal> rows, String sourceVersion) implements java.io.Serializable {
        public Preview { rows = List.copyOf(rows); }
        public long count(Status status) { return rows.stream().filter(p -> p.status() == status).count(); }
        public Proposal find(String id) { return rows.stream().filter(p -> p.operationId().equals(id)).findFirst().orElse(null); }
        public java.util.stream.Stream<Proposal> selected(Set<String> ids) {
            return rows.stream().filter(row -> ids.contains(row.operationId()));
        }
    }
    public record BatchResult(int selected, int applied, int sentManual, int skipped, String runId) { }

    private final DemoDataService data;
    public DemoMatchingService(DemoDataService data) { this.data = data; }

    public Preview preview(DemoOperationState state, LocalDate from, LocalDate to, boolean includeProcessed) {
        return preview(state, from, to, includeProcessed, true);
    }

    public Preview preview(DemoOperationState state, LocalDate from, LocalDate to,
                           boolean includeProcessed, boolean includeManual) {
        if (from == null || to == null || from.isAfter(to))
            throw new IllegalArgumentException("Укажите корректный период: начало не позже окончания");
        if (!data.hasMatchingSource())
            throw new IllegalStateException("Полный анализ доступен в профиле jdbc-demo с изолированной MySQL.");
        return data.readMatchingSnapshot(() -> previewInSnapshot(state, from, to, includeProcessed, includeManual));
    }

    private Preview previewInSnapshot(DemoOperationState state, LocalDate from, LocalDate to,
                                      boolean includeProcessed, boolean includeManual) {
        List<Operation> scope = state.operationsInPeriod(from, to);
        var observer = new PeriodFingerprint(state);
        scope.forEach(observer::add);
        var before = observer.finish(data.contracts());
        List<Proposal> rows = scope.stream()
                .filter(op -> includeProcessed || !state.isLinked(op.id()) && "LOADED".equals(data.operationStatus(op.id())))
                .filter(op -> includeManual || !state.isManualQueued(op.id()))
                .sorted(Comparator.comparing(Operation::timestamp).reversed())
                .map(op -> propose(op, state)).toList();
        synchronized (state) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Поиск отменён");
            if (!before.equals(periodVersion(state, from, to)))
                throw new IllegalStateException("Данные изменились во время анализа. Повторите поиск.");
            for (Proposal row : rows) if (row.decision() != null) { state.analysis(row.operationId(), row.decision()); state.proposal(row); }
            return new Preview(from, to, includeProcessed, includeManual, state.revision(),
                    before.fingerprint(), rows, before.sourceVersion());
        }
    }

    /** Only state that can affect proposals inside the selected period invalidates a preview. */
    public int fingerprint(DemoOperationState state, LocalDate from, LocalDate to) {
        return periodVersion(state, from, to).fingerprint();
    }

    private PeriodVersion periodVersion(DemoOperationState state, LocalDate from, LocalDate to) {
        var observer = new PeriodFingerprint(state);
        state.visitOperationsInPeriod(from, to, observer::add);
        return observer.finish(data.contracts());
    }

    private record PeriodVersion(int fingerprint, String sourceVersion) { }

    private static final class PeriodFingerprint {
        private final DemoOperationState state;
        private final MessageDigest digest;
        private int hash = 1;

        private PeriodFingerprint(DemoOperationState state) {
            this.state = state;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }

        private void add(Operation operation) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Поиск отменён");
            String id = operation.id();
            // Same fold as the previous List.hashCode, without retaining the input rows or employee facts.
            hash = 31 * hash + operation.hashCode();
            hash = 31 * hash + Objects.hashCode(state.match(id));
            hash = 31 * hash + Boolean.hashCode(state.isManualQueued(id));
            hash = 31 * hash + Objects.hashCode(state.manualReason(id));
            hash = 31 * hash + Objects.hashCode(state.previousContract(id));
            digest.update(operation.sourceVersion().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
        }

        private PeriodVersion finish(List<Contract> contracts) {
            return new PeriodVersion(31 * hash + contracts.hashCode(),
                    java.util.HexFormat.of().formatHex(digest.digest()));
        }
    }

    public Proposal propose(Operation op, DemoOperationState state) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Поиск отменён");
        var decision = data.analyze(op);
        if (state.isLinked(op.id())) return new Proposal(op.id(), Status.SKIPPED,
                state.match(op.id()).contractId(), List.of(), "Связь уже подтверждена; повторное применение запрещено.", false, decision);
        List<String> candidates = decision.candidateIds().stream().map(data::contractReference).toList();
        boolean queued = state.isManualQueued(op.id());
        Status status = queued ? Status.ALREADY_MANUAL : Status.valueOf(decision.status().name());
        String reason = decision.checks().stream().filter(check -> !check.passed())
                .map(check -> check.code() + ": " + check.explanation())
                .collect(java.util.stream.Collectors.joining("; "));
        if (reason.isBlank()) reason = decision.checks().stream().map(check -> check.explanation())
                .collect(java.util.stream.Collectors.joining("; "));
        if (queued) reason = "Ручная задача уже создана. " + reason;
        Proposal proposal = new Proposal(op.id(), status, decision.proposedContractId() == null ? null
                : data.contractReference(decision.proposedContractId()), candidates, reason, queued, decision);
        return proposal;
    }

    public boolean loadTestBatch(DemoOperationState state) {
        synchronized (state) {
            List<Operation> batch = data.matchingBatch();
            if (batch.stream().anyMatch(op -> state.operation(op.id()) != null)) return false;
            return state.addOperations(batch);
        }
    }

    public BatchResult apply(DemoOperationState state, Preview preview, Set<String> selected, String initiator) {
        return data.confirmAtomically(state, () -> perform(state, preview, selected, initiator, false));
    }
    public BatchResult sendToManual(DemoOperationState state, Preview preview, Set<String> selected, String initiator) {
        return data.confirmAtomically(state, () -> perform(state, preview, selected, initiator, true));
    }

    private BatchResult perform(DemoOperationState state, Preview preview, Set<String> selected,
                                String initiator, boolean manual) {
        synchronized (state) {
            DemoPermissions.requireCurrentIfPresent(manual
                    ? DemoPermissions.Action.QUEUE_MANUAL : DemoPermissions.Action.APPLY_SAFE);
            initiator = DemoPermissions.currentActorOr(initiator);
            if (preview == null) throw new IllegalStateException("Сначала сформируйте предпросмотр.");
            var current = periodVersion(state, preview.from(), preview.to());
            if (preview.fingerprint() != current.fingerprint() || !preview.sourceVersion().equals(current.sourceVersion()))
                throw new IllegalStateException("Данные изменились после предпросмотра. Пересчитайте предложения.");
            Set<String> ids = new LinkedHashSet<>(Objects.requireNonNull(selected));
            if (ids.isEmpty()) throw new IllegalArgumentException("Выберите хотя бы одну операцию");
            var byId = new java.util.HashMap<String,Proposal>();
            preview.selected(ids).forEach(row -> byId.put(row.operationId(),row));
            List<Proposal> actionable = new ArrayList<>();
            for (String id : ids) {
                Proposal old = byId.get(id);
                if (old == null) throw new IllegalArgumentException("Выбрана операция вне текущего предпросмотра");
                Operation op = state.operation(id);
                if (op == null || !old.equals(propose(op, state)))
                    throw new IllegalStateException("Предложение устарело. Пересчитайте предпросмотр.");
                if (manual ? old.status() != Status.SKIPPED && !state.isLinked(id) && !state.isManualQueued(id) : old.status() == Status.SAFE)
                    actionable.add(old);
            }
            if (actionable.isEmpty()) throw new IllegalArgumentException(manual
                    ? "Все выбранные операции уже обработаны или находятся в очереди"
                    : "Среди выбранных нет безопасных предложений для привязки");
            // All candidates and state are validated before the first mutation.
            for (Proposal p : actionable) if (!manual) {
                Contract contract = data.contract(p.contractId());
                Operation op = state.operation(p.operationId());
                if (contract == null || op == null)
                    throw new IllegalStateException("Предложение устарело. Пересчитайте предпросмотр.");
            }
            String runId = state.nextRunId();
            for (Proposal p : actionable) {
                if (manual) {
                    Operation op = state.operation(p.operationId());
                    state.queueForManual(p.operationId(), p.reason().isBlank()
                            ? "Передано сотрудником из предпросмотра" : p.reason(), initiator, runId);
                } else state.link(p.operationId(), p.contractId(), "Автоматически", data.contracts(),
                        p.reason(), initiator, runId);
            }
            int noContract = (int) byId.values().stream()
                    .filter(p -> p.status() == Status.NO_CONTRACT).count();
            int alreadyQueued = (int) byId.values().stream()
                    .filter(p -> p.status() == Status.ALREADY_MANUAL).count();
            int alreadyProcessed = (int) byId.values().stream()
                    .filter(p -> p.status() == Status.SKIPPED).count();
            state.recordRun(new DemoOperationState.BatchRun(runId, DemoOperationState.now(), initiator,
                    preview.from(), preview.to(), preview.rows().size(), ids.size(), actionable.size(),
                    manual ? 0 : actionable.size(), manual ? actionable.size() : 0,
                    ids.size() - actionable.size(), noContract, alreadyQueued, alreadyProcessed,
                    manual ? "Передача на ручной разбор" : "Пакетная автопривязка",
                    actionable.stream().map(Proposal::operationId).toList(),
                    preview.includeProcessed(), preview.includeManual()));
            return new BatchResult(ids.size(), manual ? 0 : actionable.size(), manual ? actionable.size() : 0,
                    ids.size() - actionable.size(), runId);
        }
    }
}
