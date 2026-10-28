package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.ErrorParameter;
import com.vaadin.flow.router.HasErrorParameter;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.ParentLayout;

@ParentLayout(MainLayout.class)
@PageTitle("Страница не найдена - А.Выгодоприобретатель")
public class UnknownRouteView extends Div implements HasErrorParameter<NotFoundException> {
    public UnknownRouteView() {
        setClassName("placeholder-page app-page");
        add(NotFoundView.content());
    }

    @Override
    public int setErrorParameter(BeforeEnterEvent event, ErrorParameter<NotFoundException> parameter) {
        return 404;
    }
}
