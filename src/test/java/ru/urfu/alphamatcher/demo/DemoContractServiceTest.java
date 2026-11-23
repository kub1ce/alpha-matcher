package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DemoContractServiceTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoContractService contracts = new DemoContractService(data);
    private final DemoOperationState state = new DemoOperationState(data.originalFixtures());

    @Test
    void searchCombinesPartyInnAndTypeAndPaginates() {
        var result = contracts.search(new DemoContractService.Query("6677889900", "Лизинг", "date", true),
                0, 8, state);
        assertEquals(1, result.total());
        assertEquals("AG-2026-081", result.rows().getFirst().id());
        assertEquals(0, contracts.search(new DemoContractService.Query("6677889900", "Поставка", "id", false),
                0, 8, state).total());
        assertEquals(1, contracts.search(new DemoContractService.Query("Петров", "", "id", false),
                0, 8, state).total());
        var first = contracts.search(new DemoContractService.Query("", "", "id", false), 0, 8, state);
        var last = contracts.search(new DemoContractService.Query("", "", "id", false), 3, 8, state);
        assertEquals(30, first.total());
        assertEquals(4, first.pages());
        assertEquals(6, last.rows().size());
        assertEquals("AG-2026-116", last.rows().getFirst().id());
        assertTrue(contracts.types().contains("Лизинг"));
    }

    @Test
    void linkedOperationsFollowConfirmedChangesInBothContracts() {
        assertEquals("PAY-2026-000192", contracts.linkedOperations("AG-2026-081", state).getFirst().id());
        assertEquals(2, contracts.linkedOperations("AG-2026-014", state).size());
        assertEquals(0, contracts.linkedOperations("AG-2026-021", state).size());
        state.link("PAY-2026-000184", "AG-2026-021", "Вручную", data.contracts(), "Проверены стороны");
        assertEquals(1, contracts.linkedOperations("AG-2026-014", state).size());
        assertEquals(1, contracts.linkedOperations("AG-2026-021", state).size());
        state.unlink("PAY-2026-000184");
        assertEquals(0, contracts.linkedOperations("AG-2026-021", state).size());
        assertEquals(1, contracts.linkedOperations("AG-2026-014", state).size());
    }

    @Test
    void contractSearchOrdersBusinessNumbersAndNeverUsesTechnicalIdsOrInnFragments() {
        var template = data.contract("AG-2026-081");
        var sample = List.of(numbered(template, "DB-9001", "101/26-А"),
                numbered(template, "DB-9002", "101/26"), numbered(template, "DB-9003", "К-9900"));
        var jdbcProjection = new DemoDataService() {
            @Override public List<Contract> contracts() { return sample; }
            @Override public String contractNumber(Contract contract) { return contract.name(); }
            @Override public Party contractParty(Contract contract, boolean payer, DemoOperationState ignored) {
                return payer && contract.id().equals("DB-9003")
                        ? data.contractParty(template, true, state) : null;
            }
        };
        var service = new DemoContractService(jdbcProjection);
        var exact = service.search(new DemoContractService.Query("  № 101 / 26  ", "", null, false), 0, 10, state);
        assertEquals(List.of("DB-9002", "DB-9001"), exact.rows().stream().map(DemoDataService.Contract::id).toList());
        var explicit = service.search(new DemoContractService.Query("101/26", "", "id", true), 0, 10, state);
        assertEquals(List.of("DB-9001", "DB-9002"), explicit.rows().stream().map(DemoDataService.Contract::id).toList());
        assertEquals(0, service.search(new DemoContractService.Query("9003", "", null, false), 0, 10, state).total());
        assertEquals(0, service.search(new DemoContractService.Query("667788", "", null, false), 0, 10, state).total());
        assertEquals(0, service.search(new DemoContractService.Query("101/26-A", "", null, false), 0, 10, state).total());
    }

    private static DemoDataService.Contract numbered(DemoDataService.Contract template, String id, String number) {
        return new DemoDataService.Contract(id, number, template.date(), template.payer(), template.recipient(),
                template.type(), template.beneficiary(), template.payerId(), template.recipientId());
    }

    @Test
    void missingContractDoesNotResolveOrGainOperations() {
        assertNull(contracts.contract("AG-NOT-FOUND"));
        assertTrue(contracts.linkedOperations("AG-NOT-FOUND", state).isEmpty());
    }
}
