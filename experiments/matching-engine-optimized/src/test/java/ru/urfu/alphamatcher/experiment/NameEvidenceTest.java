package ru.urfu.alphamatcher.experiment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class NameEvidenceTest {
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "Черняку Илье Кузьмичу|Черняк Илья Кузьмич",
        "Черняка Илью Кузьмича|Черняк Илья Кузьмич",
        "Черняком Ильёй Кузьмичом|Черняк Илья Кузьмич",
        "Хартманн Мишель Альбертовны|Хартманн Мишель Альбертовна",
        "Лосю Игорю Яковлевичу|Лось Игорь Яковлевич",
        "Зайцем Павлом Ильичом|Заяц Павел Ильич",
        "Де-Витта Юлия Оскаровича|Де-Витт Юлий Оскарович",
        "Кожухом Лукьяном Валентиновичем|Кожух Лукьян Валентинович",
        "Мельник Аксиньи Яковлевны|Мельник Аксинья Яковлевна",
        "Крутых Агнии Денисовны|Крутых Агния Денисовна",
        "Саито Наоми Викторовны|Саито Наоми Викторовна",
        "Львом Петром Павловичем|Лев Пётр Павлович",
        "Рябцевым Савелием Артёмовичем|Рябцев Савелий Артёмович",
        "Илье Кузьмичу Черняку|Черняк Илья Кузьмич",
        "Грачев Любови Ильиничны|Грачев Любовь Ильинична",
        "Smith John George|Smith John George",
        "O'Neil José Gabriel|O'Neil José Gabriel"})
    void fullDbNameIsVerifiedComponentByComponent(String surface,String canonical) {
        // Instrumental of an unfamiliar -ов surname is deliberately still unsupported.
        if(surface.startsWith("Рябцевым")) { assertFalse(NameNormalizer.matches(surface,canonical));return; }
        var confirmation=NameNormalizer.confirm(surface,canonical);
        assertTrue(confirmation.fullNameMatched(),confirmation.toString());
        assertEquals(surface,confirmation.surface());assertEquals(NameNormalizer.key(canonical),confirmation.databaseCanonical());
        assertEquals(Status.SAFE,decision(surface,canonical,false).status());
    }

    @ParameterizedTest @CsvSource(delimiter='|',value={
        "Черняку Ильи Кузьмичу|Черняк Илья Кузьмич",
        "Черняку Илие Кузьмичу|Черняк Илья Кузьмич",
        "Рябцева Савелию Артёмовича|Рябцев Савелий Артёмович",
        "Лебедева Савелия Артёмовича|Рябцев Савелий Артёмович",
        "Рябцева Теодора Артёмовича|Рябцев Савелий Артёмович",
        "Рябцева Савелия Филипповича|Рябцев Савелий Артёмович",
        "Хартманн Мишэль Альбертовны|Хартманн Мишель Альбертовна",
        "Лося Игором Яковлевичем|Лось Игорь Яковлевич",
        "Заяцем Павлом Ильичом|Заяц Павел Ильич",
        "Витта Юлия Оскаровича|Де-Витт Юлий Оскарович",
        "Smith Jon George|Smith John George",
        "John George Smith|Smith John George",
        "Smіth John George|Smith John George",
        "Smitһ John George|Smitһ John George",
        "Смиты Джона Джорджа|Smith John George",
        "Рябцев С.А.|Рябцев Савелий Артёмович"})
    void conflictingComponentsAndCasesCannotBeReplacedByInn(String surface,String canonical) {
        assertFalse(NameNormalizer.confirm(surface,canonical).fullNameMatched());
        assertEquals(Status.REVIEW,decision(surface,canonical,false).status());
    }

    @ParameterizedTest @ValueSource(strings={"Рябцева Савелия Артёмовича","Шнайдера Теодора Филипповича","Черняку Илье Кузьмичу"})
    void aCanonicalHypothesisIsNotLexicalCertaintyOrDbIdentity(String surface) {
        var mention=NameNormalizer.resolve(surface);
        assertEquals(surface,mention.surface());assertFalse(mention.recognized());
        assertFalse(mention.canonicalHypotheses().isEmpty());
        assertFalse(NameNormalizer.confirm(surface,"Сидоров Иван Иванович").fullNameMatched());
    }

    @Test void foreignNamesAreExtractedOnlyInExplicitNameContextWithoutTransliteration() {
        var parser=new PurposeParser();
        var f=parser.parse("ФИО O'Neil José Gabriel; ИНН 9900000017");
        assertTrue(f.issues().isEmpty(),f.issues().toString());assertEquals(1,f.names().size());
        assertEquals("o'neil josé gabriel",f.names().getFirst().key());
        assertTrue(parser.parse("payment for Smith John George").names().isEmpty());
        assertFalse(parser.parse("ФИО Smіth John George").issues().isEmpty());
        assertEquals(NameNormalizer.FullNameMatch.EXACT,NameNormalizer.confirm("Smith John George","Smith John George").match());
        assertEquals(NameNormalizer.FullNameMatch.REORDERED,NameNormalizer.confirm("Илье Кузьмичу Черняку","Черняк Илья Кузьмич").match());
    }

    @Test void sameFullNameDoesNotProveSameClientAndThirdPartyStillRequiresReview() {
        assertEquals(Status.REVIEW,decision("Черняку Илье Кузьмичу","Черняк Илья Кузьмич",true).status());
        var d=decision("Черняку Илье Кузьмичу","Черняк Илья Кузьмич",false);
        var candidate=new Candidate(1,"D-1",Map.of("PAYER",party("Черняк Илья Кузьмич",99L),"RECIPIENT",recipient()));
        assertEquals(Status.REVIEW,DiscoveryTestSupport.evaluate(operation("Договор D-1, ИНН 9900000017, ФИО Черняку Илье Кузьмичу","Черняк Илья Кузьмич"),d.features(),List.of(candidate)).status());
    }

    @Test void lateNameConflictsAndResourceLimitsRemainFailClosed() {
        String base="Договор D-1, ИНН 9900000017, ";
        String filler="пояснение к платежу ".repeat(1500);
        assertTimeoutPreemptively(Duration.ofSeconds(4),()->{
            assertEquals(Status.REVIEW,evaluate(base+filler+", ФИО Черняк Илия Кузьмич","Черняк Илья Кузьмич").status());
            assertEquals(Status.REVIEW,evaluate(base+"x".repeat(64001)+", ФИО Черняк Илья Кузьмич","Черняк Илья Кузьмич").status());
            assertTrue(new PurposeParser(1).parse(base+filler).issues().stream().anyMatch(s->s.contains("TIME_LIMIT")));
            assertTrue(new PurposeParser().parse("ФИО Черняк Илья Кузьмич; ".repeat(300)).issues().stream().anyMatch(s->s.contains("MATCH_LIMIT")));
        });
    }

    @Test void aLaterCorrectMentionCannotOverwriteAnEarlierInconsistentCase() {
        String text="Договор D-1, ИНН 9900000017; ФИО Иванова Ивану Ивановича; ФИО Иванова Ивана Ивановича";
        var decision=evaluate(text,"Иванов Иван Иванович");
        assertEquals(2,decision.features().names().size());assertEquals(Status.REVIEW,decision.status());
    }

    @Test void unfamiliarInvariantComponentsDoNotMakeMixedCasesACertainCanonicalForm() {
        String surface="Рябцева Савелию Артёмовича";
        assertEquals(NameNormalizer.key(surface),NameNormalizer.resolve(surface).key());
        assertFalse(NameNormalizer.matches(surface,"Рябцев Савелий Артёмович"));
    }

    @Test void rareInstrumentalPatronymicsAreNotSilentlyIgnoredWithoutALabel() {
        for(String name:List.of("Зайцем Павлом Ильичом","Зайцем Петром Ильичом")) {
            var d=evaluate("Договор D-1, ИНН 9900000017; "+name,"Заяц Павел Ильич");
            assertEquals(1,d.features().names().size());
            assertEquals(name.contains("Павлом")?Status.SAFE:Status.REVIEW,d.status());
        }
    }

    private static Decision decision(String surface,String canonical,boolean third) {
        return evaluate("Договор D-1, ИНН 9900000017, "+(third?"по поручению ":"")+"ФИО "+surface,canonical);
    }
    private static Decision evaluate(String text,String canonical) {
        var operation=operation(text,canonical);
        return DiscoveryTestSupport.evaluate(operation,new PurposeParser().parse(text),List.of(new Candidate(1,"D-1",operation.parties())));
    }
    private static Operation operation(String text,String canonical) {
        return new Operation(1,text,"LOADED",true,null,Map.of("PAYER",party(canonical,1L),"RECIPIENT",recipient()));
    }
    private static Party party(String name,long id) {
        String[] p=name.split(" ");return new Party(id,"9900000017","9900000017",true,null,null,p[0],p[1],p[2]);
    }
    private static Party recipient() { return new Party(2L,"9900000024","9900000024",true); }
}
