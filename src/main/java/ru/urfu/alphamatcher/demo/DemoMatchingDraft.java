package ru.urfu.alphamatcher.demo;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Session-scoped UI draft. It contains proposals and selection, never confirmed links. */
public class DemoMatchingDraft implements Serializable {
    private LocalDate from = LocalDate.of(2026, 9, 1);
    private LocalDate to = LocalDate.of(2026, 10, 12);
    private boolean includeProcessed;
    private boolean includeManual;
    private int page;
    private DemoMatchingService.Preview preview;
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean hidden;
    private LocalDateTime previewAt;
    private String previewAuthor;

    public LocalDate from() { return from; }
    public LocalDate to() { return to; }
    public boolean includeProcessed() { return includeProcessed; }
    public boolean includeManual() { return includeManual; }
    public int page() { return page; }
    public void page(int page) { this.page = Math.max(0, page); }
    public DemoMatchingService.Preview preview() { return preview; }
    public Set<String> selected() { return selected; }
    public void selectPage(List<DemoMatchingService.Proposal> visible, boolean checked) {
        for (var proposal : visible) {
            if (checked) selected.add(proposal.operationId());
            else selected.remove(proposal.operationId());
        }
    }
    public void selectResults(List<DemoMatchingService.Proposal> filtered) {
        selected.clear();
        selectPage(filtered, true);
    }
    public void retainResults(List<DemoMatchingService.Proposal> filtered) {
        Set<String> allowed = new LinkedHashSet<>();
        for (var proposal : filtered) allowed.add(proposal.operationId());
        selected.retainAll(allowed);
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
}
