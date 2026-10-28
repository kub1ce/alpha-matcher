package ru.urfu.alphamatcher.demo;

import java.io.Serializable;

/** The two selectable demo accounts; this is not production authentication. */
public record DemoUser(String fullName, String shortName, String initials, String email,
                       DemoRole role) implements Serializable {
    public static final DemoUser ISAEV = new DemoUser("Исаев Савелий Дмитриевич", "Исаев С.",
            "ИС", "s.isaev@bank.internal", DemoRole.OPERATIONS);
    public static final DemoUser ROZHKOV = new DemoUser("Рожков Андрей Сергеевич", "Рожков А.",
            "РА", "a.rozhkov@bank.internal", DemoRole.DECISION_MAKER);

    public static DemoUser forRole(DemoRole role) {
        return role == DemoRole.DECISION_MAKER ? ROZHKOV : ISAEV;
    }
    public String firstName() { return fullName.split(" ")[1]; }
}
