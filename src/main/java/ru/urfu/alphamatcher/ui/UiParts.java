package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.RouterLink;

public final class UiParts {
    private UiParts() { }

    static void openDialog(Component owner, Dialog dialog) {
        var detach = owner.addDetachListener(event -> dialog.close());
        dialog.addOpenedChangeListener(event -> {
            if (!event.isOpened()) detach.remove();
        });
        dialog.open();
    }

    public static Div div(String css, Component... children) {
        Div div = new Div(children);
        div.setClassName(css);
        return div;
    }

    public static Span text(String css, String value) {
        Span span = new Span(value);
        span.setClassName(css);
        return span;
    }

    public static Span beneficiaryBadge(String name) {
        Span badge = text("beneficiary-badge", name);
        badge.getElement().setAttribute("title", name);
        return badge;
    }

    public static Button button(String css, String caption, Runnable action) {
        Button button = new Button(caption, event -> action.run());
        button.setClassName(css);
        return button;
    }

    public static Button iconButton(String css, String caption, VaadinIcon icon, boolean iconAfterText,
                                    Runnable action) {
        Button button = button(css, caption, action);
        button.setIcon(icon.create());
        button.setIconAfterText(iconAfterText);
        return button;
    }

    public static Icon icon(String css, VaadinIcon type) {
        Icon icon = type.create();
        icon.setClassName(css);
        return icon;
    }

    public static Div iconText(String css, VaadinIcon type, String caption) {
        return div(css, icon("inline-icon", type), text("", caption));
    }

    public static RouterLink link(String css, String caption, Class<? extends Component> view) {
        RouterLink link = new RouterLink(caption, view);
        link.setClassName(css);
        return link;
    }
}
