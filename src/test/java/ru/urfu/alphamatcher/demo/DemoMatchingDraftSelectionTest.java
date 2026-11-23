package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DemoMatchingDraftSelectionTest {
    @Test
    void pageAndAllResultsSelectionStayIndependentAcrossPages() {
        var draft = new DemoMatchingDraft();
        List<DemoMatchingService.Proposal> rows = IntStream.range(0, 127)
                .mapToObj(index -> new DemoMatchingService.Proposal("PAY-%03d".formatted(index),
                        DemoMatchingService.Status.SAFE, "AG-001", List.of("AG-001"), "", false))
                .toList();
        draft.selectPage(rows.subList(0, 10), true);
        assertEquals(10, draft.selected().size());
        draft.selectPage(rows.subList(70, 80), true);
        assertEquals(20, draft.selected().size());
        draft.selectResults(rows);
        assertEquals(127, draft.selected().size());
        draft.selectPage(rows.subList(70, 73), false);
        assertEquals(124, draft.selected().size());
        assertTrue(draft.selected().contains(rows.getFirst().operationId()));
        assertFalse(draft.selected().contains(rows.get(70).operationId()));
        draft.selectPage(rows.subList(70, 71), true);
        assertEquals(125, draft.selected().size());
        draft.retainResults(rows.subList(0, 20));
        assertEquals(20, draft.selected().size());
        draft.selected().clear();
        assertEquals(0, draft.selected().size());
    }

    @Test
    void frozenSelectAllKeepsExclusionsAndDoesNotIncludeALaterPreview() {
        var first = new DemoMatchingService.Proposal("A", DemoMatchingService.Status.SAFE,
                "AG-001", List.of("AG-001"), "", false);
        var second = new DemoMatchingService.Proposal("B", DemoMatchingService.Status.REVIEW,
                null, List.of(), "", false);
        var draft = new DemoMatchingDraft();
        var preview = new DemoMatchingService.Preview(null,null,false,false,0,0,List.of(first,second),"");
        draft.save(preview, "Тест");
        draft.selectResults(preview.rows());
        draft.selectPage(List.of(second), false);
        var frozen = draft.selectionSnapshot();
        assertEquals(List.of(first), draft.selectedRows(preview.rows()).toList());
        draft.selectPage(List.of(second), true);
        assertEquals(2, draft.selected().size());
        draft.save(new DemoMatchingService.Preview(null,null,false,false,0,0,List.of(second),""), "Тест");
        assertEquals(java.util.Set.of("A"), frozen);
        assertTrue(draft.selected().isEmpty());
    }
}
