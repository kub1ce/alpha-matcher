package ru.urfu.alphamatcher;

import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Inline;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.component.page.TargetElement;
import com.vaadin.flow.server.AppShellSettings;

@SpringBootApplication
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet(Lumo.UTILITY_STYLESHEET)
@StyleSheet("styles.css") // Your custom styles
@Push
public class Application implements AppShellConfigurator {

    @Override
    public void configurePage(AppShellSettings settings) {
        settings.addInlineWithContents(TargetElement.HEAD, Inline.Position.PREPEND, """
                (function () {
                  let preference = null;
                  try { preference = window.localStorage.getItem('alpha-matcher-theme'); } catch (ignored) { }
                  const dark = preference === 'dark' ? true : preference === 'light' ? false
                      : window.matchMedia('(prefers-color-scheme: dark)').matches;
                  document.documentElement.setAttribute('theme', dark ? 'dark' : 'light');
                  document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
                })();
                """, Inline.Wrapping.JAVASCRIPT);
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
