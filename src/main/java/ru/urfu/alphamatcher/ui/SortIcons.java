package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.Html;

/** Equal-size inline SVGs for sortable table headings. */
final class SortIcons {
    private SortIcons() { }

    static Html icon(boolean active, boolean descending) {
        String path = !active ? "M8 3v10 M5 6l3-3 3 3 M5 10l3 3 3-3"
                : descending ? "M8 3v10 M4 9l4 4 4-4" : "M8 13V3 M4 7l4-4 4 4";
        return new Html("<svg class='sort-svg' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' "
                + "fill='none' stroke='currentColor' stroke-width='1.8' stroke-linecap='round' "
                + "stroke-linejoin='round' aria-hidden='true'><path d='" + path + "'/></svg>");
    }
}
