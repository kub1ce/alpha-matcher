package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DemoSessionNotificationTest {
    @AfterEach
    void clearSession() { VaadinSession.setCurrent(null); }

    @Test
    void notifiesCurrentSessionOnIdentityChangeAndStopsAfterUnsubscribe() {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
        VaadinSession.setCurrent(session);

        AtomicInteger first = new AtomicInteger();
        AtomicInteger detached = new AtomicInteger();
        Runnable removeFirst = DemoSession.onUserChanged(() -> {
            assertEquals(DemoRole.OPERATIONS, DemoSession.role());
            first.incrementAndGet();
        });
        Runnable removeDetached = DemoSession.onUserChanged(detached::incrementAndGet);
        removeDetached.run();
        DemoSession.login(DemoRole.OPERATIONS);
        DemoSession.login(DemoRole.OPERATIONS);
        assertEquals(1, first.get());
        assertEquals(0, detached.get());

        removeFirst.run();
        DemoSession.login(DemoRole.DECISION_MAKER);
        assertEquals(1, first.get());
        assertEquals(0, detached.get());
    }

    @Test
    void logoutNotifiesAfterRevokingRole() {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
        VaadinSession.setCurrent(session);
        DemoSession.login(DemoRole.DECISION_MAKER);
        AtomicInteger revoked = new AtomicInteger();
        DemoSession.onUserChanged(() -> {
            assertNull(DemoSession.role());
            revoked.incrementAndGet();
        });
        DemoSession.logout();
        assertEquals(1, revoked.get());
    }
}
