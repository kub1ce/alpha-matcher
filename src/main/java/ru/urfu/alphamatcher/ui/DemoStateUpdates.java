package ru.urfu.alphamatcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.shared.Registration;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.urfu.alphamatcher.demo.DemoOperationState;

/** One subscription per attached component; batch changes render once before the response. */
final class DemoStateUpdates {
    private DemoStateUpdates() { }

    static void bind(Component component, DemoOperationState state, Runnable refresh) {
        Registration[] subscription = new Registration[1];
        component.addAttachListener(event -> {
            var ui = event.getUI();
            AtomicBoolean active = new AtomicBoolean(true);
            AtomicBoolean pending = new AtomicBoolean();
            Runnable schedule = () -> {
                if (!active.get() || !pending.compareAndSet(false, true)) return;
                try {
                    // Never wait for access while holding the state monitor. Vaadin queues access
                    // until the session lock is released, so a batch and its audit finish first.
                    ui.access(() -> {
                        if (!active.get()) return;
                        ui.beforeClientResponse(component, context -> {
                            pending.set(false);
                            if (active.get() && component.isAttached()) refresh.run();
                        });
                    });
                } catch (UIDetachedException detached) {
                    pending.set(false);
                }
            };
            Registration listener = state.onChange(schedule);
            subscription[0] = () -> {
                active.set(false);
                listener.remove();
            };
            schedule.run(); // Also catches changes made while the component was detached.
        });
        component.addDetachListener(event -> {
            if (subscription[0] != null) {
                subscription[0].remove();
                subscription[0] = null;
            }
        });
    }
}
