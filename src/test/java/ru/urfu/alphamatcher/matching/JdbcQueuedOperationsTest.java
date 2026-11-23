package ru.urfu.alphamatcher.matching;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.urfu.alphamatcher.demo.DemoDataService.Operation;
import ru.urfu.alphamatcher.demo.DemoOperationState.EmployeeSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "ALPHA_FINALIZATION_OWNED", matches = "true")
class JdbcQueuedOperationsTest {
    @ParameterizedTest
    @ValueSource(strings = {"utf8mb4_0900_ai_ci", "utf8mb4_unicode_ci"})
    void readsPersistedQueueWithEitherConnectionCollationWithoutAcceptingAliases(String collation) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:33368/alpha_matcher_ui_demo"
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionCollation=" + collation;
        String password = System.getenv("ALPHA_DEMO_PASSWORD");
        var data = new JdbcDemoDataService(url, "ui_demo", password, System.getenv("ALPHA_DEMO_OWNER"));
        try (var connection = DriverManager.getConnection(url, "ui_demo", password);
             var statement = connection.createStatement();
             var row = statement.executeQuery("""
                     SELECT COLLATION(reference), @@collation_connection
                     FROM operation WHERE reference='DEMO-0002'
                     """)) {
            assertTrue(row.next());
            assertEquals("utf8mb4_unicode_ci", row.getString(1));
            assertEquals(collation, row.getString(2));
        }
        var state = data.state();
        var saved = state.employeeSnapshot();
        assertTrue(state.queuedOperations(4).stream().anyMatch(op -> op.id().equals("DEMO-0002")));

        // Exercise exact matching without writing aliases or changing the persistent queue.
        var aliases = Set.of("demo-0002", "DÉMO-0002", "DEMO-0002 ");
        state.restoreEmployeeSnapshot(withQueue(saved, aliases));
        assertTrue(state.queuedOperations(4).isEmpty());
        state.restoreEmployeeSnapshot(withQueue(saved, Set.of("DEMO-0004", "DEMO-0002", "DÉMO-0002")));
        assertEquals(List.of("DEMO-0002"), state.queuedOperations(1).stream().map(Operation::id).toList());
        assertEquals(List.of("DEMO-0002", "DEMO-0004"),
                state.queuedOperations(4).stream().map(Operation::id).toList());
    }

    private static EmployeeSnapshot withQueue(EmployeeSnapshot saved, Set<String> queue) {
        return new EmployeeSnapshot(saved.matches(), saved.previousContracts(), queue, Map.of(),
                saved.runs(), saved.audit(), saved.nextRunNumber(), saved.revision());
    }
}
