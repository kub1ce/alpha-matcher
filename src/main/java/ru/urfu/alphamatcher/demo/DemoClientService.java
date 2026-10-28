package ru.urfu.alphamatcher.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.demo.DemoDataService.Contract;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;
import ru.urfu.alphamatcher.demo.DemoDataService.Party;

/** Read-only client directory derived from the shared session state and explicit party IDs. */
@Service
public class DemoClientService {
    public record Query(String search, String type, String affiliation, String sort, boolean descending) { }
    public record Page(List<Party> rows, int total, int page, int pages) { }
    public record RelatedContract(Contract contract, List<String> roles) { }
    public record RelatedOperation(Operation operation, List<String> roles) { }

    private final DemoDataService data;

    public DemoClientService(DemoDataService data) { this.data = data; }

    public List<String> types(DemoOperationState state) {
        return data.parties(state).stream().map(Party::type).distinct().sorted().toList();
    }

    public Page search(Query query, int requestedPage, int pageSize, DemoOperationState state) {
        String needle = normalize(query.search());
        Comparator<Party> comparator = switch (query.sort()) {
            case "name" -> Comparator.comparing(Party::name);
            case "type" -> Comparator.comparing(Party::type).thenComparing(Party::id);
            case "inn" -> Comparator.comparing(Party::inn).thenComparing(Party::id);
            case "bank" -> Comparator.comparing(Party::bankClient).thenComparing(Party::id);
            case "pin" -> Comparator.comparing(Party::pin).thenComparing(Party::id);
            case "contracts" -> Comparator.comparingInt((Party p) -> contracts(p.id(), state).size())
                    .thenComparing(Party::id);
            default -> Comparator.comparing(Party::id);
        };
        if (query.descending()) comparator = comparator.reversed();
        List<Party> filtered = data.parties(state).stream()
                .filter(p -> normalize(p.id() + " " + p.name() + " " + p.inn()).contains(needle))
                .filter(p -> query.type() == null || query.type().isBlank() || p.type().equals(query.type()))
                .filter(p -> switch (query.affiliation() == null ? "" : query.affiliation()) {
                    case "bank" -> p.bankClient();
                    case "external" -> !p.bankClient();
                    default -> true;
                }).sorted(comparator).toList();
        int pages = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        return new Page(filtered.subList(Math.min(page * pageSize, filtered.size()),
                Math.min((page + 1) * pageSize, filtered.size())), filtered.size(), page, pages);
    }

    public List<RelatedContract> contracts(String clientId, DemoOperationState state) {
        Map<String, RelatedContract> related = new LinkedHashMap<>();
        for (Contract contract : data.contracts()) {
            List<String> roles = contractRoles(contract, clientId);
            if (!roles.isEmpty()) related.put(contract.id(), new RelatedContract(contract, roles));
        }
        for (Operation operation : state.all()) {
            if (!clientId.equals(operation.payer().id()) && !clientId.equals(operation.recipient().id())) continue;
            DemoOperationState.Match match = state.match(operation.id());
            if (match == null || related.containsKey(match.contractId())) continue;
            Contract contract = data.contract(match.contractId());
            if (contract != null) related.put(contract.id(),
                    new RelatedContract(contract, List.of("Связь через операцию")));
        }
        return List.copyOf(related.values());
    }

    public List<RelatedOperation> operations(String clientId, DemoOperationState state) {
        List<RelatedOperation> related = new ArrayList<>();
        for (Operation operation : state.all()) {
            List<String> roles = new ArrayList<>();
            if (clientId.equals(operation.payer().id())) roles.add("Плательщик");
            if (clientId.equals(operation.recipient().id())) roles.add("Получатель");
            DemoOperationState.Match match = state.match(operation.id());
            Contract contract = match == null ? null : data.contract(match.contractId());
            if (contract != null && contract.beneficiary() != null
                    && clientId.equals(contract.beneficiary().id())) roles.add("Выгодоприобретатель");
            if (!roles.isEmpty()) related.add(new RelatedOperation(operation, List.copyOf(roles)));
        }
        related.sort(Comparator.comparing((RelatedOperation row) -> row.operation().timestamp()).reversed()
                .thenComparing(row -> row.operation().id()));
        return List.copyOf(related);
    }

    public List<String> contractRoles(Contract contract, String clientId) {
        List<String> roles = new ArrayList<>();
        if (clientId.equals(contract.payerId())) roles.add("Плательщик");
        if (clientId.equals(contract.recipientId())) roles.add("Получатель");
        if (contract.beneficiary() != null && clientId.equals(contract.beneficiary().id()))
            roles.add("Выгодоприобретатель");
        return List.copyOf(roles);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
