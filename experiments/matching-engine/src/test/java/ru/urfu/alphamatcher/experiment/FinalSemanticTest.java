package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Development cases do not read frozen corpora or oracle. */
class FinalSemanticTest {
    static Decision decide(String object){return DeepGrammarTest.decide("Оплата за "+object);}
    @ParameterizedTest @ValueSource(strings={"монтаж газовых фильтров","очистку технических устройств",
            "научную проверку материалов","подземную прокладку коммуникаций","испытания гидравлических систем",
            "съёмку новых предметов","анализ оптоволоконных кабелей","контроль исторических предметов",
            "сборку металлических панелей","реставрацию технических предметов"})
    void finiteTypesComposeAcrossNewCombinations(String object){
        assertEquals(Status.SAFE,decide(object).status());
        assertEquals(Status.SAFE,DeepGrammarTest.decide("Платёж за "+object).status());
        for(String hazard:List.of(" Иванова"," иванова"," и клиента"," ???","; ФИО: Юдин Павел Андреевич","; ИНН 9900000031"))
            assertEquals(Status.REVIEW,decide(object+hazard).status(),object+hazard);
    }
    @ParameterizedTest @ValueSource(strings={"обучение операторов","обследование пациента","научную неизвестную работу",
            "научную","гидравлическую систему","монтаж газовых неизвестных","прокладку клиенту",
            "съёмку иванова","очистку фuльтров","сборку неизвестных фильтров","научную экспертизу Brown"})
    void modifiersNeverLicensePeopleOrUnknownDependencies(String object){assertEquals(Status.REVIEW,decide(object).status());}
    @ParameterizedTest @ValueSource(strings={"EQUINOX","NORTH","ЛЕС","ПОСТАВКИ","LEASE","SUPPLY","ZZTOP"})
    void catalogAgreementCannotCertifyUnmarkedIntent(String number){
        var c=new Candidate(101,number,CorrectnessRemediationTest.PARTIES);
        for(String text:List.of("Договор "+number,"ИНН 9900000017, договор "+number,"Расчёт по договору "+number+" ИНН 9900000017")){
            var d=CorrectnessRemediationTest.decide(text+"; ИНН 9900000017",List.of(c));
            assertEquals(Status.REVIEW,d.status());assertEquals(PurposeParser.Completeness.INCOMPLETE,d.features().contractAnalysis());
            assertTrue(d.features().issues().stream().anyMatch(i->i.contains("CONTRACT_TYPE_OR_TITLE")));
            assertTrue(d.checks().stream().anyMatch(i->!i.passed()&&i.explanation().contains("not intent proof")));
        }
    }
    @ParameterizedTest @ValueSource(strings={"Договор номер ","Договор № ","Договор под номером "})
    void explicitReferencesStillNeedEveryIndependentGate(String label){
        var c=new Candidate(101,"EQUINOX",CorrectnessRemediationTest.PARTIES);
        assertEquals(Status.SAFE,CorrectnessRemediationTest.decide(label+"EQUINOX; ИНН 9900000017",List.of(c)).status());
        for(String tail:List.of(" или OTHER"," и OTHER","; по поручению клиента","; ФИО: Юдин Павел Андреевич","; ИНН 9900000031"))
            assertEquals(Status.REVIEW,CorrectnessRemediationTest.decide(label+"EQUINOX"+tail+"; ИНН 9900000017",List.of(c)).status());
        assertEquals(Status.REVIEW,CorrectnessRemediationTest.decide(label+"EQUINOX; ИНН 9900000017",List.of(c,new Candidate(102,"EQUINOX",CorrectnessRemediationTest.PARTIES))).status());
    }
    @ParameterizedTest @ValueSource(strings={"ГХИЧ","ЛЖИЧ","ДНОВИЧ"})
    void untypedMetadataRetainsIdentityUncertainty(String code){
        var d=DeepGrammarTest.decide("Сопроводительная отметка "+code);
        assertEquals(Status.REVIEW,d.status());
        assertTrue(d.features().issues().stream().anyMatch(i->i.contains("metadata-or-person")));
        assertEquals(Status.REVIEW,DeepGrammarTest.decide("Сопроводительная отметка: "+code+"; ФИО: Юдин Павел Андреевич").status());
    }
    @Test void metamorphicBoundariesKeepIdentityAndCandidateEvidence(){
        for(String governor:List.of("Оплата","Расчёт","Перечисление")){
            var a=DeepGrammarTest.decide(governor+" за монтаж газовых фильтров");
            var b=DeepGrammarTest.decide(governor+" за монтаж газовых фильтров; Без НДС");
            assertEquals(Status.SAFE,a.status());assertEquals(a.status(),b.status());
            assertEquals(a.candidateIds(),b.candidateIds());assertEquals(a.searchEvidence(),b.searchEvidence());
            assertEquals(Status.REVIEW,DeepGrammarTest.decide(governor+". За монтаж газовых фильтров").status());
        }
    }
}
