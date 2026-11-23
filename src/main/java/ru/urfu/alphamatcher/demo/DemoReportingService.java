package ru.urfu.alphamatcher.demo;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.demo.DemoOperationState.AuditEvent;
import ru.urfu.alphamatcher.demo.DemoOperationState.BatchRun;

/** Read-only projections of the confirmed, session-scoped demo operation state. */
@Service
public class DemoReportingService {
    public record Statistics(int total, int automatic, int manual, int pending, int other,
                             Map<String, Integer> reasons) {
        public int processed() { return automatic + manual; }
        public int autoShare() { return percent(automatic, processed()); }
        public int percentOfTotal(int value) { return percent(value, total); }
        public int percentOfQueue(int value) { return percent(value, pending); }
    }
    public record RunPage(List<BatchRun> rows, int total, int page, int pages) { }
    public record AuditPage(List<AuditEvent> rows, int total, int page, int pages) { }

    public Statistics statistics(DemoOperationState state) {
        synchronized (state) {
            var matches = state.confirmedMatches();
            int automatic = Math.toIntExact(matches.values().stream()
                    .filter(match -> "Автоматически".equals(match.method())).count());
            int manual = matches.size() - automatic, pending = 0;
            automatic = Math.addExact(automatic,state.durableAutomaticCount());
            Map<String, Integer> reasons = new LinkedHashMap<>();
            for (String category : List.of("Платёж за третье лицо", "Несколько договоров", "Конфликт ИНН",
                    "Недостаточно данных", "Договор не найден / прочее")) reasons.put(category, 0);
            for (String reason : state.queueReasons().values()) {
                pending++;
                String category = classify(reason);
                reasons.put(category, reasons.get(category) + 1);
            }
            int total = state.totalOperations();
            return new Statistics(total, automatic, manual, pending, total - automatic - manual - pending,
                    Collections.unmodifiableMap(reasons));
        }
    }

    private String classify(String reason) {
        String value = normalize(reason);
        if (value.contains("третье лицо")) return "Платёж за третье лицо";
        if (value.contains("несколько") || value.contains("кандидат")) return "Несколько договоров";
        if (value.contains("инн")) return "Конфликт ИНН";
        if (value.contains("недостаточно") || value.contains("неполн")) return "Недостаточно данных";
        return "Договор не найден / прочее";
    }

    public RunPage runs(DemoOperationState state, String search, String type,
                        boolean descending, int requested, int size) {
        String needle = DemoContractService.normalizedText(search);
        List<BatchRun> rows = state.batchRuns().stream()
                .filter(run -> type == null || type.isBlank() || run.type().equals(type))
                .filter(run -> java.util.stream.Stream.of(run.id(), run.initiator(), run.type(), period(run),
                                run.from().toString(), run.to().toString())
                        .anyMatch(value -> DemoContractService.normalizedText(value).contains(needle)))
                .sorted(descending ? Comparator.comparing(BatchRun::time).thenComparing(BatchRun::id).reversed()
                        : Comparator.comparing(BatchRun::time).thenComparing(BatchRun::id)).toList();
        int page = page(requested, size, rows.size());
        return new RunPage(slice(rows, page, size), rows.size(), page, pages(rows.size(), size));
    }

    public static String period(BatchRun run) {
        return run.from().format(DATE) + " - " + run.to().format(DATE);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public AuditPage audit(DemoOperationState state, String search, String actor, String action,
                           boolean descending, int requested, int size) {
        String needle = normalize(search);
        if (size < 1) throw new IllegalArgumentException("Размер страницы должен быть положительным");
        java.util.function.Predicate<AuditEvent> matches = event ->
                (actor == null || actor.isBlank() || event.actor().equals(actor))
                && (action == null || action.isBlank() || event.action().equals(action))
                && normalize(event.operationId() + " " + event.action() + " " + event.previous()
                        + " " + event.current() + " " + event.runId()).contains(needle);
        var chronological = Comparator.comparing(AuditEvent::time).thenComparing(AuditEvent::id);
        var order = descending ? chronological.reversed() : chronological;
        var legacy = state.auditEvents().stream().filter(matches).sorted(order).iterator();
        int target = Math.max(0, requested);
        var selected = new java.util.ArrayList<AuditEvent>(size);
        var last = new java.util.ArrayList<AuditEvent>(size);
        int[] matched = {0};
        java.util.function.Consumer<AuditEvent> collect = event -> {
            if (matched[0] % size == 0) last.clear();
            last.add(event);
            if (matched[0] / size == target) selected.add(event);
            matched[0] = Math.incrementExact(matched[0]);
        };
        AuditEvent[] older = {legacy.hasNext() ? legacy.next() : null};
        state.visitDurableAudit(descending, event -> {
            if (!matches.test(event)) return;
            while (older[0] != null && order.compare(older[0], event) <= 0) {
                collect.accept(older[0]);
                older[0] = legacy.hasNext() ? legacy.next() : null;
            }
            collect.accept(event);
        });
        if (older[0] != null) collect.accept(older[0]);
        legacy.forEachRemaining(collect);
        int pages = Math.max(1, Math.ceilDiv(matched[0], size));
        int page = Math.min(target, pages - 1);
        return new AuditPage(List.copyOf(page == target ? selected : last), matched[0], page, pages);
    }

    public List<String> actors(DemoOperationState state) {
        return java.util.stream.Stream.concat(state.auditEvents().stream().map(AuditEvent::actor),
                state.durableAuditActors().stream()).distinct().sorted().toList();
    }
    public List<String> actions(DemoOperationState state) {
        var existing = state.auditEvents().stream().map(AuditEvent::action);
        return java.util.stream.Stream.concat(existing, state.durableAutomaticCount() == 0
                ? java.util.stream.Stream.empty() : java.util.stream.Stream.of("Автоматическая привязка"))
                .distinct().sorted().toList();
    }
    public static int percent(int value, int denominator) {
        return denominator == 0 ? 0 : (int) Math.round(100.0 * value / denominator);
    }
    private static String normalize(String text) { return text == null ? "" : text.trim().toLowerCase(Locale.ROOT); }
    private static int pages(int count, int size) {
        if (size < 1) throw new IllegalArgumentException("Размер страницы должен быть положительным");
        return Math.max(1, (count + size - 1) / size);
    }
    private static int page(int requested, int size, int count) {
        return Math.max(0, Math.min(requested, pages(count, size) - 1));
    }
    private static <T> List<T> slice(List<T> rows, int page, int size) {
        int start = page * size;
        return List.copyOf(rows.subList(start, Math.min(start + size, rows.size())));
    }
}
