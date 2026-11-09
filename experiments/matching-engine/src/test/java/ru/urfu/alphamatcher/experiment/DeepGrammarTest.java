package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Development only: independent of the frozen prospective deepening corpus. */
class DeepGrammarTest {
    static Decision decide(String context){return CorrectnessRemediationTest.decide(context+"; договор № ZX-583; ИНН 9900000017",List.of(CorrectnessRemediationTest.CONTRACT));}
    @ParameterizedTest @ValueSource(strings={"анализ контрольных образцов","монтаж кабелей","переплёт документов","испытания оборудования",
            "синтез материалов","контроль качества","аудит отчётности","осмотр здания","надзор работ","экспертизу проекта",
            "поверку датчиков","проверку изделий","сборку блоков","настройку приборов","наладку оборудования",
            "исследования образцов","обследование сооружений","измерения параметров","тестирование программных модулей"})
    void intrinsicActionAndTypedDependenciesArePaymentObjects(String object){assertEquals(Status.SAFE,decide("Оплата за "+object).status(),object);}
    @ParameterizedTest @ValueSource(strings={"услуги Иванова","обработку иванова","обучение клиента","доработку Brown",
            "анализ иванова","контроль неизвестного лица","сборку Юдина","монтаж кабеля Иванова",
            "анализ материалов неизвестные сведения","испытания приборов ???","анализ Туманова Елисея Назаровича"})
    void headAloneNeverHidesActorOrUnknownDependentTail(String object){assertEquals(Status.REVIEW,decide("Оплата за "+object).status(),object);}
    @ParameterizedTest @ValueSource(strings={"анализ","монтаж","экспертизу","контроль"})
    void unfamiliarOrMixedScriptTailDoesNotBecomeAbsent(String head){
        for(String tail:List.of("мaтериалов","НЕИЗВЕСТНО","неподтверждённого явления","сиротки"))
            assertEquals(Status.REVIEW,decide("Оплата за "+head+" "+tail).status());
    }
    @ParameterizedTest @ValueSource(strings={"Договор номер ","Договор под номером ","Договор с номером ","Contract number ","Contract no. ","Contract No "})
    void lexicalNumberLabelHasAnIndependentStructuralMeaning(String label){
        var c=new Candidate(101,"CLEARCODE",CorrectnessRemediationTest.PARTIES);
        var d=CorrectnessRemediationTest.decide(label+"CLEARCODE; ИНН 9900000017",List.of(c));assertEquals(Status.SAFE,d.status());
        assertEquals(Status.REVIEW,CorrectnessRemediationTest.decide("Договор CLEARCODE; ИНН 9900000017",List.of(c)).status());
        assertEquals(Status.REVIEW,CorrectnessRemediationTest.decide(label+"CLEARCODE; ИНН 9900000017",List.of(c,new Candidate(102,"CLEARCODE",CorrectnessRemediationTest.PARTIES))).status());
    }
    @ParameterizedTest @ValueSource(strings={" or OTHER"," или ДРУГОЙ"," либо ДРУГОЙ"," and OTHER"," и OTHER"})
    void unresolvedOtherReferenceCannotDisappearAfterTextualNumberLabel(String tail){
        var c=new Candidate(101,"CLEARCODE",CorrectnessRemediationTest.PARTIES);
        assertEquals(Status.REVIEW,CorrectnessRemediationTest.decide("Договор номер CLEARCODE"+tail+"; ИНН 9900000017",List.of(c)).status());
    }
    @ParameterizedTest @ValueSource(strings={"анализ материалов","монтаж оборудования","переплёт документов","испытания кабелей"})
    void independentHazardsRemainVisibleAfterFragmentClassification(String object){
        for(String hazard:List.of("ФИО: Туманов Елисей Назарович","Получатель: ИНН 9900000017","ИНН 9900000031","по поручению клиента"))
            assertEquals(Status.REVIEW,decide("Оплата за "+object+"; "+hazard).status());
    }
    @Test void technicalFooterIsNotSilentlyTreatedAsMissingName(){
        assertEquals(Status.REVIEW,decide("Оплата за анализ материалов; Сопроводительная отметка ГХИЧ").status());
        assertEquals(Status.SAFE,decide("Оплата за анализ материалов; Сопроводительная отметка: ГХИЧ").status());
        assertEquals(Status.REVIEW,decide("Оплата за анализ материалов; Сопроводительная отметка: ГХИЧ; ФИО: Brown John George").status());
    }
    @Test void unmarkedWordAndChangedIdentityRemainIndependentOfCatalogHit(){
        for(String word:List.of("SUPPLY","LEASE","ОКАЗАНИЯ","ПОСТАВКИ"))
            assertEquals(Status.REVIEW,CorrectnessRemediationTest.decide("Договор "+word+"; ИНН 9900000017",List.of(new Candidate(101,word,CorrectnessRemediationTest.PARTIES))).status());
    }
    @ParameterizedTest @ValueSource(strings={"анализ образцов","монтаж приборов","контроль качества","переплёт документов"})
    void metamorphicMeaningfulAndIrrelevantChangesHaveDifferentEffects(String phrase){
        for(String governor:List.of("Оплата","Перечисление","Платёж")){
            var a=decide(governor+" за "+phrase);
            var b=decide(governor+" за "+phrase+"; Без НДС");
            assertEquals(Status.SAFE,a.status());assertEquals(a.status(),b.status());assertEquals(a.candidateIds(),b.candidateIds());
            assertEquals(Status.REVIEW,decide(governor+" за "+phrase+" Иванова").status());
            assertEquals(Status.REVIEW,decide(governor+". За "+phrase).status());
        }
    }
    @ParameterizedTest @ValueSource(strings={"CLEARCODE постороннее","СЧЕТА ACCOUNT","ПЛАТЕЛЬЩИКА ИВАНОВА","clearcode"})
    void lexicalNumberFieldCannotSwallowAnotherProseComponent(String value){
        String candidate=value.split(" ")[0].toUpperCase(java.util.Locale.ROOT);
        var d=CorrectnessRemediationTest.decide("Договор номер "+value+"; ИНН 9900000017",List.of(new Candidate(101,candidate,CorrectnessRemediationTest.PARTIES)));
        assertEquals(Status.REVIEW,d.status());
    }
    @ParameterizedTest @ValueSource(strings={"услуги, Иванова","анализ материалов, иванова","монтаж кабеля, Brown","обработку документов, клиента"})
    void commaCannotMakeAnUnresolvedDependentActorDisappear(String phrase){assertEquals(Status.REVIEW,decide("Оплата за "+phrase).status());}
    @ParameterizedTest @ValueSource(strings={"услуги связи и обслуживание","анализ материалов и монтаж оборудования","разработку модулей и проверку документов"})
    void coordinatedTypedObjectsAreNotReducedToTheFirstHead(String phrase){assertEquals(Status.SAFE,decide("Оплата за "+phrase).status());}
    @ParameterizedTest @ValueSource(strings={"Оплата за услуги связи, платёж по плану","Перечисление за оборудование дог. # ZX-583","Оплата за услуги, период 12.2027"})
    void completeIndependentFinancialFieldsCloseTheObjectPhrase(String phrase){assertEquals(Status.SAFE,decide(phrase).status());}
    @ParameterizedTest @ValueSource(strings={"услуги связи и неизвестного лица","услуги связи, платёж Иванова","услуги связи, период Иванова"})
    void fieldHeadOrConjunctionDoesNotMakeAnUnknownContinuationSafe(String phrase){assertEquals(Status.REVIEW,decide("Оплата за "+phrase).status());}
    @Test void establishedFinancialScopeFormsRemainCompatible(){
        var parser=new PurposeParser();
        for(String prefix:List.of("Оплата услуг связи","Перечисление за оборудование","Арендная плата","Взнос","Авансовый платёж","Оплата обучения"))
            for(String label:List.of(" по договору № "," по договору N "," дог. # "," договор "))
                for(String suffix:List.of(", без НДС",", период 11.2028",", основание: акт 724",", платёж согласно графику"))
                    for(String service:List.of("",", за услуги связи")){
                        String purpose=prefix+label+"ZX-583, ИНН 9900000017"+service+suffix;
                        assertTrue(parser.parse(purpose).issues().isEmpty(),purpose+parser.parse(purpose).issues());
                    }
    }
    @ParameterizedTest @ValueSource(strings={"поставку тары","ремонт оборудования и услуги доступа"})
    void packagingAndServiceResourcesHaveExplicitNonPersonTypes(String phrase){assertEquals(Status.SAFE,decide("Оплата за "+phrase).status());}
    @ParameterizedTest @ValueSource(strings={"поставку тары Иванова","услуги доступа клиента"})
    void resourceOrPackagingNounCannotHideALaterActor(String phrase){assertEquals(Status.REVIEW,decide("Оплата за "+phrase).status());}
}
