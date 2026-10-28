package ru.urfu.alphamatcher.demo;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import com.vaadin.flow.server.VaadinSession;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;

/** Mutable, isolated demo state. One instance is kept in each Vaadin user session. */
public class DemoOperationState implements Serializable {
    public record Match(String contractId, String method, String justification, String automaticReason,
                        String actor, LocalDateTime decisionAt, String reviewReason) implements Serializable { }
    public record Query(String text, LocalDate from, LocalDate to, String status, String method,
                        String sort, boolean descending) { }
    public record Page(List<Operation> rows, int total, int page, int pages) { }
    public record BatchRun(String id, LocalDateTime time, String initiator,
                           LocalDate from, LocalDate to, int considered, int selected, int eligible,
                           int applied, int manual, int skipped, int withoutContract,
                           int alreadyQueued, int alreadyProcessed, String type,
                           List<String> operationIds, boolean includeProcessed,
                           boolean includeManual) implements Serializable { }
    public record AuditEvent(String id, LocalDateTime time, String operationId, String action,
                             String previous, String current, String actor, String method,
                             String runId, String justification, String automaticReason, String reviewReason,
                             String previousJustification, String previousState) implements Serializable { }
    private static final ZoneId DEMO_ZONE = ZoneId.of("Asia/Yekaterinburg");
    private final List<Operation> operations;
    private final Map<String, Match> matches = new HashMap<>();
    private final Map<String, String> previousContracts = new HashMap<>();
    private final Set<String> manualQueue = new LinkedHashSet<>();
    private final Map<String, String> manualReasons = new HashMap<>();
    private final List<BatchRun> batchRuns = new ArrayList<>();
    private final List<AuditEvent> auditEvents = new ArrayList<>();
    private long nextRunNumber;
    private boolean idsNormalized;
    private long revision;

    public DemoOperationState(List<Operation> operations) {
        this.operations = new ArrayList<>(operations);
        for (Operation operation : operations) seedState(operation);
    }

    private void seedState(Operation operation) {
        if (!operation.initialContract().isBlank()) matches.put(operation.id(),
                new Match(operation.initialContract(), operation.initialMethod(), operation.initialJustification(),
                        "", operation.initialActor(), operation.initialDecisionAt(), operation.reason()));
        else if (!operation.reason().isBlank()) manualQueue.add(operation.id());
    }

    /** Extend an existing session without replacing its confirmed links, queue, runs or audit. */
    public void mergeSeedOperations(List<Operation> seed) {
        Set<String> present = new LinkedHashSet<>();
        for (Operation operation : operations) present.add(operation.id());
        for (Operation operation : seed) if (present.add(operation.id())) {
            operations.add(operation);
            seedState(operation);
            revision++;
        }
    }

