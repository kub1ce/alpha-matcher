package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;

class SyntheticGeneratorTest {
    @ParameterizedTest
    @EnumSource(Profile.class)
    void seedAndDimensionsAreDeterministicAndKeysAreUnique(Profile profile) {
        var generator=new SyntheticGenerator(); var config=Config.standard(42,24,48,200,profile);
        var first=generator.generate(config);
        assertEquals(first,generator.generate(config));
        assertNotEquals(first,generator.generate(Config.standard(43,24,48,200,profile)));
        assertEquals(24,first.clients().size()); assertEquals(48,first.contracts().size()); assertEquals(200,first.payments().size());
        assertEquals(24,new HashSet<>(first.clients().stream().map(Client::inn).toList()).size());
        assertTrue(first.clients().stream().allMatch(client->Inn.valid(client.inn())));
        assertEquals(3,first.clients().stream().map(Client::type).distinct().count());
        assertEquals(200,first.payments().stream().map(Payment::reference).distinct().count());
        assertEquals(200,first.truth().stream().map(Truth::id).distinct().count());
    }

    @Test
    void scenarioWeightsControlCountsExactlyAndCanDisableCases() {
        var config=new Config(1,12,16,100,Profile.MIXED,Map.of(Scenario.CLEAR,3,Scenario.NO_CONTRACT,1));
        var data=new SyntheticGenerator().generate(config);
        assertEquals(75,data.truth().stream().filter(t->t.scenario()==Scenario.CLEAR).count());
        assertEquals(25,data.truth().stream().filter(t->t.scenario()==Scenario.NO_CONTRACT).count());
        assertThrows(IllegalArgumentException.class,()->new Config(1,12,16,100,Profile.MIXED,Map.of(Scenario.CLEAR,-1)));
    }

    @Test
    void simpleSafeWorldsHaveUniqueNumbersConsistentRolesAndExistingTargets() {
        var data=new SyntheticGenerator().generate(Config.standard(99,24,48,200,Profile.SIMPLE));
        for(int i=0;i<data.truth().size();i++) {
            var truth=data.truth().get(i); var payment=data.payments().get(i);
            assertEquals("SAFE",truth.expectedStatus());
            var target=data.contracts().stream().filter(c->c.id()==truth.safeContract()).findFirst().orElseThrow();
            assertEquals(1,data.contracts().stream().filter(c->c.number().equals(target.number())).count());
            assertEquals(target.payer(),payment.payer()); assertEquals(target.recipient(),payment.recipient());
            assertNull(target.beneficiary()); assertNull(payment.linked());
        }
    }

    @Test
    void oracleIsHandSpecifiedRatherThanComputedFromPaymentText() {
        var data=new SyntheticGenerator().generate(new Config(7,12,16,1,Profile.MIXED,Map.of(Scenario.CONFLICT,1)));
        assertEquals("REVIEW",data.truth().getFirst().expectedStatus());
        assertNotEquals(data.truth().getFirst().inns().getFirst(),data.payments().getFirst().payerInn());
    }
}
