package ru.urfu.alphamatcher.demo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.demo.DemoDataService.Contract;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;

/** Read-only contract projection of the current demo session's confirmed operation matches. */
@Service
public class DemoContractService {
    public record Query(String text, String type, String sort, boolean descending) { }
    public record Page(List<Contract> rows, int total, int page, int pages) { }
    public record Hit(Contract contract, Match match) { }
    public enum Match {
        EXACT_NUMBER("Точный номер договора"), NUMBER_PREFIX("Начало номера договора"),
        NUMBER_PART("Часть номера договора"), INN("ИНН стороны"), PARTY("Название стороны"),
        ALL("");

        private final String label;
        Match(String label) { this.label = label; }
        public String label() { return label; }
    }

    private static final Pattern SPACES = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern SEPARATOR_SPACES = Pattern.compile(" *([/-]) *");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final DemoDataService data;

    public DemoContractService(DemoDataService data) { this.data = data; }

    public Contract contract(String id) { return data.contract(id); }
    public String number(Contract contract) { return data.contractNumber(contract); }

    public List<String> types() {
        return data.contracts().stream().map(Contract::type).distinct().sorted().toList();
    }

    public List<Operation> linkedOperations(String contractId, DemoOperationState state) {
        return state.all().stream()
                .filter(op -> state.match(op.id()) != null && contractId.equals(state.match(op.id()).contractId()))
                .sorted(Comparator.comparing(Operation::timestamp).reversed()).toList();
    }

    public int linkedOperationCount(String contractId, DemoOperationState state) {
        return data.linkedCount(contractId,state);
    }

    public DemoOperationState.Page linkedOperationPage(String contractId, DemoOperationState state,
                                                       int requested, int size) {
        return data.linkedOperations(contractId,state,requested,size);
    }

    public Page search(Query query, int requestedPage, int pageSize, DemoOperationState state) {
        if (pageSize < 1) throw new IllegalArgumentException("Размер страницы должен быть положительным");
        List<Hit> filtered = hits(data.contracts().stream()
                .filter(contract -> query.type() == null || query.type().isBlank()
                        || query.type().equals(contract.type())), query.text(), state);
        Comparator<Contract> comparator = switch (Objects.toString(query.sort(), "id")) {
            case "date" -> Comparator.comparing(c -> contractDate(c.date()),
                    Comparator.nullsLast(query.descending() ? Comparator.<LocalDate>reverseOrder() : Comparator.<LocalDate>naturalOrder()));
            case "subject" -> Comparator.comparing(data::contractDescription);
            case "payer" -> Comparator.comparing(Contract::payer);
            case "recipient" -> Comparator.comparing(Contract::recipient);
            case "operations" -> {
                var counts=new java.util.HashMap<String,Integer>();
                yield Comparator.comparingInt(c -> counts.computeIfAbsent(c.id(),key -> linkedOperationCount(key,state)));
            }
            default -> Comparator.comparing(this::number);
        };
        Comparator<Contract> direction = query.descending() && !"date".equals(query.sort())
                ? comparator.reversed() : comparator;
        Comparator<Hit> order = Comparator.comparing(Hit::contract, direction.thenComparing(Contract::id));
        if (query.sort() == null) order = Comparator.comparing(Hit::match).thenComparing(order);
        List<Contract> ordered = filtered.stream().sorted(order).map(Hit::contract).toList();
        int pages = Math.max(1, (ordered.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * pageSize;
        return new Page(ordered.subList(start, Math.min(start + pageSize, ordered.size())), ordered.size(), page, pages);
    }

    List<Hit> ranked(Stream<Contract> contracts, String text, DemoOperationState state) {
        return hits(contracts, text, state).stream().sorted(Comparator.comparing(Hit::match)
                .thenComparing(hit -> number(hit.contract())).thenComparing(hit -> hit.contract().id())).toList();
    }

    public Match match(Contract contract, String text, DemoOperationState state) {
        return match(contract, new Search(text), state);
    }

    private List<Hit> hits(Stream<Contract> contracts, String text, DemoOperationState state) {
        Search search = new Search(text);
        return contracts.map(contract -> new Hit(contract, match(contract, search, state)))
                .filter(hit -> hit.match() != null).toList();
    }

    private Match match(Contract contract, Search search, DemoOperationState state) {
        if (search.text.isEmpty()) return Match.ALL;
        if (search.number.isEmpty()) return null;
        String number = normalizedNumber(normalizedText(number(contract)));
        if (number.equals(search.number)) return Match.EXACT_NUMBER;
        if (search.inn) {
            var payer = data.contractParty(contract, true, state);
            var recipient = data.contractParty(contract, false, state);
            return Stream.of(payer, recipient, contract.beneficiary()).filter(Objects::nonNull)
                    .anyMatch(p -> search.text.equals(p.inn())) ? Match.INN : null;
        }
        if (number.startsWith(search.number)) return Match.NUMBER_PREFIX;
        if (number.contains(search.number)) return Match.NUMBER_PART;
        if (search.numberOnly) return null;
        var beneficiary = contract.beneficiary();
        return Stream.of(contract.payer(), contract.recipient(),
                        beneficiary == null ? "" : beneficiary.name())
                .anyMatch(name -> normalizedText(name).contains(search.text)) ? Match.PARTY : null;
    }

    static String normalizedText(String value) {
        return SPACES.matcher(Objects.toString(value, "").toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static String normalizedNumber(String value) {
        return SEPARATOR_SPACES.matcher(value.startsWith("№") ? value.substring(1).trim() : value)
                .replaceAll("$1");
    }

    private static final class Search {
        private final String text;
        private final String number;
        private final boolean inn;
        private final boolean numberOnly;

        private Search(String input) {
            text = normalizedText(input);
            number = normalizedNumber(text);
            boolean digits = DIGITS.matcher(text).matches();
            inn = digits && (text.length() == 10 || text.length() == 12);
            numberOnly = digits || text.startsWith("№") || number.contains("/");
        }
    }

    private static LocalDate contractDate(String value) {
        if (value == null || value.isBlank()) return null;
        return value.indexOf('.') >= 0 ? LocalDate.parse(value, DATE) : LocalDate.parse(value);
    }
}
