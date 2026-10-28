package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import ru.urfu.alphamatcher.demo.DemoThemeService;

/** Shared theme control for the login page and application header. */
public final class ThemeToggle extends Button {
    private final Html moonIcon = new Html("<svg class='toolbar-svg theme-svg theme-moon' xmlns='http://www.w3.org/2000/svg' "
            + "viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' "
            + "stroke-linecap='round' stroke-linejoin='round' aria-hidden='true'>"
            + "<path d='M20.5 14.3A8.8 8.8 0 0 1 9.7 3.5a8.8 8.8 0 1 0 10.8 10.8Z'/></svg>");
    private final Html sunIcon = new Html("<svg class='toolbar-svg theme-svg theme-sun' xmlns='http://www.w3.org/2000/svg' "
            + "viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' "
            + "stroke-linecap='round' aria-hidden='true'>"
            + "<circle cx='12' cy='12' r='4'/><path d='M12 2v2M12 20v2M4.93 4.93l1.42 1.42"
            + "M17.65 17.65l1.42 1.42M2 12h2M20 12h2M4.93 19.07l1.42-1.42"
            + "M17.65 6.35l1.42-1.42'/></svg>");
    public ThemeToggle() {
        setClassName("theme-button");
        getElement().appendChild(moonIcon.getElement(), sunIcon.getElement());
        setEnabled(false);
        addAttachListener(event -> DemoThemeService.restore(event.getUI(), restored -> {
            if (!isAttached() || getUI().orElse(null) != event.getUI()) return;
            DemoThemeService.apply(event.getUI(), restored);
            showTheme(restored);
            setEnabled(true);
            DemoThemeService.installToggle(getElement());
        }));
        showTheme(false);
    }

    private void showTheme(boolean selectedDark) {
        getElement().setAttribute("data-theme", selectedDark ? "dark" : "light");
        String label = selectedDark ? "Включить светлую тему" : "Включить тёмную тему";
        getElement().setAttribute("aria-label", label);
        getElement().setAttribute("title", label);
    }
}
