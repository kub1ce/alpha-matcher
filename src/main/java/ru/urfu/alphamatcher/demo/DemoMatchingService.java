package ru.urfu.alphamatcher.demo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.demo.DemoDataService.Contract;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;

/** Deterministic demo proposals. A preview never changes confirmed matches or the manual queue. */
@Service
public class DemoMatchingService {
    public enum Status { SAFE, REVIEW, NO_CONTRACT, ALREADY_MANUAL, SKIPPED }
    public record Proposal(String operationId, Status status, String contractId,
                           List<String> candidates, String reason, boolean queued) implements java.io.Serializable { }
    public record Preview(LocalDate from, LocalDate to, boolean includeProcessed, boolean includeManual,
                          long revision, int fingerprint,
                          List<Proposal> rows) implements java.io.Serializable {
        public long count(Status status) { return rows.stream().filter(p -> p.status() == status).count(); }
        public Proposal find(String id) { return rows.stream().filter(p -> p.operationId().equals(id)).findFirst().orElse(null); }
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
        List<Proposal> rows = state.all().stream()
                .filter(op -> !op.timestamp().toLocalDate().isBefore(from) && !op.timestamp().toLocalDate().isAfter(to))
                .filter(op -> includeProcessed || !state.isLinked(op.id()))
                .filter(op -> includeManual || !state.isManualQueued(op.id()))
                .sorted(Comparator.comparing(Operation::timestamp).reversed())
                .map(op -> propose(op, state)).toList();
        return new Preview(from, to, includeProcessed, includeManual, state.revision(),
                fingerprint(state, from, to), rows);
    }

    /** Only state that can affect proposals inside the selected period invalidates a preview. */
    public int fingerprint(DemoOperationState state, LocalDate from, LocalDate to) {
        List<Object> relevant = new ArrayList<>();
        for (Operation op : state.all()) {
            LocalDate date = op.timestamp().toLocalDate();
            if (date.isBefore(from) || date.isAfter(to)) continue;
            relevant.add(op);
            relevant.add(state.match(op.id()));
            relevant.add(state.isManualQueued(op.id()));
            relevant.add(state.manualReason(op.id()));
            relevant.add(state.previousContract(op.id()));
        }
        relevant.add(data.contracts());
        return relevant.hashCode();
    }

    public Proposal propose(Operation op, DemoOperationState state) {
        if (state.isLinked(op.id())) return new Proposal(op.id(), Status.SKIPPED,
                state.match(op.id()).contractId(), List.of(), "Связь уже подтверждена; повторное применение запрещено.", false);
        LinkedHashSet<String> ids = new LinkedHashSet<>(op.candidates());
        if (state.previousContract(op.id()) != null) ids.add(state.previousContract(op.id()));
        List<String> candidates = ids.stream().filter(id -> data.contract(id) != null).toList();
        boolean queued = state.isManualQueued(op.id());
        if (queued) return new Proposal(op.id(), Status.ALREADY_MANUAL, null, candidates,
                "Ручная задача уже создана. Повторная отправка не требуется. "
                        + (state.manualReason(op.id()) != null ? state.manualReason(op.id())
                                : op.reason().isBlank() ? "Проверьте операцию в очереди." : op.reason()), true);
        if (candidates.isEmpty()) return new Proposal(op.id(), Status.NO_CONTRACT, null, candidates,
                op.reason().isBlank() ? "Подходящий договор не указан в демонстрационных реквизитах."
                        : op.reason() + ". Подтверждённого кандидата нет.", queued);
        if (candidates.size() != 1) return new Proposal(op.id(), Status.REVIEW, null, candidates,
                "Несколько кандидатов: требуется осознанный выбор сотрудника. Демонстрационная проверка.", queued);
        Contract contract = data.contract(candidates.getFirst());
        boolean sameParties = contract.payer().equals(op.payer().name())
                && contract.recipient().equals(op.recipient().name());
        DemoDataService.Party payer = data.party(contract.payer());
        DemoDataService.Party recipient = data.party(contract.recipient());
        boolean sameInn = payer != null && recipient != null
                && !op.payer().inn().isBlank() && !op.recipient().inn().isBlank()
                && op.payer().inn().equals(payer.inn()) && op.recipient().inn().equals(recipient.inn());
        boolean explicitContract = op.purpose().contains(contract.id());
        boolean thirdParty = op.purpose().toLowerCase().contains("третье лицо")
                || op.purpose().toLowerCase().contains("за иванова");
        if (op.reason().isBlank() && state.previousContract(op.id()) == null
                && sameParties && sameInn && explicitContract && !thirdParty) {
            return new Proposal(op.id(), Status.SAFE, contract.id(), candidates,
                    "Номер договора указан в назначении, стороны и оба ИНН совпадают; иных кандидатов нет.", false);
        }
        String reason = queued && op.reason().isBlank() ? "Операция уже направлена сотрудником на ручной разбор."
                : !op.reason().isBlank() ? op.reason()
                : !sameParties || !sameInn ? "Стороны или ИНН не совпадают с договором."
                : !explicitContract ? "Номер договора не подтверждён назначением платежа."
                : "Ранее подтверждённая связь была отвязана; требуется ручная проверка.";
        if (thirdParty) reason += " Проверьте выгодоприобретателя и основание платежа.";
        return new Proposal(op.id(), Status.REVIEW, null, candidates, reason + " Демонстрационная проверка.", queued);
    }

