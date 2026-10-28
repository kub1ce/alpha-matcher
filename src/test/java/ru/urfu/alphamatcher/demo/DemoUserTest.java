package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DemoUserTest {
    @Test
    void selectableAccountsHaveDistinctIdentityAndRole() {
        assertEquals("Исаев Савелий Дмитриевич", DemoUser.forRole(DemoRole.OPERATIONS).fullName());
        assertEquals("Савелий", DemoUser.ISAEV.firstName());
        assertEquals("Рожков Андрей Сергеевич", DemoUser.forRole(DemoRole.DECISION_MAKER).fullName());
        assertEquals("Андрей", DemoUser.ROZHKOV.firstName());
        assertEquals("a.rozhkov@bank.internal", DemoUser.ROZHKOV.email());
        assertEquals(DemoRole.DECISION_MAKER, DemoUser.ROZHKOV.role());
    }
}
