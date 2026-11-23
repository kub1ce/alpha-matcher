package ru.urfu.alphamatcher.matching;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.urfu.alphamatcher.demo.*;
import ru.urfu.alphamatcher.experiment.MatchingModel;

@EnabledIfEnvironmentVariable(named="ALPHA_DEMO_PASSWORD", matches=".+")
class RawPartySafeIntegrationTest {
    @Test void acceptedRawPartySafeCanBeConfirmedWithoutDemoNameHeuristics() throws Exception {
        var data=new JdbcDemoDataService("jdbc:mysql://127.0.0.1:33368/alpha_matcher_ui_demo?useSSL=false&allowPublicKeyRetrieval=true","ui_demo",System.getenv("ALPHA_DEMO_PASSWORD"),System.getenv("ALPHA_DEMO_OWNER"),true);
        var state=data.state();var before=state.employeeSnapshot();var service=new DemoMatchingService(data);
        assertFalse(state.isLinked("EXP-013"));
        assertNotEquals(data.contract("DB-105").payer(),state.operation("EXP-013").payer().name());
        try {
            var preview=service.preview(state,LocalDate.of(2026,10,1),LocalDate.of(2026,10,1),true,true);
            assertEquals(MatchingModel.Status.SAFE,preview.find("EXP-013").decision().status());
            service.apply(state,preview,Set.of("EXP-013"),DemoUser.ISAEV.fullName());
            assertEquals("DB-105",state.match("EXP-013").contractId());
            assertEquals(MatchingModel.Status.SKIPPED,data.analyze("EXP-013").status());
        } finally {
            data.confirmAtomically(state,()->{
                if(state.isLinked("EXP-013"))state.unlinkIfUnchanged("EXP-013",state.match("EXP-013"));
                state.restoreEmployeeSnapshot(before);
                return null;
            });
        }
        assertEquals(before.audit(),state.auditEvents());
        assertEquals(before.runs(),state.batchRuns());
        assertEquals(MatchingModel.Status.SAFE,data.analyze("EXP-013").status());
    }
}
