package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@Route(value = "section/:key/:id", layout = MainLayout.class)
@PageTitle("Раздел - А.Выгодоприобретатель")
public class ContextPlaceholderView extends Div implements BeforeEnterObserver {
    public ContextPlaceholderView() {
        setClassName("placeholder-page app-page");
    }
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String key = event.getRouteParameters().get("key").orElse("");
        String id = event.getRouteParameters().get("id").orElse("");
        switch (key) {
            case "manual" -> { event.rerouteTo("manual/" + id); return; }
            case "contracts" -> { event.rerouteTo("contract/" + id); return; }
            case "clients" -> {
                event.rerouteTo("client/" + id);
                return;
            }
            default -> { event.rerouteTo(OperationsView.class); return; }
        }
    }
}
