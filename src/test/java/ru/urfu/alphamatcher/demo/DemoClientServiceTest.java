package ru.urfu.alphamatcher.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DemoClientServiceTest {
    private final DemoDataService data = new DemoDataService();
    private final DemoClientService clients = new DemoClientService(data);
    private final DemoOperationState state = new DemoOperationState(data.fixtures());

    @Test
    void listFlagIsConsistentBetweenDirectoryAndClientRecord() {
        var page = clients.search(new DemoClientService.Query("Контрагент-03", "", "", "id", false),
                0, 10, state);
        assertEquals("CL-103", page.rows().getFirst().id());
        assertTrue(page.rows().getFirst().terroristOrExtremistListed());
        assertTrue(data.client("CL-103", state).terroristOrExtremistListed());
        assertFalse(data.client("CL-104", state).terroristOrExtremistListed());
    }

    @Test
    void directorySearchFiltersSortAndPaginationUseAllKnownParties() {
        assertTrue(data.parties().size() > 20);
        assertEquals(data.parties().size(), new HashSet<>(data.parties().stream()
                .map(DemoDataService.Party::id).toList()).size());
        var all = clients.search(new DemoClientService.Query("", "", "", "id", false), 0, 10, state);
        assertEquals(data.parties().size(), all.total());
        assertEquals(10, all.rows().size());
        assertEquals(10, clients.search(new DemoClientService.Query("", "", "", "id", false),
                1, 10, state).rows().size());
        assertEquals("CL-001", clients.search(new DemoClientService.Query("ромашка", "", "", "id", false),
                0, 10, state).rows().getFirst().id());
        assertEquals("CL-001", clients.search(new DemoClientService.Query("7701234567", "", "", "id", false),
                0, 10, state).rows().getFirst().id());
        assertEquals("CL-001", clients.search(new DemoClientService.Query("cl-001", "", "", "id", false),
                0, 10, state).rows().getFirst().id());
        var externalPeople = clients.search(new DemoClientService.Query("", "Физическое лицо",
                "external", "name", false), 0, 10, state);
        assertFalse(externalPeople.rows().isEmpty());
        assertTrue(externalPeople.rows().stream().allMatch(p -> !p.bankClient()
                && p.type().equals("Физическое лицо")));
        var descending = clients.search(new DemoClientService.Query("", "", "bank", "id", true),
                0, 10, state);
        assertTrue(descending.rows().stream().allMatch(DemoDataService.Party::bankClient));
        assertEquals("CL-007", descending.rows().getFirst().id());
    }

    @Test
    void contractAndOperationRolesUseIdsAndCurrentConfirmedMatches() {
        var romashka = clients.contracts("CL-001", state);
        assertEquals(romashka.size(), new HashSet<>(romashka.stream()
                .map(row -> row.contract().id()).toList()).size());
        assertTrue(romashka.stream().anyMatch(row -> row.contract().id().equals("AG-2026-014")
                && row.roles().equals(List.of("Получатель"))));
        assertTrue(clients.contracts("CL-011", state).isEmpty());
        assertTrue(clients.operations("CL-011", state).isEmpty());
        assertFalse(data.client("CL-008").bankClient());
        assertTrue(data.client("CL-008").pin().isBlank());

        int before = clients.operations("CL-006", state).size();
        state.link("PAY-2026-000185", "AG-2026-031", "Вручную", data.contracts(),
                "Проверены документы", DemoUser.ISAEV.fullName(), null);
        assertEquals(before + 1, clients.operations("CL-006", state).size());
        assertTrue(clients.operations("CL-006", state).stream().anyMatch(row ->
                row.operation().id().equals("PAY-2026-000185")
                        && row.roles().contains("Выгодоприобретатель")));
        state.unlink("PAY-2026-000185", DemoUser.ISAEV.fullName());
        assertEquals(before, clients.operations("CL-006", state).size());

        assertTrue(clients.contracts("CL-009", state).isEmpty());
        state.link("PAY-2026-000190", "AG-2026-014", "Вручную", data.contracts(),
                "Проверено третье лицо", DemoUser.ISAEV.fullName(), null);
        assertEquals(List.of("Связь через операцию"), clients.contracts("CL-009", state)
                .getFirst().roles());
        state.unlink("PAY-2026-000190", DemoUser.ISAEV.fullName());
        assertTrue(clients.contracts("CL-009", state).isEmpty());
    }

    @Test
    void aNewPartyInTheSharedOperationStateAppearsInTheDirectory() {
        DemoDataService.Party newcomer = new DemoDataService.Party("CL-200", "АО «Новый участник»",
                "Юридическое лицо", "1231231231", "", "", "");
        state.addOperations(List.of(new DemoDataService.Operation("PAY-2026-000500",
                LocalDateTime.of(2026, 10, 12, 10, 0), newcomer, data.client("CL-001"),
                BigDecimal.valueOf(1000), "Оплата", "", "", "", List.of())));
        assertEquals("CL-200", clients.search(new DemoClientService.Query("Новый участник", "", "",
                "id", false), 0, 10, state).rows().getFirst().id());
        assertEquals(1, clients.operations("CL-200", state).size());
        assertEquals("Плательщик", clients.operations("CL-200", state).getFirst().roles().getFirst());
    }
}
