package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import ru.urfu.alphamatcher.demo.DemoRole;
import ru.urfu.alphamatcher.demo.DemoSession;
import ru.urfu.alphamatcher.demo.DemoUser;

@Route(value = "profile", layout = MainLayout.class)
@PageTitle("Профиль - А.Выгодоприобретатель")
public class ProfileView extends Div {
    public ProfileView() {
        setClassName("profile-page app-page");
        refreshRole();
    }

    public void refreshRole() {
        removeAll();
        DemoUser user = DemoSession.user();
        if (user == null) return;
        DemoRole role = user.role();

        Div identity = UiParts.div("profile-identity",
                UiParts.text("profile-avatar", user.initials()),
                UiParts.div("profile-identity-copy",
                        UiParts.text("profile-name", user.fullName()),
                        UiParts.text("profile-email", user.email()),
                        UiParts.iconText("profile-role-badge", VaadinIcon.USER_CHECK, role.label())));
        var logout = UiParts.iconButton("profile-logout app-action app-action--danger", "Выйти из системы",
                VaadinIcon.SIGN_OUT, false, () -> {
                    DemoSession.logout();
                });

        Div details = UiParts.div("profile-section",
                UiParts.text("profile-section-title", "СЛУЖЕБНЫЕ ПАРАМЕТРЫ ПРОФИЛЯ"),
                UiParts.div("profile-info-grid",
                        infoCard("Подразделение:", "Управление операционной поддержки корпоративного бизнеса"),
                        infoCard("Полномочия:", permissions(role))));

        Div choices = UiParts.div("profile-section",
                UiParts.text("profile-section-title", "СМЕНИТЬ ПОЛЬЗОВАТЕЛЯ"),
                UiParts.div("profile-role-grid", roleCard(DemoRole.OPERATIONS, role),
                        roleCard(DemoRole.DECISION_MAKER, role)));

        add(UiParts.div("profile-card", UiParts.text("profile-page-title", "Профиль сотрудника"),
                UiParts.div("profile-header", identity, logout), details, choices));
    }

    private Div infoCard(String label, String value) {
        return UiParts.div("profile-info-card", UiParts.text("profile-info-label", label),
                UiParts.text("profile-info-value", value));
    }

    private Button roleCard(DemoRole choice, DemoRole current) {
        boolean selected = choice == current;
        Button button = UiParts.button(selected ? "profile-role-card selected" : "profile-role-card", "", () -> {
            if (DemoSession.role() == choice) return;
            UI.getCurrent().getChildren().filter(MainLayout.class::isInstance)
                    .map(MainLayout.class::cast).forEach(layout -> layout.changeRole(choice));
            refreshRole();
        });
        button.getElement().appendChild(UiParts.div("profile-role-copy",
                        UiParts.text("profile-role-name", choice.label()),
                        UiParts.text("profile-role-description", permissions(choice))).getElement(),
                UiParts.icon("profile-role-check", VaadinIcon.CHECK).getElement());
        button.getElement().setAttribute("aria-pressed", String.valueOf(selected));
        return button;
    }

    private static String permissions(DemoRole role) {
        return role == DemoRole.OPERATIONS
                ? "Первичный разбор нераспознанных платежей, сверка платежей за третьих лиц и ручная привязка."
                : "Перепривязка сложных договоров, утверждение коллизий и аудит пакетных сессий сопоставления.";
    }
}