    public List<Operation> all() { return List.copyOf(operations); }
    public long revision() { return revision; }
    public boolean isManualQueued(String id) { return manualQueue.contains(id) && !isLinked(id); }
    public String manualReason(String id) { return manualReasons.get(id); }
    public List<BatchRun> batchRuns() { normalizeLegacyIds(); return List.copyOf(batchRuns); }
    public List<AuditEvent> auditEvents() { normalizeLegacyIds(); return List.copyOf(auditEvents); }
    public List<AuditEvent> auditEventsForOperation(String operationId) {
        if (operationId == null || operation(operationId) == null) return List.of();
        return auditEvents().stream().filter(event -> operationId.equals(event.operationId())).toList();
    }
    public String nextRunId() { normalizeLegacyIds(); return "RUN-%03d".formatted(++nextRunNumber); }
    public void recordRun(BatchRun run) {
        normalizeLegacyIds();
        if (batchRuns.stream().anyMatch(existing -> existing.id().equals(run.id())))
            throw new IllegalArgumentException("Запуск с таким ID уже существует");
        batchRuns.add(run);
        if (run.id().startsWith("RUN-DEMO-")) idsNormalized = false;
    }
    /** Upgrade IDs in an existing session, preserving canonical IDs and audit references. */
    private void normalizeLegacyIds() {
        if (idsNormalized) return;
        Map<String, String> runIds = new HashMap<>();
        Set<String> usedRuns = new LinkedHashSet<>();
        long runMaximum = nextRunNumber;
        for (BatchRun run : batchRuns) if (run.id().matches("RUN-\\d+")) {
            usedRuns.add(run.id());
            runMaximum = Math.max(runMaximum, Long.parseLong(run.id().substring(4)));
        }
        for (int index = 0; index < batchRuns.size(); index++) {
            BatchRun run = batchRuns.get(index);
            if (!run.id().startsWith("RUN-DEMO-")) continue;
            String desired = "RUN-" + run.id().substring("RUN-DEMO-".length());
            String replacement = desired;
            while (!usedRuns.add(replacement)) replacement = "RUN-%03d".formatted(++runMaximum);
            runMaximum = Math.max(runMaximum, Long.parseLong(replacement.substring(4)));
            runIds.put(run.id(), replacement);
            batchRuns.set(index, new BatchRun(replacement, run.time(), run.initiator(), run.from(), run.to(),
                    run.considered(), run.selected(), run.eligible(), run.applied(), run.manual(),
                    run.skipped(), run.withoutContract(), run.alreadyQueued(), run.alreadyProcessed(),
                    run.type(), run.operationIds(), run.includeProcessed(), run.includeManual()));
        }
        nextRunNumber = runMaximum;

        Set<String> usedAudits = new LinkedHashSet<>();
        long auditMaximum = 0;
        for (AuditEvent event : auditEvents) if (event.id().matches("AUD-\\d+")) {
            usedAudits.add(event.id());
            auditMaximum = Math.max(auditMaximum, Long.parseLong(event.id().substring(4)));
        }
        for (int index = 0; index < auditEvents.size(); index++) {
            AuditEvent event = auditEvents.get(index);
            String replacement = event.id();
            if (replacement.startsWith("AUD-DEMO-")) {
                replacement = "AUD-" + replacement.substring("AUD-DEMO-".length());
                while (!usedAudits.add(replacement)) replacement = "AUD-%04d".formatted(++auditMaximum);
                auditMaximum = Math.max(auditMaximum, Long.parseLong(replacement.substring(4)));
            }
            String linkedRun = runIds.getOrDefault(event.runId(), event.runId());
            if (!replacement.equals(event.id()) || !Objects.equals(linkedRun, event.runId()))
                auditEvents.set(index, new AuditEvent(replacement, event.time(), event.operationId(),
                        event.action(), event.previous(), event.current(), event.actor(), event.method(),
                        linkedRun, event.justification(), event.automaticReason(), event.reviewReason(), event.previousJustification(),
                        event.previousState()));
        }
        idsNormalized = true;
    }
    public static LocalDateTime now() { return LocalDateTime.now(DEMO_ZONE); }
    private static String currentActor() {
        return VaadinSession.getCurrent() == null || DemoSession.user() == null
                ? "Сотрудник" : DemoSession.user().fullName();
    }
    private void audit(String operationId, String action, String previous, String current,
                       String actor, String method, String runId, String justification, String automaticReason,
                       String reviewReason, String previousJustification, String previousState) {
        normalizeLegacyIds();
        long nextAuditNumber = auditEvents.stream().map(AuditEvent::id)
                .filter(id -> id.matches("AUD-\\d+"))
                .mapToLong(id -> Long.parseLong(id.substring(4))).max().orElse(0) + 1;
        auditEvents.add(new AuditEvent("AUD-%04d".formatted(nextAuditNumber),
                now(), operationId, action, previous, current, actor, method, runId,
                justification, automaticReason, reviewReason, previousJustification, previousState));
    }
    public boolean addOperations(List<Operation> batch) {
        Set<String> ids = new LinkedHashSet<>();
        for (Operation operation : batch) {
            if (operation(operation.id()) != null || !ids.add(operation.id())) return false;
        }
        operations.addAll(batch);
        if (!batch.isEmpty()) revision++;
        return true;
    }
    public boolean queueForManual(String id) {
        return queueForManual(id, "Передано сотрудником из предпросмотра");
    }
    public boolean queueForManual(String id, String reason) {
        return queueForManual(id, reason, currentActor(), null);
    }
    public boolean queueForManual(String id, String reason, String actor, String runId) {
        DemoPermissions.requireCurrentIfPresent(DemoPermissions.Action.QUEUE_MANUAL);
        actor = DemoPermissions.currentActorOr(actor);
        if (operation(id) == null) throw new IllegalArgumentException("Неизвестная операция");
        if (isLinked(id)) throw new IllegalStateException("Обработанную операцию нельзя передать в очередь");
        boolean added = manualQueue.add(id);
        if (added) {
            manualReasons.put(id, reason);
            revision++;
            audit(id, "Постановка на ручной разбор", "Без привязки", "Ручной разбор",
                    actor, "Вручную", runId, "", "", reason, "", "Не обработана");
        }
        return added;
    }
    public Operation operation(String id) {
        return operations.stream().filter(op -> op.id().equals(id)).findFirst().orElse(null);
    }
    public Match match(String id) { return matches.get(id); }
    public String previousContract(String id) { return previousContracts.get(id); }
    public boolean isLinked(String id) { return matches.containsKey(id); }
    public int linkedCount() { return matches.size(); }
    /** One validation rule for every confirmed manual decision, including direct state calls. */
    public static String validatedManualJustification(String value) {
        if (value == null) throw new IllegalArgumentException("Укажите краткое обоснование решения (не менее 5 символов)");
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.codePointAt(start)))
            start += Character.charCount(value.codePointAt(start));
        while (end > start && isWhitespace(value.codePointBefore(end)))
            end -= Character.charCount(value.codePointBefore(end));
        String normalized = value.substring(start, end);
        if (normalized.codePointCount(0, normalized.length()) < 5)
            throw new IllegalArgumentException("Укажите краткое обоснование решения (не менее 5 символов)");
        return normalized;
    }
    public static boolean hasValidManualJustification(String value) {
        try { validatedManualJustification(value); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }
    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT || Character.isISOControl(codePoint);
    }
    public void link(String operationId, String contractId, String method, List<DemoDataService.Contract> contracts) {
        link(operationId, contractId, method, contracts, "");
    }
    public void link(String operationId, String contractId, String method,
                     List<DemoDataService.Contract> contracts, String justification) {
        link(operationId, contractId, method, contracts, justification, currentActor(), null);
    }
    public void link(String operationId, String contractId, String method,
                     List<DemoDataService.Contract> contracts, String justification,
                     String actor, String runId) {
        DemoPermissions.requireCurrentIfPresent(matches.containsKey(operationId)
                ? DemoPermissions.Action.CHANGE_LINK : "Автоматически".equals(method)
                ? DemoPermissions.Action.APPLY_SAFE : DemoPermissions.Action.MANUAL_CLEAR);
        actor = DemoPermissions.currentActorOr(actor);
        Operation operation = operation(operationId);
        if (operation == null) throw new IllegalArgumentException("Неизвестная операция");
        DemoDataService.Contract contract = contracts.stream().filter(c -> c.id().equals(contractId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Неизвестный договор"));
        if (!List.of("Автоматически", "Вручную").contains(method))
            throw new IllegalArgumentException("Неизвестный способ");
        String explanation = "Вручную".equals(method) ? validatedManualJustification(justification) : "";
        String automaticReason = "";
        if ("Автоматически".equals(method)) {
            // The same proposal calculation drives the preview and guards the final state mutation.
            DemoDataService reference = new DemoDataService();
            var proposal = new DemoMatchingService(reference).propose(operation, this);
            if (proposal.status() != DemoMatchingService.Status.SAFE
                    || !contractId.equals(proposal.contractId())
                    || !contract.equals(reference.contract(contractId)))
                throw new IllegalStateException("Автоматическая привязка недоступна: предложение не является безопасным");
            if (justification != null && !justification.isBlank() && !justification.equals(proposal.reason()))
                throw new IllegalStateException("Основание автоматического предложения изменилось");
            automaticReason = proposal.reason();
        }
        boolean partiesDiffer = !contract.payer().equals(operation.payer().name())
                || !contract.recipient().equals(operation.recipient().name());
        if ("Вручную".equals(method) && !matches.containsKey(operationId))
            DemoPermissions.requireCurrentIfPresent(DemoPermissions.manualAction(operation, contract, this));
        if (partiesDiffer && !"Вручную".equals(method))
            throw new IllegalArgumentException("Стороны договора не совпадают со сторонами операции");
        Match previous = matches.get(operationId);
        if (previous != null && previous.contractId().equals(contractId)
                && previous.method().equals(method) && previous.justification().equals(explanation)
                && previous.automaticReason().equals(automaticReason)) return;
        boolean queued = isManualQueued(operationId);
        String reviewReason = manualReasons.getOrDefault(operationId, operation.reason());
        LocalDateTime decisionAt = now();
        Match next = new Match(contractId, method, explanation, automaticReason, actor, decisionAt,
                reviewReason == null ? "" : reviewReason);
        matches.put(operationId, next);
        previousContracts.remove(operationId);
        manualQueue.remove(operationId);
        manualReasons.remove(operationId);
        revision++;
        audit(operationId, previous == null
                        ? ("Автоматически".equals(method) ? "Автоматическая привязка" : "Ручная привязка")
                        : "Изменение договора",
                previous == null ? (queued ? "Нет договора (ручной разбор)" : "Нет договора")
                        : previous.contractId(), contractId, actor, method, runId,
                explanation, automaticReason, next.reviewReason(),
                previous == null ? "" : previous.justification(),
                previous == null ? (queued ? "Ожидала ручного разбора" : "Не обработана")
                        : "Обработана (" + previous.method() + ")");
    }
    public void unlink(String operationId) {
        unlink(operationId, currentActor());
    }
    public void unlink(String operationId, String actor) {
        DemoPermissions.requireCurrentIfPresent(DemoPermissions.Action.UNLINK);
        actor = DemoPermissions.currentActorOr(actor);
        if (operation(operationId) == null) throw new IllegalArgumentException("Неизвестная операция");
        Match removed = matches.remove(operationId);
        if (removed != null) {
            previousContracts.put(operationId, removed.contractId());
            manualQueue.add(operationId);
            manualReasons.put(operationId, "Договор отвязан сотрудником");
            revision++;
            audit(operationId, "Отвязка договора", removed.contractId(),
                    "Нет договора (ручной разбор)", actor, "Вручную", null,
                    "", "", removed.reviewReason(), removed.justification(),
                    "Обработана (" + removed.method() + ")");
        }
    }

    public Page search(Query query, int requestedPage, int pageSize) {
        if (pageSize < 1) throw new IllegalArgumentException("Размер страницы должен быть положительным");
        String needle = query.text() == null ? "" : query.text().trim().toLowerCase(Locale.ROOT);
        List<Operation> filtered = new ArrayList<>();
        for (Operation op : operations) {
            Match match = match(op.id());
            String searchable = String.join(" ", op.id(), op.payer().name(), op.payer().inn(),
                    op.recipient().name(), op.recipient().inn(), op.purpose()).toLowerCase(Locale.ROOT);
            if (!searchable.contains(needle)) continue;
            LocalDate date = op.timestamp().toLocalDate();
            if (query.from() != null && date.isBefore(query.from())) continue;
            if (query.to() != null && date.isAfter(query.to())) continue;
            if ("Обработана".equals(query.status()) && match == null) continue;
            if ("Не обработана".equals(query.status()) && match != null) continue;
            if (query.method() != null && !query.method().isBlank()
                    && (match == null || !query.method().equals(match.method()))) continue;
            filtered.add(op);
        }
        Comparator<Operation> comparator = switch (Objects.toString(query.sort(), "date")) {
            case "id" -> Comparator.comparing(Operation::id);
            case "amount" -> Comparator.comparing(Operation::amount);
            case "payer" -> Comparator.comparing(op -> op.payer().name());
            case "recipient" -> Comparator.comparing(op -> op.recipient().name());
            default -> Comparator.comparing(Operation::timestamp);
        };
        filtered.sort(query.descending() ? comparator.reversed() : comparator);
        int pages = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * pageSize;
        return new Page(List.copyOf(filtered.subList(start, Math.min(start + pageSize, filtered.size()))),
                filtered.size(), page, pages);
    }
}
