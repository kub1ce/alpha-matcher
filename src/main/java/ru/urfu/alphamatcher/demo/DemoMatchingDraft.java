package ru.urfu.alphamatcher.demo;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import ru.urfu.alphamatcher.demo.DemoMatchingService.Proposal;

/** UI-scoped draft, retained across navigation in one tab. Never contains confirmed links. */
public class DemoMatchingDraft implements Serializable {
    private LocalDate from = LocalDate.of(2026, 9, 1);
    private LocalDate to = LocalDate.of(2026, 10, 12);
    private boolean includeProcessed;
    private boolean includeManual;
    private int page;
    private DemoMatchingService.Preview preview;
    private final Selection selected = new Selection();
    private boolean hidden;
    private LocalDateTime previewAt;
    private String previewAuthor;
    private long selectionEmployee;

    void bindSelectionToEmployee(long generation) {
        if (selectionEmployee != generation) {
            selected.clear();
            selectionEmployee = generation;
        }
    }

    public LocalDate from() { return from; }
    public LocalDate to() { return to; }
    public boolean includeProcessed() { return includeProcessed; }
    public boolean includeManual() { return includeManual; }
    public int page() { return page; }
    public void page(int page) { this.page = Math.max(0, page); }
    public DemoMatchingService.Preview preview() { return preview; }
    public Set<String> selected() { return selected; }
    public Set<String> selectionSnapshot() {
        var snapshot = new Selection();
        snapshot.all = selected.all;
        snapshot.excluded.addAll(selected.excluded);
        snapshot.included.addAll(selected.included);
        return java.util.Collections.unmodifiableSet(snapshot);
    }
    public void selectPage(List<DemoMatchingService.Proposal> visible, boolean checked) {
        for (var proposal : visible) {
            if (checked) selected.add(proposal.operationId());
            else selected.remove(proposal.operationId());
        }
    }
    public void selectResults(List<DemoMatchingService.Proposal> filtered) {
        selected.clear();
        if (preview != null && filtered == preview.rows()) selected.all = filtered;
        else selectPage(filtered, true);
    }
    public void retainResults(List<DemoMatchingService.Proposal> filtered) {
        if (filtered == selected.all) return;
        var kept = new LinkedHashSet<String>();
        for (var proposal : filtered)
            if (selected.contains(proposal.operationId())) kept.add(proposal.operationId());
        selected.clear();
        selected.addAll(kept);
    }
    public Stream<Proposal> selectedRows(List<Proposal> rows) {
        return rows == selected.all
                ? rows.stream().filter(row -> !selected.excluded.contains(row.operationId()))
                : rows.stream().filter(row -> selected.contains(row.operationId()));
    }
    public boolean hidden() { return hidden; }
    public LocalDateTime previewAt() { return previewAt; }
    public String previewAuthor() { return previewAuthor; }
    public void parameters(LocalDate from, LocalDate to, boolean includeProcessed) {
        parameters(from, to, includeProcessed, includeManual);
    }
    public void parameters(LocalDate from, LocalDate to, boolean includeProcessed, boolean includeManual) {
        this.from = from;
        this.to = to;
        this.includeProcessed = includeProcessed;
        this.includeManual = includeManual;
    }
    public void save(DemoMatchingService.Preview preview, String author) {
        this.preview = preview;
        this.previewAt = LocalDateTime.now();
        this.previewAuthor = author;
        this.hidden = false;
        selected.clear();
        page = 0;
    }
    public void hide() { hidden = true; }
    public void restore() { hidden = false; }

    /** Selecting every row refers to the existing immutable preview and keeps only exceptions. */
    private static final class Selection extends AbstractSet<String> implements Serializable {
        private List<Proposal> all = List.of();
        private final Set<String> excluded = new LinkedHashSet<>();
        private final Set<String> included = new LinkedHashSet<>();

        private boolean inPreview(Object id) {
            return all.stream().anyMatch(row -> row.operationId().equals(id));
        }

        @Override public int size() { return all.size() - excluded.size() + included.size(); }
        @Override public boolean contains(Object id) {
            return included.contains(id) || !excluded.contains(id) && inPreview(id);
        }
        @Override public boolean add(String id) {
            return inPreview(id) ? excluded.remove(id) : included.add(id);
        }
        @Override public boolean remove(Object id) {
            return included.remove(id) || id instanceof String key && inPreview(key) && excluded.add(key);
        }
        @Override public void clear() {
            all = List.of();
            excluded.clear();
            included.clear();
        }
        @Override public Iterator<String> iterator() {
            return Stream.concat(all.stream().map(Proposal::operationId).filter(id -> !excluded.contains(id)),
                    included.stream()).iterator();
        }
    }
}
