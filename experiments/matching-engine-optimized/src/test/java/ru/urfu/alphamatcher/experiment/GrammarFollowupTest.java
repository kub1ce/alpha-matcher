package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Development constructions. Does not read any prospective holdout or its oracle. */
class GrammarFollowupTest {
    static Decision decide(String context){return CorrectnessRemediationTest.decide(context+"; договор № ZX-583; ИНН 9900000017",List.of(CorrectnessRemediationTest.CONTRACT));}
    @ParameterizedTest @ValueSource(strings={"текущий месяц","предыдущий квартал","отчётный год","отчетный период",
            "расчётный день","календарный год","прошлую неделю","текущую декаду","следующее полугодие","второй квартал"})
    void boundedCalendarObjectIsNotAnActor(String period){
        var d=decide("Услуги за "+period);assertEquals(Status.SAFE,d.status(),d.toString());
    }
    @ParameterizedTest @ValueSource(strings={"разработку","переработку","доработку","недоработку","выработку","отработку","проработку","обработку","подработку"})
    void prefixedWorkNounRetainsPaymentGovernor(String object){
        assertEquals(Status.SAFE,decide("Оплата за "+object+" изделий").status());
    }
    @ParameterizedTest @ValueSource(strings={"Оплата за сиротку","Оплата за молодку","Оплата за подругу","Оплата за работника",
            "Оплата за доверителя","Оплата за прошлого клиента","За доработку","Оплата за Разработку Ивана Петровича",
            "Оплата за Отчётный Квартал","Оплата за текущего плательщика","Оплата за отчётный квартал Иванова",
            "Оплата за непонятный период","Услуги за текущий квартал; по поручению клиента"})
    void oppositeActorReadingsAndUnknownTailStayManual(String context){
        assertEquals(Status.REVIEW,decide(context).status(),context);
    }
    @ParameterizedTest @ValueSource(strings={"Услуги за отчётный месяц","Оплата за доработку"})
    void contextCannotHideNamesRolesOtherInnsOrDuplicateContracts(String context){
        for(String hazard:List.of("ФИО: Туманов Елисей Назарович","Получатель: ИНН 9900000017","ИНН 9900000031","за третье лицо"))
            assertEquals(Status.REVIEW,decide(context+"; "+hazard).status());
        var d=CorrectnessRemediationTest.decide(context+"; договор № ZX-583; ИНН 9900000017",List.of(CorrectnessRemediationTest.CONTRACT,new Candidate(102,"ZX-583",CorrectnessRemediationTest.PARTIES)));
        assertEquals(Status.REVIEW,d.status());assertNull(d.proposedContractId());
    }
    @ParameterizedTest @ValueSource(strings={"SUPPLY","LEASE","SERVICES","ПОСТАВКИ","АРЕНДЫ","СОГЛАШЕНИЕ"})
    void uppercaseCatalogWordDoesNotAloneProveExplicitIdentifier(String word){
        var d=CorrectnessRemediationTest.decide("Договор "+word+"; ИНН 9900000017",List.of(new Candidate(101,word,CorrectnessRemediationTest.PARTIES)));
        assertEquals(Status.REVIEW,d.status());
    }
}
