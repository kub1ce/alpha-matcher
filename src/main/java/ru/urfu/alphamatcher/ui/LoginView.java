package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import ru.urfu.alphamatcher.demo.DemoRole;
import ru.urfu.alphamatcher.demo.DemoSession;
import ru.urfu.alphamatcher.demo.DemoUser;

@Route("login")
@PageTitle("Вход - А.Выгодоприобретатель")
public class LoginView extends Div {
    private DemoRole selected = DemoRole.OPERATIONS;
    private final TextField login = new TextField();
    private final PasswordField password = new PasswordField();
    private final Span error = UiParts.text("login-error", "");

    public LoginView() {
        setClassName("login-page");
        ThemeToggle themeToggle = new ThemeToggle();
        themeToggle.addClassName("login-theme-toggle");
        Div card = UiParts.div("login-card");
        Div brand = UiParts.div("login-brand",
                UiParts.text("login-mark", "α"),
                new H1("А.Выгодоприобретатель"),
                UiParts.text("muted", "Система привязки операций к договорам"));
        Div form = UiParts.div("login-form");
        form.add(UiParts.text("field-label strong", "Роль:"));
        var roleOne = UiParts.button("role-option selected", "Сотрудник по операциям", () -> select(DemoRole.OPERATIONS));
        var roleTwo = UiParts.button("role-option", "Принимающий решения", () -> select(DemoRole.DECISION_MAKER));
        form.add(UiParts.div("role-switch", roleOne, roleTwo));
        login.setLabel("Учетная запись (логин):");
        login.setValue(DemoUser.forRole(selected).email());
        login.setPrefixComponent(VaadinIcon.USER.create());
        login.setWidthFull();
        password.setLabel("Пароль:");
        password.setValue("demopassword");
        password.setPrefixComponent(VaadinIcon.LOCK.create());
        password.setWidthFull();
        password.setRevealButtonVisible(true);
        var forgot = UiParts.button("forgot-link", "Забыли пароль?", () ->
                Notification.show("Восстановление пароля пока недоступно"));
        Div passwordLabel = UiParts.div("password-line", UiParts.text("field-label", "Пароль:"), forgot);
        password.setLabel(null);
        password.setAriaLabel("Пароль");
        var submit = UiParts.iconButton("login-submit app-action app-action--primary", "Войти", VaadinIcon.ARROW_RIGHT, true, () -> {
            boolean valid = !login.getValue().isBlank() && !password.getValue().isBlank();
            login.setInvalid(login.getValue().isBlank());
            password.setInvalid(password.getValue().isBlank());
            error.setText(valid ? "" : "Введите логин и пароль");
            if (valid) {
                DemoSession.login(selected);
                UI.getCurrent().navigate("");
            }
        });
        login.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, event -> submit.click());
        password.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, event -> submit.click());
        form.add(login, passwordLabel, password, error, submit);
        card.add(brand, form);
        add(themeToggle, card);
        roleOne.addClickListener(e -> { roleOne.addClassName("selected"); roleTwo.removeClassName("selected"); });
        roleTwo.addClickListener(e -> { roleTwo.addClassName("selected"); roleOne.removeClassName("selected"); });
    }

    private void select(DemoRole role) {
        selected = role;
        login.setValue(DemoUser.forRole(role).email());
        password.setValue("demopassword");
        error.setText("");
    }
}
