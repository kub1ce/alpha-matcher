package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

@Route(value = "not-found", layout = MainLayout.class)
@PageTitle("Страница не найдена - А.Выгодоприобретатель")
public class NotFoundView extends Div {
    public NotFoundView() {
        setClassName("placeholder-page app-page");
        add(content());
    }

    static Div content() {
        RouterLink home = new RouterLink("На главную", HomeView.class);
        home.setClassName("app-action app-action--secondary");
        return UiParts.div("placeholder-card",
                UiParts.text("placeholder-title", "Страница не найдена"),
                UiParts.text("muted", "Проверьте адрес или вернитесь на главную."), home);
    }
}
