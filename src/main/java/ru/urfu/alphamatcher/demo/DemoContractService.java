package ru.urfu.alphamatcher.demo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.demo.DemoDataService.Contract;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;

/** Read-only contract projection of the current demo session's confirmed operation matches. */
@Service
public class DemoContractService {
    public record Query(String text, String type, String sort, boolean descending) { }
    public record Page(List<Contract> rows, int total, int page, int pages) { }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final DemoDataService data;

    public DemoContractService(DemoDataService data) { this.data = data; }

    public Contract contract(String id) { return data.contract(id); }

    public List<String> types() {
        return data.contracts().stream().map(Contract::type).distinct().sorted().toList();
    }

    public List<Operation> linkedOperations(String contractId, DemoOperationState state) {
        return state.all().stream()
                .filter(op -> state.match(op.id()) != null && contractId.equals(state.match(op.id()).contractId()))
                .sorted(Comparator.comparing(Operation::timestamp).reversed()).toList();
    }

    public Page search(Query query, int requestedPage, int pageSize, DemoOperationState state) {
        if (pageSize < 1) throw new IllegalArgumentException("Размер страницы должен быть положительным");
        String needle = Objects.toString(query.text(), "").trim().toLowerCase(Locale.ROOT);
        List<Contract> filtered = data.contracts().stream().filter(contract -> {
            if (query.type() != null && !query.type().isBlank() && !query.type().equals(contract.type())) return false;
            DemoDataService.Party payer = data.party(contract.payer());
            DemoDataService.Party recipient = data.party(contract.recipient());
            DemoDataService.Party beneficiary = contract.beneficiary();
            String haystack = String.join(" ", contract.id(), contract.name(), contract.payer(), contract.recipient(),
                    payer == null ? "" : payer.inn(), recipient == null ? "" : recipient.inn(),
                    beneficiary == null ? "" : beneficiary.name(), beneficiary == null ? "" : beneficiary.inn());
            return haystack.toLowerCase(Locale.ROOT).contains(needle);
        }).toList();
        Comparator<Contract> comparator = switch (Objects.toString(query.sort(), "id")) {
            case "date" -> Comparator.comparing(c -> LocalDate.parse(c.date(), DATE));
            case "subject" -> Comparator.comparing(Contract::name);
            case "payer" -> Comparator.comparing(Contract::payer);
            case "recipient" -> Comparator.comparing(Contract::recipient);
            case "operations" -> Comparator.comparingInt(c -> linkedOperations(c.id(), state).size());
            default -> Comparator.comparing(Contract::id);
        };
        List<Contract> ordered = filtered.stream().sorted(query.descending() ? comparator.reversed() : comparator).toList();
        int pages = Math.max(1, (ordered.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * pageSize;
        return new Page(ordered.subList(start, Math.min(start + pageSize, ordered.size())), ordered.size(), page, pages);
    }
}