    public boolean loadTestBatch(DemoOperationState state) {
        synchronized (state) {
            List<Operation> batch = data.matchingBatch();
            if (batch.stream().anyMatch(op -> state.operation(op.id()) != null)) return false;
            return state.addOperations(batch);
        }
    }

    public BatchResult apply(DemoOperationState state, Preview preview, Set<String> selected, String initiator) {
        return perform(state, preview, selected, initiator, false);
    }
    public BatchResult sendToManual(DemoOperationState state, Preview preview, Set<String> selected, String initiator) {
        return perform(state, preview, selected, initiator, true);
    }

    private BatchResult perform(DemoOperationState state, Preview preview, Set<String> selected,
                                String initiator, boolean manual) {
        synchronized (state) {
            DemoPermissions.requireCurrentIfPresent(manual
                    ? DemoPermissions.Action.QUEUE_MANUAL : DemoPermissions.Action.APPLY_SAFE);
            initiator = DemoPermissions.currentActorOr(initiator);
            if (preview == null || preview.fingerprint() != fingerprint(state, preview.from(), preview.to()))
                throw new IllegalStateException("Данные изменились после предпросмотра. Пересчитайте предложения.");
            Set<String> ids = new LinkedHashSet<>(Objects.requireNonNull(selected));
            if (ids.isEmpty()) throw new IllegalArgumentException("Выберите хотя бы одну операцию");
            List<Proposal> actionable = new ArrayList<>();
            for (String id : ids) {
                Proposal old = preview.find(id);
                if (old == null) throw new IllegalArgumentException("Выбрана операция вне текущего предпросмотра");
                Operation op = state.operation(id);
                if (op == null || !old.equals(propose(op, state)))
                    throw new IllegalStateException("Предложение устарело. Пересчитайте предпросмотр.");
                if (manual ? !state.isLinked(id) && !state.isManualQueued(id) : old.status() == Status.SAFE)
                    actionable.add(old);
            }
            if (actionable.isEmpty()) throw new IllegalArgumentException(manual
                    ? "Все выбранные операции уже обработаны или находятся в очереди"
                    : "Среди выбранных нет безопасных предложений для привязки");
            // All candidates and state are validated before the first mutation.
            for (Proposal p : actionable) if (!manual) {
                Contract contract = data.contract(p.contractId());
                Operation op = state.operation(p.operationId());
                if (contract == null || !contract.payer().equals(op.payer().name())
                        || !contract.recipient().equals(op.recipient().name()))
                    throw new IllegalStateException("Предложение устарело. Пересчитайте предпросмотр.");
            }
            String runId = state.nextRunId();
            for (Proposal p : actionable) {
                if (manual) {
                    Operation op = state.operation(p.operationId());
                    state.queueForManual(p.operationId(), op.reason().isBlank()
                            ? "Передано сотрудником из предпросмотра" : op.reason(), initiator, runId);
                } else state.link(p.operationId(), p.contractId(), "Автоматически", data.contracts(),
                        p.reason(), initiator, runId);
            }
            int noContract = (int) ids.stream().map(preview::find)
                    .filter(p -> p.status() == Status.NO_CONTRACT).count();
            int alreadyQueued = (int) ids.stream().map(preview::find)
                    .filter(p -> p.status() == Status.ALREADY_MANUAL).count();
            int alreadyProcessed = (int) ids.stream().map(preview::find)
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
