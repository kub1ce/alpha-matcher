package ru.urfu.alphamatcher.demo;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.demo.DemoDataService.Contract;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;

/** Queue and decision rules over the same confirmed matches used by operation and contract views. */
@Service
public class DemoManualReviewService {
    public record Page(List<Operation> rows, int total, int page, int pages) { }
    private final DemoDataService data;

    public DemoManualReviewService(DemoDataService data) { this.data = data; }

    public String reason(Operation operation, DemoOperationState state) {
        if (state.manualReason(operation.id()) != null) return state.manualReason(operation.id());
        return operation.reason().isBlank() && state.previousContract(operation.id()) != null
                ? "Договор отвязан сотрудником" : operation.reason();
    }

    public String explanation(Operation operation, DemoOperationState state) {
        return switch (reason(operation, state)) {
            case "Платеж за третье лицо" -> "В назначении указан другой участник. Проверьте выгодоприобретателя и основание платежа.";
            case "Недостаточно данных" -> "Не хватает реквизитов и точного номера договора для уверенного выбора.";
            case "Несколько подходящих договоров" -> "Найдено несколько возможных договоров; проверьте стороны и предмет каждого.";
            case "Конфликт ИНН плательщика" -> "ИНН отправителя не совпадает с плательщиком предложенного договора.";
            case "Неполные сведения о получателе" -> "Получатель не определён по реквизитам операции.";
            case "Договор не найден" -> "Подтверждённого подходящего договора в текущем реестре не найдено.";
            case "Договор отвязан сотрудником" -> "Ранее подтверждённая связь была удалена сотрудником.";
            default -> "Проверьте стороны, назначение платежа и договор перед подтверждением.";
        };
    }

    public List<String> reasons(DemoOperationState state) {
        return data.manualItems(state).stream().map(item -> item.reason()).distinct().sorted().toList();
    }

    public int pendingCount(DemoOperationState state) {
        return search("", "", 0, 1, state).total();
    }

    public Page search(String text, String reason, int requestedPage, int pageSize, DemoOperationState state) {
        if (pageSize < 1) throw new IllegalArgumentException("Размер страницы должен быть положительным");
        String needle = Objects.toString(text, "").trim().toLowerCase(Locale.ROOT);
        List<Operation> rows = state.all().stream().filter(op -> state.isManualQueued(op.id()))
                .filter(op -> !reason(op, state).isBlank())
                .filter(op -> reason == null || reason.isBlank() || reason.equals(reason(op, state)))
                .filter(op -> String.join(" ", op.id(), op.payer().name(), op.recipient().name(), op.purpose())
                        .toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Operation::id)).toList();
        int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * pageSize;
        return new Page(rows.subList(start, Math.min(start + pageSize, rows.size())), rows.size(), page, pages);
    }

    public List<Contract> candidates(Operation operation, DemoOperationState state) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(operation.candidates());
        if (state.previousContract(operation.id()) != null) ids.add(state.previousContract(operation.id()));
        return ids.stream().map(data::contract).filter(Objects::nonNull).toList();
    }

    public List<Contract> findContracts(String query) {
        String needle = Objects.toString(query, "").trim().toLowerCase(Locale.ROOT);
        if (needle.isBlank()) return List.of();
        return data.contracts().stream().filter(c -> (c.id() + " " + c.name() + " " + c.payer() + " " + c.recipient())
                .toLowerCase(Locale.ROOT).contains(needle)).limit(9).toList();
    }

    public List<String> warnings(Operation operation, Contract contract, DemoOperationState state) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        String reason = reason(operation, state);
        if ("Платеж за третье лицо".equals(reason)) result.add("Платёж за третье лицо: проверьте основание и выгодоприобретателя.");
        if ("Конфликт ИНН плательщика".equals(reason)) result.add("Конфликт ИНН плательщика требует проверки.");
        if (candidates(operation, state).size() > 1) result.add("Найдено несколько кандидатов. Выберите договор осознанно.");
        if (!candidates(operation, state).contains(contract)) result.add("Договор выбран вне списка предложенных кандидатов.");
        if (!contract.payer().equals(operation.payer().name()) || !contract.recipient().equals(operation.recipient().name()))
            result.add("Стороны операции и договора не совпадают.");
        return List.copyOf(result);
    }

    public void confirm(String operationId, String contractId, String justification,
                        boolean confirmed, DemoOperationState state) {
        if (!confirmed) throw new IllegalArgumentException("Требуется явное подтверждение решения");
        Operation operation = state.operation(operationId);
        if (operation == null) throw new IllegalArgumentException("Операция не найдена");
        if (state.isLinked(operationId)) throw new IllegalStateException("Операция уже привязана к договору");
        if (contractId == null || contractId.isBlank()) throw new IllegalArgumentException("Выберите договор");
        Contract contract = data.contract(contractId);
        if (contract == null) throw new IllegalArgumentException("Договор не найден");
        DemoPermissions.requireCurrentIfPresent(DemoPermissions.manualAction(operation, contract, state));
        DemoOperationState.validatedManualJustification(justification);
        state.link(operationId, contractId, "Вручную", data.contracts(), justification);
    }
}
