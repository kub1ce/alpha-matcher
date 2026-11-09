package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class ContextRemediationTest {
    @ParameterizedTest @ValueSource(strings={"оцифровку архива","ламинирование документов","стабилизацию образцов",
            "сортирование материалов","маркировку изделий","инвентаризацию имущества"})
    void productivePaymentObjectsDoNotRequireAddingEveryNounToAWhitelist(String object) {
        var d=CorrectnessRemediationTest.decide("Оплата за "+object+"; Договор ZX-583, ИНН 9900000017",List.of(CorrectnessRemediationTest.CONTRACT));
        assertEquals(Status.SAFE,d.status(),d.toString());CorrectnessRemediationTest.passed(d,"ROLE_CLAIMS");
    }
    @ParameterizedTest @ValueSource(strings={"Оплата за Туманова Елисея Захаровича","Оплата за ООО \"Барьер\"",
            "Оплата за клиента","Оплата за доверителя","Оплата за неизвестность","За оцифровку архива",
            "По поручению Туманова Елисея Захаровича","Оплата за Инвентаризацию Ивана Петровича"})
    void personsOrganizationsAgencyAndUnclearObjectsStayManual(String context) {
        var d=CorrectnessRemediationTest.decide(context+"; Договор ZX-583, ИНН 9900000017",List.of(CorrectnessRemediationTest.CONTRACT));
        assertEquals(Status.REVIEW,d.status(),d.toString());assertNull(d.proposedContractId());
    }
}
