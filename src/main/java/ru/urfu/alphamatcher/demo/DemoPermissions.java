package ru.urfu.alphamatcher.demo;

import com.vaadin.flow.server.VaadinSession;

/** Permissions for the two demonstration users. Not a production security boundary. */
public final class DemoPermissions {
    public enum Action {
        VIEW, PREVIEW, APPLY_SAFE, QUEUE_MANUAL, MANUAL_CLEAR,
        MANUAL_COMPLEX, CHANGE_LINK, UNLINK, VIEW_RUNS, VIEW_AUDIT
    }

    private DemoPermissions() { }

    public static boolean allows(DemoUser user, Action action) {
        if (user == null) return false;
        return user.role() == DemoRole.DECISION_MAKER || switch (action) {
            case VIEW, PREVIEW, APPLY_SAFE, QUEUE_MANUAL, MANUAL_CLEAR, VIEW_RUNS -> true;
            case MANUAL_COMPLEX, CHANGE_LINK, UNLINK, VIEW_AUDIT -> false;
        };
    }

    public static boolean allowsCurrent(Action action) {
        return VaadinSession.getCurrent() != null && allows(DemoSession.user(), action);
    }

    public static void require(DemoUser user, Action action) {
        if (!allows(user, action)) throw new IllegalStateException("Недостаточно полномочий");
    }

    public static Action manualAction(DemoDataService.Operation operation,
                                      DemoDataService.Contract contract, DemoOperationState state) {
        var decision = state.analysis(operation.id());
        if (decision != null && contract.id().startsWith("DB-")) {
            boolean safe = decision.status() == ru.urfu.alphamatcher.experiment.MatchingModel.Status.SAFE
                    && contract.id().equals("DB-" + decision.proposedContractId()) && state.previousContract(operation.id()) == null;
            return safe ? Action.MANUAL_CLEAR : Action.MANUAL_COMPLEX;
        }
        boolean clear = operation.candidates().size() == 1
                && operation.candidates().contains(contract.id())
                && contract.payer().equals(operation.payer().name())
                && contract.recipient().equals(operation.recipient().name())
                && !"Конфликт ИНН плательщика".equals(operation.reason())
                && !"Несколько подходящих договоров".equals(operation.reason())
                && state.previousContract(operation.id()) == null;
        return clear ? Action.MANUAL_CLEAR : Action.MANUAL_COMPLEX;
    }

    /** Explanation for a denied manual choice; the permission decision remains in manualAction. */
    public static String manualDenialReason(DemoDataService.Operation operation,
                                            DemoDataService.Contract contract, DemoOperationState state) {
        String cause;
        if (operation.candidates().size() > 1
                || "Несколько подходящих договоров".equals(operation.reason()))
            cause = "Найдено несколько подходящих договоров.";
        else if ("Конфликт ИНН плательщика".equals(operation.reason())
                || !contract.payer().equals(operation.payer().name())
                || !contract.recipient().equals(operation.recipient().name()))
            cause = "Сведения о сторонах или ИНН противоречат выбранному договору.";
        else if (state.previousContract(operation.id()) != null)
            cause = "Ранее подтверждённая привязка была отменена.";
        else if (operation.candidates().isEmpty())
            cause = "Нет подтверждённого однозначного предложения договора.";
        else
            cause = "Выбранный договор не входит в однозначное предложение.";
        return cause + " Решение может принять только сотрудник, принимающий решения.";
    }

    public static String confirmedLinkDenialReason() {
        return "Изменить или отвязать подтверждённый договор может только сотрудник, принимающий решения.";
    }

    /** Tests and fixture setup without a Vaadin session remain independent of UI permissions. */
    public static void requireCurrentIfPresent(Action action) {
        if (VaadinSession.getCurrent() != null) require(DemoSession.user(), action);
    }

    public static String currentActorOr(String fallback) {
        return VaadinSession.getCurrent() == null ? fallback : DemoSession.user().fullName();
    }
}
