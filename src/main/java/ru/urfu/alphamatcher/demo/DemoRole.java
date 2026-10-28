package ru.urfu.alphamatcher.demo;

/** Permissions label; employee identity belongs to DemoUser. */
public enum DemoRole {
    OPERATIONS("Сотрудник по операциям"),
    DECISION_MAKER("Сотрудник, принимающий решения");

    private final String label;
    DemoRole(String label) { this.label = label; }
    public String label() { return label; }
}
