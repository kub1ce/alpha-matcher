package ru.urfu.alphamatcher.demo;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import com.vaadin.flow.server.VaadinSession;

@Service
public class DemoDataService {
    public DemoOperationState.Page linkedOperations(String contractId, DemoOperationState state, int requested, int size) {
        return operationPage(state.all().stream().filter(op -> {
            var match=state.match(op.id());
            return match!=null && contractId.equals(match.contractId());
        }).sorted(Comparator.comparing(Operation::timestamp).reversed()).toList(),requested,size);
    }

    public int linkedCount(String contractId, DemoOperationState state) {
        return Math.toIntExact(state.confirmedMatches().values().stream()
                .filter(match -> contractId.equals(match.contractId())).count());
    }

    public DemoOperationState.Page clientOperations(String clientId, Set<String> beneficiaryContracts,
                                                     DemoOperationState state, int requested, int size) {
        return operationPage(state.all().stream().filter(op -> {
            if (clientId.equals(op.payer().id()) || clientId.equals(op.recipient().id())) return true;
            var match=state.match(op.id());
            return match!=null && beneficiaryContracts.contains(match.contractId());
        }).sorted(Comparator.comparing(Operation::timestamp).reversed().thenComparing(Operation::id)).toList(),
                requested,size);
    }

    public List<String> clientOperationContracts(String clientId, DemoOperationState state) {
        return state.all().stream().filter(op -> clientId.equals(op.payer().id()) || clientId.equals(op.recipient().id()))
                .map(op -> state.match(op.id())).filter(java.util.Objects::nonNull)
                .map(DemoOperationState.Match::contractId).distinct().toList();
    }

    private static DemoOperationState.Page operationPage(List<Operation> operations, int requested, int size) {
        int pages=Math.max(1,Math.ceilDiv(operations.size(),size));
        int page=Math.min(Math.max(0,requested),pages-1);
        int start=page*size;
        return new DemoOperationState.Page(List.copyOf(operations.subList(start,
                (int)Math.min(operations.size(),start+(long)size))),operations.size(),page,pages);
    }

