package ru.urfu.alphamatcher.stage7;

import java.util.function.IntConsumer;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.textfield.TextField;

/** Shared zero-based pagination control for registry tables. */
public final class AppPagination extends Div {
    public static final int PAGE_SIZE = 10;
    public record Window(int page, int pages, int start, int end) { }

    public static Window window(int total, int requestedPage) {
        if (total < 0) throw new IllegalArgumentException("Количество записей не может быть отрицательным");
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        return new Window(page, pages, total == 0 ? 0 : page * PAGE_SIZE + 1,
                Math.min(total, (page + 1) * PAGE_SIZE));
    }

    private final Span range = UiParts.text("", "");
    private final Span pageCount = UiParts.text("", "");
    private final TextField pageInput = new TextField();
    private final Button first;
    private final Button previous;
    private final Button next;
    private final Button last;
    private final IntConsumer navigate;
    private int page;
    private int pages = 1;

    public AppPagination(IntConsumer navigate) {
        this.navigate = navigate;
        setClassName("registry-pagination app-pagination");
        first = button("Первая страница", VaadinIcon.ANGLE_DOUBLE_LEFT, () -> go(0));
        previous = button("Предыдущая страница", VaadinIcon.ARROW_LEFT, () -> go(page - 1));
        next = button("Следующая страница", VaadinIcon.ARROW_RIGHT, () -> go(page + 1));
        last = button("Последняя страница", VaadinIcon.ANGLE_DOUBLE_RIGHT, () -> go(pages - 1));
        pageInput.setClassName("app-pagination-input");
        pageInput.setAriaLabel("Номер страницы");
        pageInput.setValue("1");
        pageInput.addKeyPressListener(Key.ENTER, event -> submit());
        add(range, UiParts.div("registry-page-controls", first, previous,
                UiParts.text("", "Страница"), pageInput, pageCount, next, last));
        update(0, 0);
    }

    private Button button(String label, VaadinIcon icon, Runnable action) {
        Button button = UiParts.iconButton("registry-page-button", "", icon, false, action);
        button.getElement().setAttribute("aria-label", label);
        button.getElement().setAttribute("title", label);
        return button;
    }

    private void go(int target) {
        if (target >= 0 && target < pages && target != page) navigate.accept(target);
    }

    private void submit() {
        String value = pageInput.getValue().trim();
        try {
            int target = Integer.parseInt(value);
            if (target < 1 || target > pages) throw new NumberFormatException();
            pageInput.setInvalid(false);
            go(target - 1);
        } catch (NumberFormatException ex) {
            pageInput.setInvalid(true);
            pageInput.setErrorMessage("Введите номер от 1 до " + pages);
        }
    }

    public void update(int total, int requestedPage) {
        Window window = window(total, requestedPage);
        pages = window.pages();
        page = window.page();
        range.setText("Показаны " + window.start() + "-" + window.end() + " из " + total);
        pageInput.setValue(String.valueOf(page + 1));
        pageInput.setInvalid(false);
        pageCount.setText("из " + pages);
        first.setEnabled(page > 0);
        previous.setEnabled(page > 0);
        next.setEnabled(page < pages - 1);
        last.setEnabled(page < pages - 1);
        pageInput.setEnabled(pages > 1);
    }

    public int page() { return page; }
    public int pages() { return pages; }
}
