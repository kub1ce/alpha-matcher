package ru.urfu.alphamatcher.demo;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        int automatic = 0, manual = 0, pending = 0, other = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (String category : List.of("Платёж за третье лицо", "Несколько договоров", "Конфликт ИНН",
                "Недостаточно данных", "Договор не найден / прочее")) reasons.put(category, 0);
        for (var operation : state.all()) {
            var match = state.match(operation.id());
            if (match != null) {
                if ("Автоматически".equals(match.method())) automatic++;
                else manual++;
            } else if (state.isManualQueued(operation.id())) {
                pending++;
                String reason = state.manualReason(operation.id()) == null
                        ? operation.reason() : state.manualReason(operation.id());
                String category = classify(reason);
                reasons.put(category, reasons.get(category) + 1);
            } else other++;
        }
        return new Statistics(state.all().size(), automatic, manual, pending, other,
                Collections.unmodifiableMap(reasons));
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
        String needle = normalize(search);
        List<BatchRun> rows = state.batchRuns().stream()
                .filter(run -> type == null || type.isBlank() || run.type().equals(type))
                .filter(run -> normalize(run.id() + " " + run.initiator() + " " + run.type()
                        + " " + run.from() + " " + run.to()).contains(needle))
                .sorted(descending ? Comparator.comparing(BatchRun::time).thenComparing(BatchRun::id).reversed()
                        : Comparator.comparing(BatchRun::time).thenComparing(BatchRun::id)).toList();
        int page = page(requested, size, rows.size());
        return new RunPage(slice(rows, page, size), rows.size(), page, pages(rows.size(), size));
    }

    public AuditPage audit(DemoOperationState state, String search, String actor, String action,
                           boolean descending, int requested, int size) {
        String needle = normalize(search);
        List<AuditEvent> rows = state.auditEvents().stream()
                .filter(event -> actor == null || actor.isBlank() || event.actor().equals(actor))
                .filter(event -> action == null || action.isBlank() || event.action().equals(action))
                .filter(event -> normalize(event.operationId() + " " + event.action() + " "
                        + event.previous() + " " + event.current() + " " + event.runId()).contains(needle))
                .sorted(descending ? Comparator.comparing(AuditEvent::time).thenComparing(AuditEvent::id).reversed()
                        : Comparator.comparing(AuditEvent::time).thenComparing(AuditEvent::id)).toList();
        int page = page(requested, size, rows.size());
        return new AuditPage(slice(rows, page, size), rows.size(), page, pages(rows.size(), size));
    }

    public List<String> actors(DemoOperationState state) {
        return state.auditEvents().stream().map(AuditEvent::actor).distinct().sorted().toList();
    }
    public List<String> actions(DemoOperationState state) {
        return state.auditEvents().stream().map(AuditEvent::action).distinct().sorted().toList();
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
