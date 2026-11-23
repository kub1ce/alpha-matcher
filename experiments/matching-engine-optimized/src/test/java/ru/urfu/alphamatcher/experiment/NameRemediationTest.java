package ru.urfu.alphamatcher.experiment;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class NameRemediationTest {
    static Decision decision(String surface,String canonical) {
        String[] p=canonical.split(" ");
        var party=new Party(1L,"9900000017","9900000017",true,null,null,p[0],p[1],p[2]);
        var parties=Map.of("PAYER",party,"RECIPIENT",CorrectnessRemediationTest.RECIPIENT);
        String text="Договор ZX-583, ИНН 9900000017; Плательщик: "+surface;
        return DiscoveryTestSupport.evaluate(new Operation(1,text,"LOADED",true,null,parties),new PurposeParser().parse(text),List.of(new Candidate(101,"ZX-583",parties)));
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "Dupré André Lucien|Dupré André Lucien","Кхан Раджив Кумар|Кхан Раджив Кумар",
        "Елисей Захарович Туманов|Туманов Елисей Захарович",
        "Соловья Льва Ильича|Соловей Лев Ильич","Соловью Льву Ильичу|Соловей Лев Ильич",
        "Соловьём Львом Ильичом|Соловей Лев Ильич","Журавеля Кузьмы Фомича|Журавель Кузьма Фомич",
        "Журавелю Кузьме Фомичу|Журавель Кузьма Фомич","Баженова Елисея Захаровича|Баженов Елисей Захарович"})
    void supportedFormsAreConfirmedOnlyAgainstAllThreeCatalogComponents(String surface,String canonical) {
        assertTrue(NameNormalizer.confirm(surface,canonical).fullNameMatched());
        var d=decision(surface,canonical);assertEquals(Status.SAFE,d.status(),d.toString());
        CorrectnessRemediationTest.passed(d,"PURPOSE_NAMES");CorrectnessRemediationTest.passed(d,"ROLE_CLAIMS");
        assertEquals(surface,d.features().names().getFirst().surface());
        String[] p=canonical.split(" ");
        for(String wrong:List.of("Другой "+p[1]+" "+p[2],p[0]+" Другое "+p[2],p[0]+" "+p[1]+" Другое")) {
            var rejected=decision(surface,wrong);assertEquals(Status.REVIEW,rejected.status());
            assertNull(rejected.proposedContractId());CorrectnessRemediationTest.passed(rejected,"PURPOSE_INN");
        }
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "Соловея Лева Ильича|Соловей Лев Ильич","Соловья Льву Ильича|Соловей Лев Ильич",
        "Журавеля Кузьма Фомича|Журавель Кузьма Фомич","Журавеля Кузьми Фомича|Журавель Кузьма Фомич",
        "Dupré Andre Lucien|Dupré André Lucien","Кxан Раджив Кумар|Кхан Раджив Кумар",
        "Елисей Захарович Туманов|Туманов Елисей Назарович"})
    void wrongFormsAndSimilarLettersStayConflicting(String surface,String canonical) {
        assertFalse(NameNormalizer.confirm(surface,canonical).fullNameMatched());
        assertEquals(Status.REVIEW,decision(surface,canonical).status());
    }
    @Test void serviceCodeIsNotAnUnlabelledNameButUnknownExplicitPatronymicIsObserved() {
        assertTrue(new PurposeParser().parse("Служебная пометка АБВИЧ").names().isEmpty());
        var f=new PurposeParser().parse("Плательщик: Баженов Елисей Захарович");
        assertEquals(1,f.names().size());assertTrue(f.issues().isEmpty());
    }
    @Test void primaryHypothesisDoesNotPretendToResolveRealMorphologicalAmbiguity() {
        var f=NameNormalizer.resolve("Кудряшовой Аделины Мироновны");
        assertFalse(f.recognized());assertFalse(NameNormalizer.matches(f.surface(),"Другой Аделина Мироновна"));
        assertTrue(f.canonicalHypotheses().stream().noneMatch(s->s.contains("ьь")));
    }
    @Test void capitalizationCannotSilentlyDropAConflictingUnlabelledPerson() {
        String text="Договор ZX-583, ИНН 9900000017; Другов Елисей ЗАХАРОВИЧ";
        var d=CorrectnessRemediationTest.decide(text,List.of(CorrectnessRemediationTest.CONTRACT));
        assertEquals(Status.REVIEW,d.status());assertFalse(d.features().names().isEmpty());
        assertTrue(d.checks().stream().anyMatch(c->c.code().equals("PURPOSE_NAMES")&&!c.passed()));
        var footer=CorrectnessRemediationTest.decide("Договор ZX-583, ИНН 9900000017; Служебная пометка АБВИЧ",List.of(CorrectnessRemediationTest.CONTRACT));
        assertEquals(Status.REVIEW,footer.status());assertTrue(footer.features().issues().stream().anyMatch(PurposeParser::identityIssue));
    }
}
