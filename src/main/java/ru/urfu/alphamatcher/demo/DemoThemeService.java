package ru.urfu.alphamatcher.demo;

import java.util.function.Consumer;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.dom.Element;

/** Browser preference for the demo UI; no theme state is kept in the Vaadin session. */
public final class DemoThemeService {
    private static final String STORAGE_KEY = "alpha-matcher-theme";

    private DemoThemeService() { }

    public static void restore(UI ui, Consumer<Boolean> onResolved) {
        ui.getPage().executeJs("""
                let stored = null;
                try { stored = window.localStorage.getItem($0); } catch (ignored) { }
                return stored === 'dark' ? true : stored === 'light' ? false
                        : window.matchMedia('(prefers-color-scheme: dark)').matches;
                """, STORAGE_KEY).then(Boolean.class, onResolved::accept);
    }

    public static void apply(UI ui, boolean dark) {
        ui.getPage().setColorScheme(dark ? ColorScheme.Value.DARK : ColorScheme.Value.LIGHT);
    }

    public static void installToggle(Element button) {
        button.executeJs("""
                if (this.__alphaThemeInstalled) return;
                this.__alphaThemeInstalled = true;
                this.addEventListener('click', () => {
                    if (this.disabled) return;
                    const root = document.documentElement;
                    const tokens = new Set((root.getAttribute('theme') || '').split(/\\s+/).filter(Boolean));
                    const dark = !tokens.has('dark');
                    if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
                        root.classList.remove('theme-changing');
                    } else if (!root.classList.contains('theme-changing')) {
                        root.classList.add('theme-changing');
                        void root.offsetWidth;
                    }
                    tokens.delete('dark');
                    tokens.delete('light');
                    tokens.add(dark ? 'dark' : 'light');
                    root.setAttribute('theme', [...tokens].join(' '));
                    try { localStorage.setItem($0, dark ? 'dark' : 'light'); } catch (ignored) { }
                    document.querySelectorAll('.theme-button').forEach(toggle => {
                        toggle.setAttribute('data-theme', dark ? 'dark' : 'light');
                        const label = dark ? 'Включить светлую тему' : 'Включить тёмную тему';
                        toggle.setAttribute('aria-label', label);
                        toggle.setAttribute('title', label);
                    });
                    clearTimeout(window.alphaThemeMotionTimer);
                    if (root.classList.contains('theme-changing')) {
                        window.alphaThemeMotionTimer = setTimeout(() => root.classList.remove('theme-changing'), 250);
                    }
                });
                """, STORAGE_KEY);
    }
}
