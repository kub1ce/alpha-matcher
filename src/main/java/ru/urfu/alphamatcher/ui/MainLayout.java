package ru.urfu.alphamatcher.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import ru.urfu.alphamatcher.demo.DemoNotificationService;
import ru.urfu.alphamatcher.demo.DemoNotificationService.Kind;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoManualReviewService;
import ru.urfu.alphamatcher.demo.DemoRole;
import ru.urfu.alphamatcher.demo.DemoSession;
import ru.urfu.alphamatcher.demo.DemoUser;
import ru.urfu.alphamatcher.demo.DemoPermissions;

public class MainLayout extends AppLayout implements BeforeEnterObserver, BeforeLeaveObserver, AfterNavigationObserver {
    private final DemoDataService data;
    private final DemoManualReviewService manualReview;
    private Span manualBadge;
    private final List<RouterLink> links = new ArrayList<>();
    private final Span employeeName = UiParts.text("employee-name", "");
    private final Span employeeRole = UiParts.text("employee-role", "");
    private final Span initials = UiParts.text("avatar", "");
    private final Div notificationPopup = UiParts.div("popup notification-popup");
    private final Div notificationList = UiParts.div("notification-list");
    private final Span unreadDot = UiParts.text("bell-dot", "");
    private final Div employeePopup = UiParts.div("popup employee-popup");
    private final Span popupName = UiParts.text("popup-name", "");
    private final Span popupLogin = UiParts.text("muted", "");
    private final Span popupRoleLabel = UiParts.text("role-chip-label", "");
    private final Icon popupOperationsIcon = UiParts.icon("inline-icon", VaadinIcon.USER);
    private final Icon popupDecisionIcon = UiParts.icon("inline-icon", VaadinIcon.USER_CHECK);
    private final Div roleChoices = UiParts.div("role-choices");
    private final Map<String, Notification> visibleToasts = new LinkedHashMap<>();
    private Runnable removeUserListener;

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        UI ui = event.getUI();
        removeUserListener = DemoSession.onUserChanged(() -> {
            if (!ui.isAttached()) return;
            UI previous = UI.getCurrent();
            try {
                UI.setCurrent(ui);
                // Dialog overlays are UI children, including confirmations opened by older views.
                ui.getChildren().filter(Dialog.class::isInstance).map(Dialog.class::cast)
                        .filter(Dialog::isOpened).toList().forEach(Dialog::close);
                if (DemoSession.user() == null) {
                    getChildren().filter(AuditLogView.class::isInstance).map(AuditLogView.class::cast)
                            .forEach(AuditLogView::recheckAccess);
                    ui.getPage().setLocation("/login");
                } else {
                    refreshRoleDisplay();
                    getChildren().filter(AuditLogView.class::isInstance).map(AuditLogView.class::cast)
                            .forEach(AuditLogView::recheckAccess);
                    employeePopup.setVisible(false);
                }
                if (ui != previous) ui.push();
            } finally {
                UI.setCurrent(previous);
            }
        });
    }

    @Override
    protected void onDetach(DetachEvent event) {
        if (removeUserListener != null) {
            removeUserListener.run();
            removeUserListener = null;
        }
        super.onDetach(event);
    }

    public MainLayout(DemoDataService data, DemoManualReviewService manualReview) {
        this.data = data;
        this.manualReview = manualReview;
        addClassName("main-layout");
        setPrimarySection(Section.NAVBAR);
        Div logo = UiParts.div("header-logo", UiParts.div("small-mark", UiParts.text("small-mark-glyph", "α")),
                UiParts.text("header-logo-text", "А.Выгодоприобретатель"));
        var bell = UiParts.button("bell-button", "", () -> toggle(notificationPopup, employeePopup));
        bell.getElement().appendChild(new Html("<svg class='toolbar-svg bell-svg' xmlns='http://www.w3.org/2000/svg' "
                + "viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' "
                + "stroke-linecap='round' stroke-linejoin='round' aria-hidden='true'>"
                + "<path d='M18 8a6 6 0 0 0-12 0c0 5.5-2.5 7-2.5 9h17C20.5 15 18 13.5 18 8Z'/>"
                + "<path d='M10 20a2 2 0 0 0 4 0'/></svg>").getElement());
        bell.getElement().setAttribute("aria-label", "Уведомления");
        bell.getElement().setAttribute("title", "Уведомления");
        bell.getElement().appendChild(unreadDot.getElement());
        ThemeToggle themeButton = new ThemeToggle();
        Div userButton = UiParts.div("user-button", initials,
                UiParts.div("user-button-text", employeeName, employeeRole),
                UiParts.icon("chevron", VaadinIcon.CHEVRON_DOWN));
        userButton.getElement().addEventListener("click", event -> toggle(employeePopup, notificationPopup));
        userButton.getElement().addEventListener("keydown", event -> {
            String key = event.getEventData().get("event.key").asText();
            if ("Enter".equals(key) || " ".equals(key)) toggle(employeePopup, notificationPopup);
        }).addEventData("event.key");
        userButton.getElement().setAttribute("role", "button");
        userButton.getElement().setAttribute("tabindex", "0");
        userButton.getElement().setAttribute("aria-label", "Меню сотрудника");
        var drawerButton = UiParts.iconButton("mobile-drawer-toggle", "", VaadinIcon.MENU, false,
                () -> setDrawerOpened(!isDrawerOpened()));
        drawerButton.getElement().setAttribute("aria-label", "Открыть или закрыть меню");
        Div top = UiParts.div("topbar", drawerButton, logo,
                UiParts.div("topbar-right", bell, themeButton, userButton));
        Div popupLayer = UiParts.div("popup-layer", notificationPopup, employeePopup);
        addToNavbar(top, popupLayer);
        renderNotifications();
        notificationPopup.setVisible(false);
        buildEmployeePopup();

        Div sidebar = UiParts.div("sidebar");
        sidebar.add(group("ОСНОВНОЕ", new String[][] {
                {"Главная", "/"}, {"Статистика", "/section/statistics"},
                {"Отчёты", "/section/reports"},
                {"Справочники", "/section/directories"}
        }));
        sidebar.add(group("КОНТРОЛЬ", new String[][] {
                {"Операции", "/section/operations"}, {"Договоры", "/section/contracts"},
                {"Ручной разбор", "/section/manual"}, {"Автопривязка", "/section/matching"},
                {"Клиенты", "/section/clients"}
        }));
        sidebar.add(group("АДМИНИСТРИРОВАНИЕ", new String[][] {
                {"История запусков", "/section/runs"}, {"Журнал действий", "/section/audit"},
                {"Проверка парсинга назначений платежа", "/section/parsing"},
                {"Профиль сотрудника", "/profile"}
        }));
        addToDrawer(sidebar);
    }

    private Div group(String title, String[][] items) {
        Div group = UiParts.div("nav-group");
        Div list = UiParts.div("nav-list");
        var heading = new Button(title);
        heading.setIcon(VaadinIcon.CHEVRON_DOWN.create());
        heading.setIconAfterText(true);
        heading.setClassName("nav-heading");
        heading.getElement().setAttribute("aria-expanded", "true");
        Div panel = UiParts.div("nav-list-panel", list);
        heading.addClickListener(event -> {
            boolean expanded = group.hasClassName("collapsed");
            group.setClassName(expanded ? "nav-group" : "nav-group collapsed");
            heading.getElement().setAttribute("aria-expanded", String.valueOf(expanded));
            if (expanded) {
                panel.getElement().removeAttribute("inert");
                panel.getElement().removeAttribute("aria-hidden");
            } else {
                panel.getElement().setAttribute("inert", "");
                panel.getElement().setAttribute("aria-hidden", "true");
            }
        });
        group.add(heading);
        for (String[] item : items) {
            if (item[1].equals("/section/reports") || item[1].equals("/section/directories")) {
                Icon icon = VaadinIcon.LOCK.create();
                icon.getElement().setAttribute("aria-hidden", "true");
                Div unavailable = UiParts.div("nav-link future",
                        UiParts.text("nav-future-title", item[0]), icon);
                unavailable.getElement().setAttribute("role", "link");
                unavailable.getElement().setAttribute("aria-disabled", "true");
                unavailable.getElement().setAttribute("aria-description", "Раздел пока недоступен");
                unavailable.getElement().setAttribute("title", "Раздел пока недоступен");
                unavailable.getElement().setAttribute("tabindex", "0");
                list.add(unavailable);
                continue;
            }
            RouterLink link;
            if (item[1].equals("/")) {
                link = new RouterLink(item[0], HomeView.class);
            } else if (item[1].equals("/profile")) {
                link = new RouterLink(item[0], ProfileView.class);
            } else if (item[1].equals("/section/operations")) {
                link = new RouterLink(item[0], OperationsView.class);
            } else if (item[1].equals("/section/contracts")) {
                link = new RouterLink(item[0], ContractsView.class);
            } else if (item[1].equals("/section/manual")) {
                link = new RouterLink(item[0], ManualReviewView.class);
            } else if (item[1].equals("/section/matching")) {
                link = new RouterLink(item[0], MatchingView.class);
            } else if (item[1].equals("/section/statistics")) {
                link = new RouterLink(item[0], StatisticsView.class);
            } else if (item[1].equals("/section/runs")) {
                link = new RouterLink(item[0], RunHistoryView.class);
            } else if (item[1].equals("/section/audit")) {
                link = new RouterLink(item[0], AuditLogView.class);
            } else if (item[1].equals("/section/parsing")) {
                link = new RouterLink(item[0], ParsingView.class);
            } else {
                link = new RouterLink(item[0], SectionView.class,
                        new RouteParameters("key", item[1].substring("/section/".length())));
            }
            link.setClassName("nav-link");
            if (item[1].equals("/section/manual")) {
                manualBadge = UiParts.text("nav-badge", "");
                link.add(manualBadge);
            }
            list.add(link);
            links.add(link);
        }
        group.add(panel);
        return group;
    }

    private void renderNotifications() {
        DemoNotificationService state = DemoNotificationService.current();
        unreadDot.setVisible(state.unreadCount() > 0);
        notificationPopup.removeAll();
        notificationList.removeAll();
        var markAll = UiParts.iconButton("notice-mark-all", "Отметить все как прочитанные",
                VaadinIcon.CHECK, false, () -> {
                    state.markAllRead();
                    renderNotifications();
                });
        markAll.setVisible(state.unreadCount() > 0);
        notificationPopup.add(UiParts.div("notice-popup-header",
                UiParts.text("popup-heading", "Уведомления"), markAll), notificationList);
        if (state.unreadItems().isEmpty()) {
            notificationList.add(UiParts.text("notification-empty", "Уведомлений нет"));
        }
        for (DemoNotificationService.Notice notice : state.unreadItems()) {
            Div item = UiParts.div("notification-item notice-" + notice.kind().name().toLowerCase(),
                    UiParts.iconText("notice-title",
                            noticeIcon(notice.kind()), notice.title()),
                    UiParts.text("muted", notice.details()),
                    UiParts.text("notice-date", notice.timestamp()));
            Div actions = UiParts.div("notice-actions");
            actions.add(UiParts.iconButton("notice-action", "Отметить прочитанным",
                    VaadinIcon.CHECK, false, () -> {
                        state.markRead(notice.id());
                        renderNotifications();
                    }));
            item.add(actions);
            notificationList.add(item);
        }
    }

    public void refreshNotificationCenter() {
        renderNotifications();
    }

    public void showToast(Kind kind, String title, String details) {
        String key = kind + "|" + title + "|" + details;
        visibleToasts.entrySet().removeIf(entry -> !entry.getValue().isOpened());
        if (visibleToasts.containsKey(key)) return;
        if (visibleToasts.size() == 3) {
            String oldest = visibleToasts.keySet().iterator().next();
            visibleToasts.remove(oldest).close();
        }
        Notification toast = new Notification();
        toast.addClassName("app-toast");
        toast.setPosition(Notification.Position.BOTTOM_END);
        toast.setDuration(kind == Kind.ERROR ? 7000 : 4500);
        var close = UiParts.iconButton("toast-close", "", VaadinIcon.CLOSE, false, () -> {
            toast.close();
            visibleToasts.remove(key);
        });
        close.getElement().setAttribute("aria-label", "Закрыть уведомление");
        toast.add(UiParts.div("toast-card toast-" + kind.name().toLowerCase(),
                UiParts.icon("toast-icon", noticeIcon(kind)),
                UiParts.div("toast-copy", UiParts.text("toast-title", title),
                        UiParts.text("toast-details", details)), close));
        visibleToasts.put(key, toast);
        toast.open();
    }

    private static VaadinIcon noticeIcon(Kind kind) {
        return switch (kind) {
            case SUCCESS -> VaadinIcon.CHECK_CIRCLE_O;
            case INFO -> VaadinIcon.INFO_CIRCLE_O;
            case WARNING -> VaadinIcon.EXCLAMATION_CIRCLE_O;
            case ERROR -> VaadinIcon.CLOSE_CIRCLE_O;
        };
    }

    private void buildEmployeePopup() {
        employeePopup.add(popupName, popupLogin,
                UiParts.div("role-chip", popupOperationsIcon, popupDecisionIcon, popupRoleLabel),
                UiParts.text("popup-caption", "СМЕНИТЬ ПОЛЬЗОВАТЕЛЯ"), roleChoices);
        for (DemoRole role : DemoRole.values()) {
            var choice = UiParts.button("role-choice", role.label(), () -> {
                changeRole(role);
            });
            roleChoices.add(choice);
        }
        employeePopup.add(UiParts.iconButton("popup-action", "Профиль сотрудника", VaadinIcon.USER, false, () -> {
                    employeePopup.setVisible(false);
                    UI.getCurrent().navigate(ProfileView.class);
                }),
                UiParts.iconButton("popup-action logout", "Выйти из системы", VaadinIcon.SIGN_OUT, false, () -> {
                    DemoSession.logout();
                }));
        employeePopup.setVisible(false);
    }

    public void changeRole(DemoRole role) {
        if (DemoSession.role() == role) return;
        DemoSession.login(role);
        AppNotifications.success("Пользователь изменён", DemoSession.user().fullName()
                + " · " + role.label());
    }

    public void refreshRoleDisplay() {
        DemoUser user = DemoSession.user();
        if (user == null) return;
        DemoRole role = user.role();
        employeeName.setText(user.shortName());
        employeeRole.setText(role.label());
        initials.setText(user.initials());
        popupName.setText(user.fullName());
        popupLogin.setText(user.email());
        popupRoleLabel.setText(role.label());
        popupOperationsIcon.setVisible(role == DemoRole.OPERATIONS);
        popupDecisionIcon.setVisible(role == DemoRole.DECISION_MAKER);
        for (RouterLink link : links) if (link.getHref().endsWith("section/audit"))
            link.setVisible(DemoPermissions.allowsCurrent(DemoPermissions.Action.VIEW_AUDIT));
        int index = 0;
        for (Component component : roleChoices.getChildren().toList()) {
            component.setClassName(role == DemoRole.values()[index++] ? "role-choice selected" : "role-choice");
        }
        getChildren().filter(ProfileView.class::isInstance).map(ProfileView.class::cast)
                .forEach(ProfileView::refreshRole);
        getChildren().filter(HomeView.class::isInstance).map(HomeView.class::cast)
                .forEach(HomeView::refreshRole);
        getChildren().filter(MatchingView.class::isInstance).map(MatchingView.class::cast)
                .forEach(MatchingView::refreshUser);
        getChildren().filter(OperationDetailView.class::isInstance).map(OperationDetailView.class::cast)
                .forEach(OperationDetailView::refreshPermissions);
        getChildren().filter(ManualReviewDetailView.class::isInstance).map(ManualReviewDetailView.class::cast)
                .forEach(ManualReviewDetailView::refreshPermissions);
    }

    private void toggle(Div target, Div other) {
        other.setVisible(false);
        target.setVisible(!target.isVisible());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        DemoRole role = DemoSession.role();
        if (role == null) {
            event.forwardTo(LoginView.class);
            return;
        }
        refreshRoleDisplay();
        refreshDemoCounts();
        String path = "/" + event.getLocation().getPath();
        if (path.equals("//")) path = "/";
        for (RouterLink link : links) {
            String href = link.getHref().replaceFirst("^/?", "/");
            boolean active = href.equals(path) || (href.equals("/section/operations") && path.startsWith("/operation/"))
                    || (href.equals("/section/contracts") && path.startsWith("/contract/"))
                    || (href.equals("/section/manual") && path.startsWith("/manual/"))
                    || ((href.equals("/section/manual") || href.equals("/section/clients"))
                    && path.startsWith(href + "/"));
            link.setClassName(active ? "nav-link active" : "nav-link");
        }
        notificationPopup.setVisible(false);
        employeePopup.setVisible(false);
    }

    @Override
    public void beforeLeave(BeforeLeaveEvent event) {
        UI ui = UI.getCurrent();
        if (ui == null) return;
        // Postpone only the router's content swap. The navbar and drawer stay mounted.
        var action = event.postpone();
        getElement().executeJs("""
                const page = this.querySelector('.app-page');
                if (!page || matchMedia('(prefers-reduced-motion: reduce)').matches) return Promise.resolve();
                let animation = page.getAnimations().find(item => item.id === 'route-exit');
                if (!animation) {
                    animation = page.animate([
                        { opacity: 1, transform: 'translateY(0)' },
                        { opacity: 0, transform: 'translateY(-5px)' }
                    ], { id: 'route-exit', duration: 75, easing: 'cubic-bezier(.4,0,1,1)', fill: 'forwards' });
                }
                return animation.finished.catch(() => {});
                """).then(ignored -> {
            if (ui.isAttached()) action.proceed();
        });
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        getElement().executeJs("this.shadowRoot?.querySelector('[part=\"content\"]')?.scrollTo(0, 0)");
    }

    public void refreshDemoCounts() {
        int pending = manualReview.pendingCount(data.state());
        manualBadge.setText(pending > 0 ? String.valueOf(pending) : "");
        manualBadge.setVisible(pending > 0);
    }
}