    public record Party(String id, String name, String type, String inn, String account, String bic,
                        String pin, boolean bankClient, boolean terroristOrExtremistListed) implements java.io.Serializable {
        public Party(String id, String name, String type, String inn, String account, String bic, String pin) {
            this(id, name, type, inn, account, bic, pin, false);
        }
        public Party(String id, String name, String type, String inn, String account, String bic,
                     String pin, boolean bankClient) {
            this(id, name, type, inn, account, bic, pin, bankClient, false);
        }
    }
    public record Contract(String id, String name, String date, String payer, String recipient,
                           String type, Party beneficiary, String payerId, String recipientId)
            implements java.io.Serializable {
        public Contract(String id, String name, String date, String payer, String recipient,
                        String type, Party beneficiary) {
            this(id, name, date, payer, recipient, type, beneficiary, "", "");
        }
    }
    public record Operation(String id, LocalDateTime timestamp, Party payer, Party recipient,
                            BigDecimal amount, String purpose, String initialContract, String initialMethod,
                            String reason, List<String> candidates, String initialJustification,
                            String initialActor, LocalDateTime initialDecisionAt, String sourceVersion) implements java.io.Serializable {
        public Operation(String id, LocalDateTime timestamp, Party payer, Party recipient,
                         BigDecimal amount, String purpose, String initialContract, String initialMethod,
                         String reason, List<String> candidates, String initialJustification,
                         String initialActor, LocalDateTime initialDecisionAt) {
            this(id, timestamp, payer, recipient, amount, purpose, initialContract, initialMethod,
                    reason, candidates, initialJustification, initialActor, initialDecisionAt, "");
        }
        public Operation(String id, LocalDateTime timestamp, Party payer, Party recipient,
                         BigDecimal amount, String purpose, String initialContract, String initialMethod,
                         String reason, List<String> candidates) {
            this(id, timestamp, payer, recipient, amount, purpose, initialContract, initialMethod,
                    reason, candidates, "", "", null);
        }
        public String date() { return timestamp.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")); }
        public String amountText() {
            BigDecimal displayed = amount.stripTrailingZeros();
            NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
            format.setMaximumFractionDigits(Math.max(0, displayed.scale()));
            return format.format(displayed) + " ₽";
        }
    }
    public record ManualItem(String operationId, String payer, String amount, String reason) { }
    public record Run(String id, String date, int total, int matched, int manual) { }

    private static final Party ROMASHKA = new Party("CL-001", "ООО «Ромашка»", "Юридическое лицо", "7701234567", "40702810900000019902", "044525593", "PIN-7701-01", true);
    private static final Party VECTOR = new Party("CL-002", "ООО «Вектор»", "Юридическое лицо", "6678123456", "40702810400000018421", "046577964", "PIN-6678-02", true);
    private static final Party TECHPROM = new Party("CL-008", "АО «ТехПром»", "Юридическое лицо", "7709988776", "40702810100000088991", "044525225", "");
    private static final Party STROY = new Party("CL-003", "ООО «СтройРегион»", "Юридическое лицо", "6677889900", "", "", "", true);
    private static final Party URAL = new Party("CL-005", "ООО «УралСнаб»", "Юридическое лицо", "6678901234", "", "", "");
    private static final Party PETROV = new Party("CL-004", "Петров Петр Сергеевич", "Физическое лицо", "781234567890", "", "", "", true);
    private static final Party IVANOV = new Party("CL-006", "ИП Иванов Иван Иванович", "Индивидуальный предприниматель", "123456789012", "", "", "", true);
    private static final Party VECTOR_GROUP = new Party("CL-009", "ООО «Вектор Групп»", "Юридическое лицо", "7705112233", "", "", "");
    private static final Party UNKNOWN = new Party("", "Не определено", "Не указано", "", "", "", "");
    private static final Party SEVER = new Party("CL-010", "ООО «Север»", "Юридическое лицо", "", "", "", "");
    private static final Party SIDOROVA = new Party("CL-007", "Сидорова Анна Олеговна", "Физическое лицо", "", "", "", "", true);
    private static final Party KONTUR = new Party("CL-011", "АО «Контур»", "Юридическое лицо", "", "", "", "");
    private static final List<Party> DEMO_PARTIES = createDemoParties();
    private static final List<Contract> ORIGINAL_CONTRACTS = List.of(
            contract("AG-2026-014", "Договор поставки промышленной продукции", "15.01.2026", VECTOR, ROMASHKA, "Поставка", null),
            contract("AG-2026-021", "Дополнительный договор поставки комплектующих", "26.01.2026", VECTOR, ROMASHKA, "Поставка", null),
            contract("AG-2026-031", "Договор генподряда и строительно-монтажных работ", "02.03.2026", STROY, ROMASHKA, "Подряд", IVANOV),
            contract("AG-2026-044", "Договор комплексного сервисного обслуживания", "14.04.2026", ROMASHKA, TECHPROM, "Услуги", null),
            contract("AG-2026-045", "Договор аренды складских мощностей и оборудования", "20.04.2026", ROMASHKA, URAL, "Аренда", null),
            contract("AG-2026-052", "Договор оптовой дистрибуции материалов", "12.05.2026", VECTOR, URAL, "Поставка", null),
            contract("AG-2026-061", "Соглашение целевого финансирования и инвестиционных работ", "05.06.2026", VECTOR, STROY, "Финансирование", null),
            contract("AG-2026-072", "Договор коммерческого факторинга поставок", "18.07.2026", IVANOV, ROMASHKA, "Факторинг", null),
            contract("AG-2026-081", "Договор финансовой аренды (лизинга) спецтехники", "25.08.2026", STROY, TECHPROM, "Лизинг", PETROV));
    private static final List<Contract> CONTRACTS = createContracts();
    private static final List<Party> PARTIES = createParties();
    private static final List<Operation> ORIGINAL_FIXTURES = List.of(
            op(184, 12, 10, 42, VECTOR, ROMASHKA, 125000, "Оплата по договору AG-2026-014 от 15.01.2026 за поставку комплектующих. В том числе НДС 20% 20833.33 руб.", "AG-2026-014", "Автоматически", "", List.of()),
            op(185, 12, 11, 17, STROY, ROMASHKA, 50000, "Оплата за Иванова Ивана Ивановича по договору AG-2026-031", "", "", "Платеж за третье лицо", List.of("AG-2026-031")),
            op(186, 12, 12, 4, UNKNOWN, ROMASHKA, 27500, "Перевод средств через терминал", "", "", "Недостаточно данных", List.of()),
            op(187, 12, 13, 1, ROMASHKA, TECHPROM, 89500, "Платеж по соглашению о техническом содействии и регламентных работах за 3 кв. 2026 г.", "", "", "Несколько подходящих договоров", List.of("AG-2026-044", "AG-2026-081")),
            op(188, 11, 14, 15, VECTOR, URAL, 34000, "Оплата партии строительных материалов", "AG-2026-052", "Автоматически", "", List.of()),
            op(189, 10, 9, 30, VECTOR, STROY, 780000, "Транш инвестиционного финансирования", "AG-2026-061", "Автоматически", "", List.of()),
            op(190, 10, 16, 22, VECTOR_GROUP, ROMASHKA, 12500, "Оплата по счету № 112 за консультационные услуги", "", "", "Конфликт ИНН плательщика", List.of("AG-2026-014")),
            op(191, 9, 11, 5, SEVER, UNKNOWN, 91000, "Перечисление гарантийного обеспечения", "", "", "Неполные сведения о получателе", List.of()),
            op(192, 8, 15, 40, STROY, TECHPROM, 166000, "Оплата по договору финансовой аренды AG-2026-081", "AG-2026-081", "Вручную", "", List.of()),
            op(193, 7, 10, 20, VECTOR, ROMASHKA, 145000, "Оплата по договору AG-2026-014", "AG-2026-014", "Автоматически", "", List.of()),
            op(194, 6, 12, 10, VECTOR, URAL, 63000, "Оплата по договору AG-2026-052", "AG-2026-052", "Автоматически", "", List.of()),
            op(195, 5, 13, 50, SIDOROVA, ROMASHKA, 19800, "Оплата услуг по договору, отсутствующему в реестре", "", "", "Договор не найден", List.of()));
    private static final List<Operation> FIXTURES = createFixtures();
    private static final String SESSION_KEY = "demo-operations";

    private static Operation op(int suffix, int day, int hour, int minute, Party payer, Party recipient,
                                long amount, String purpose, String contract, String method, String reason,
                                List<String> candidates) {
        return new Operation("PAY-2026-%06d".formatted(suffix), LocalDateTime.of(2026, 10, day, hour, minute),
                payer, recipient, BigDecimal.valueOf(amount), purpose, contract, method, reason, candidates);
    }

    private static Contract contract(String id, String name, String date, Party payer, Party recipient,
                                     String type, Party beneficiary) {
        return new Contract(id, name, date, payer.name(), recipient.name(), type, beneficiary,
                payer.id(), recipient.id());
    }

    private static List<Party> createDemoParties() {
        List<Party> result = new ArrayList<>();
        for (int i = 1; i <= 16; i++) result.add(new Party("CL-%03d".formatted(100 + i),
                "ООО «Контрагент-%02d»".formatted(i), "Юридическое лицо",
                "9900%06d".formatted(i), "", "", "", false, i == 3 || i == 11));
        return List.copyOf(result);
    }

    private static List<Party> createParties() {
        List<Party> result = new ArrayList<>(List.of(ROMASHKA, VECTOR, TECHPROM, STROY, URAL,
                PETROV, IVANOV, VECTOR_GROUP, SEVER, SIDOROVA, KONTUR));
        result.addAll(DEMO_PARTIES);
        for (Contract contract : CONTRACTS) if (contract.beneficiary() != null
                && !contract.beneficiary().id().isBlank()
                && result.stream().noneMatch(p -> p.id().equals(contract.beneficiary().id())))
            result.add(contract.beneficiary());
        return List.copyOf(result);
    }

    private static List<Contract> createContracts() {
        List<Contract> result = new ArrayList<>(ORIGINAL_CONTRACTS);
        String[] types = {"Поставка", "Подряд", "Услуги", "Аренда", "Лизинг", "Финансирование"};
        for (int i = 0; i < 21; i++) {
            Party payer = DEMO_PARTIES.get(i % DEMO_PARTIES.size());
            Party recipient = DEMO_PARTIES.get((i + 1) % DEMO_PARTIES.size());
            String type = types[i % types.length];
            Party beneficiary = i % 6 == 0 ? new Party("CL-%03d".formatted(117 + i / 6), "Тестовый выгодоприобретатель №" + (i + 1),
                    "Физическое лицо", "000000%06d".formatted(i + 1), "", "", "") : null;
            result.add(contract("AG-2026-%03d".formatted(101 + i),
                    "Договор: " + type.toLowerCase() + " №" + (i + 1),
                    "%02d.08.2026".formatted(1 + i), payer, recipient, type, beneficiary));
        }
        return List.copyOf(result);
    }

    private static List<Operation> createFixtures() {
        List<Operation> result = new ArrayList<>(ORIGINAL_FIXTURES);
        for (int i = 0; i < 84; i++) {
            int suffix = 202 + i;
            int day = 1 + i % 28;
            Contract contract = CONTRACTS.get(9 + i % 21);
            Party payer = partyFromDemo(contract.payer());
            Party recipient = partyFromDemo(contract.recipient());
            String id = "PAY-2026-%06d".formatted(suffix);
            LocalDateTime time = LocalDateTime.of(2026, 9, day, 9 + i % 9, i * 7 % 60);
            BigDecimal amount = BigDecimal.valueOf(12_000L + i * 3_775L);
            String purpose = "Тестовый платёж по договору " + contract.id() + " за " + contract.type().toLowerCase();
            Operation operation = switch (i % 9) {
                case 0 -> new Operation(id, time, payer, recipient, amount, purpose,
                        contract.id(), "Автоматически", "", List.of(contract.id()));
                case 1 -> new Operation(id, time, payer, recipient, amount, purpose,
                        "", "", "", List.of(contract.id()));
                case 2 -> new Operation(id, time, payer, recipient, amount, purpose,
                        contract.id(), "Вручную", "Требовалась проверка назначения платежа",
                        List.of(contract.id()), "Проверены реквизиты и документы по тестовому платежу",
                        i % 2 == 0 ? DemoUser.ISAEV.fullName() : DemoUser.ROZHKOV.fullName(),
                        time.plusHours(2));
                case 3 -> new Operation(id, time, payer, recipient, amount,
                        "Тестовый платёж за третье лицо по договору " + contract.id(),
                        "", "", "Платеж за третье лицо", List.of(contract.id()));
                case 4 -> {
                    Contract first = CONTRACTS.get(9 + i % 5);
                    Contract second = CONTRACTS.get(9 + 16 + i % 5);
                    yield new Operation(id, time, partyFromDemo(first.payer()),
                            partyFromDemo(first.recipient()), amount, "Тестовая оплата по нескольким договорам",
                            "", "", "Несколько подходящих договоров", List.of(first.id(), second.id()));
                }
                case 5 -> new Operation(id, time,
                        new Party(payer.id(), payer.name(), payer.type(), "0000000000", "", "", ""),
                        recipient, amount, purpose, "", "", "Конфликт ИНН плательщика",
                        List.of(contract.id()));
                case 6 -> new Operation(id, time, UNKNOWN, recipient, amount,
                        "Тестовый перевод без реквизитов плательщика", "", "",
                        "Недостаточно данных", List.of());
                case 7 -> new Operation(id, time, payer, recipient, amount,
                        "Тестовая оплата без договора в реестре", "", "", "Договор не найден", List.of());
                default -> new Operation(id, time, payer, recipient, amount,
                        "Тестовый авансовый платёж без номера договора", "", "", "", List.of());
            };
            result.add(operation);
        }
        return List.copyOf(result);
    }

    private static Party partyFromDemo(String name) {
        return DEMO_PARTIES.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    public ru.urfu.alphamatcher.experiment.MatchingModel.Decision analyze(String operationReference) {
        throw new IllegalStateException("Полный анализ недоступен: включите профиль jdbc-demo с изолированной тестовой MySQL. Проверка парсинга работает без БД.");
    }
    public ru.urfu.alphamatcher.experiment.MatchingModel.Decision analyze(Operation operation) { return analyze(operation.id()); }
    public ru.urfu.alphamatcher.experiment.MatchingModel.Decision reanalyze(Operation operation) { return analyze(operation); }
    public <T> T confirmAtomically(DemoOperationState state, java.util.function.Supplier<T> action) { return state.atomically(action); }
    public <T> T readMatchingSnapshot(java.util.function.Supplier<T> action) { return action.get(); }
    public void requireCurrentOperation(Operation shown) { }
    public String contractReference(long databaseId) { return "DB-" + databaseId; }
    public boolean hasMatchingSource() { return false; }
    public String operationStatus(String reference) { return "LOADED"; }
    public String sourceLabel() { return "Тестовая среда"; }

    public DemoOperationState state() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) return new DemoOperationState(FIXTURES);
        DemoOperationState state = (DemoOperationState) session.getAttribute(SESSION_KEY);
        if (state == null) {
            state = new DemoOperationState(FIXTURES);
            session.setAttribute(SESSION_KEY, state);
        } else state.mergeSeedOperations(FIXTURES);
        return state;
    }
    public List<Operation> fixtures() { return FIXTURES; }
    List<Operation> originalFixtures() { return ORIGINAL_FIXTURES; }
    public List<Contract> contracts() { return CONTRACTS; }
    public Contract contract(String id) { return CONTRACTS.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null); }
    public String contractNumber(Contract contract) { return contract.id(); }
    public String contractDescription(Contract contract) { return contract.name(); }
    public Party contractParty(Contract contract, boolean payer, DemoOperationState state) {
        return client(payer ? contract.payerId() : contract.recipientId(), state);
    }
    public Party party(String name) {
        return PARTIES.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }
    public Party client(String id) {
        return PARTIES.stream()
                .filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
    public Party client(String id, DemoOperationState state) {
        return parties(state).stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
    public List<Party> parties() { return PARTIES; }
    public List<Party> parties(DemoOperationState state) {
        java.util.Map<String, Party> byId = new java.util.LinkedHashMap<>();
        for (Party party : PARTIES) byId.put(party.id(), party);
        for (Operation operation : state.all()) {
            if (!operation.payer().id().isBlank()) byId.putIfAbsent(operation.payer().id(), operation.payer());
            if (!operation.recipient().id().isBlank()) byId.putIfAbsent(operation.recipient().id(), operation.recipient());
        }
        return List.copyOf(byId.values());
    }
    public List<Operation> operations() { return state().all().stream()
            .sorted(java.util.Comparator.comparing(Operation::timestamp).reversed()).limit(6).toList(); }
    public List<ManualItem> manualItems() { return manualItems(state()); }
    public List<ManualItem> manualItems(DemoOperationState state) {
        return manualItems(state, state.queuedOperations());
    }
    public List<ManualItem> recentManualItems(DemoOperationState state, int limit) {
        return manualItems(state, state.queuedOperations(limit));
    }
    private List<ManualItem> manualItems(DemoOperationState state, List<Operation> queued) {
        List<ManualItem> items = new ArrayList<>();
        for (Operation op : queued) {
            if (state.isManualQueued(op.id())) {
                String reason = state.manualReason(op.id()) != null ? state.manualReason(op.id())
                        : op.reason().isBlank() ? "Договор отвязан сотрудником" : op.reason();
                items.add(new ManualItem(op.id(), op.payer().name(), op.amountText(), reason));
            }
        }
        return items;
    }
    public List<Run> runs() {
        List<Run> result = new ArrayList<>();
        for (DemoOperationState.BatchRun run : state().batchRuns().reversed())
            result.add(new Run(run.id(), run.time().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                    run.selected(), run.applied(), run.manual()));
        return List.copyOf(result);
    }
    /** Explicitly loaded stage-5 demo batch; never replaces existing session operations. */
    public List<Operation> matchingBatch() {
        return List.of(
                op(196, 12, 14, 15, VECTOR, ROMASHKA, 72000,
                        "Оплата по договору AG-2026-014 за поставку промышленной продукции", "", "", "", List.of("AG-2026-014")),
                op(197, 12, 15, 20, VECTOR, URAL, 46500,
                        "Оплата по договору AG-2026-052 за поставку материалов", "", "", "", List.of("AG-2026-052")),
                op(198, 12, 16, 5, STROY, ROMASHKA, 58000,
                        "Оплата за третье лицо по договору AG-2026-031", "", "", "Платеж за третье лицо", List.of("AG-2026-031")),
                op(199, 12, 17, 30, ROMASHKA, TECHPROM, 94000,
                        "Оплата технических и сервисных работ", "", "", "Несколько подходящих договоров",
                        List.of("AG-2026-044", "AG-2026-081")),
                op(200, 12, 18, 40, UNKNOWN, ROMASHKA, 21500,
                        "Перевод через терминал без номера договора", "", "", "Недостаточно данных", List.of()),
                op(201, 12, 19, 10, SIDOROVA, ROMASHKA, 33000,
                        "Оплата услуг по неизвестному договору", "", "", "Договор не найден", List.of())
        );
    }
    public static void clearSession() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) session.setAttribute(SESSION_KEY, null);
    }
}
