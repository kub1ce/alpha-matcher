package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Development safety constraints; prospective holdout is separate and not read here. */
class Stage6FreezeTest {
    @ParameterizedTest @ValueSource(strings={"неизвестность","неведомость","представителя","доверителя"})
    void unresolvedObjectIsNotIdentityEvidence(String object){
        var d=CorrectnessRemediationTest.decide("Оплата за "+object+"; договор № ZX-583; ИНН 9900000017",List.of(CorrectnessRemediationTest.CONTRACT));
        assertEquals(Status.REVIEW,d.status());assertNull(d.proposedContractId());
    }
    @ParameterizedTest @ValueSource(strings={"ZXWORD","КОДСЛОВО","УСЛУГ","ПОСТАВКИ"})
    void unsupportedUnmarkedWordsDoNotAcquireCompletenessFromInn(String number){
        var d=CorrectnessRemediationTest.decide("Договор "+number+"; ИНН 9900000017",List.of(new Candidate(101,number,CorrectnessRemediationTest.PARTIES)));
        assertEquals(Status.REVIEW,d.status());assertNull(d.proposedContractId());
        assertNotEquals(PurposeParser.Completeness.COMPLETE,d.features().contractAnalysis());
    }
    @ParameterizedTest @ValueSource(strings={"Туманов Елисей Назарович","Тумaнов Елисей Захарович","Brown John George","Туманов Е.З."})
    void coherentInnDoesNotOverrideUnconfirmedName(String name){
        var d=CorrectnessRemediationTest.decide("Договор № ZX-583; ИНН 9900000017; ФИО: "+name,List.of(CorrectnessRemediationTest.CONTRACT));
        assertEquals(Status.REVIEW,d.status());assertNull(d.proposedContractId());
    }
}
