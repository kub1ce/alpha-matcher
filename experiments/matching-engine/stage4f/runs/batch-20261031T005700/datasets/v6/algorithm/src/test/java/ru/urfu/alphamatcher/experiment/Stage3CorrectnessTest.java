package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class Stage3CorrectnessTest {
    private final PurposeParser parser=new PurposeParser();
    private final Party payer=new Party(1L,"9900000017","9900000017",true,null,null,"Соколов","Сергей","Васильевич");
    private final Party recipient=new Party(2L,"9900000024","9900000024",true,null,"ООО «Маяк»",null,null,null);
    private final Map<String,Party> parties=Map.of("PAYER",payer,"RECIPIENT",recipient);
    private final String base="договор D-1, ИНН 9900000017";

    @ParameterizedTest
    @ValueSource(strings={"ИНН 9900000017 / 9900000031, договор D-1","договор D-1, D-2, ИНН 9900000017",
            "получатель ИНН 9900000017, договор D-1","плательщик ООО «Маяк», договор D-1, ИНН 9900000017",
            "получатель ФИО Соколов Сергей Васильевич, договор D-1, ИНН 9900000017",
            "от имени ООО «Маяк», договор D-1, ИНН 9900000017","по поручению ООО «Маяк», договор D-1, ИНН 9900000017",
            "договор D-1, ИНН 9900000017, за Соколова","договор D-1, ИНН 9900000017, за клиента",
            "договор D-1, ИНН 9900000017, ИHH 9900000031","договор D-1, ИНН 9900000017, INN 9900000031",
            "договор D-1, ИНН 9900000017 / abc","договор D-1, AA, ИНН 9900000017",
            "договор D-1, ИНН 9900000017, не получатель ООО «Маяк»","без договора; договор D-1, ИНН 9900000017",
            "договор D-1+2, ИНН 9900000017","договор D-1·2, ИНН 9900000017","договор D-1, ИНН 9900000017+1","договор D-1:2, ИНН 9900000017",
            "договор D-1, ИНН 9900000017, ФИО: Sokolov Sergei Vasilevich","договор D-1, ИНН 9900000017, ФИО:","договор D-1, ИНН 9900000017, ИП неизвестный"})
    void singleMatchingClueCannotHideASecondConflict(String text) { assertEquals(Status.REVIEW,decision(text).status()); }

    @Test
    void knownNamesOutsideLexiconAreVerifiedAgainstWholeDbNameAndCase() {
        assertTrue(NameNormalizer.matches("Соколова Сергея Васильевича","Соколов Сергей Васильевич"));
        assertTrue(NameNormalizer.matches("Соколову Сергею Васильевичу","Соколов Сергей Васильевич"));
        assertFalse(NameNormalizer.matches("Соколова Сергею Васильевич","Соколов Сергей Васильевич"));
        assertEquals(Status.SAFE,decision(base+", ФИО Соколову Сергею Васильевичу").status());
        assertEquals(Status.SAFE,decision(base+", ФИО Соколов Сергей Васильевич.").status());
        assertEquals(Status.SAFE,decision("Договор D-1: ИНН 9900000017").status());
        assertEquals(Status.REVIEW,decision(base+", ФИО Соколов И.И.").status());
        assertFalse(NameNormalizer.matches("Соколов С.В.","Соколов Сергей Васильевич"));
    }

    @ParameterizedTest
    @CsvSource(delimiter='|',value={"Петровой-Сидоровой Татьяны Александровны|Петрова-Сидорова Татьяна Александровна",
            "Дюма Александру Сергеевичу|Дюма Александр Сергеевич","Ким Марии Андреевны|Ким Мария Андреевна",
            "Кимом Иваном Ивановичем|Ким Иван Иванович"})
    void componentParadigmsKeepGenderAndIndeclinableNames(String observed,String known) { assertTrue(NameNormalizer.matches(observed,known)); }

    @Test
    void longTextIsScannedAtBothEndsWithoutDiscardingLateEvidence() {
        String filler="служебное пояснение ".repeat(500);
        assertEquals(Status.SAFE,decision(base+", "+filler).status());
        assertEquals(Status.REVIEW,decision(base+", "+filler+", ИНН 9900000031").status());
        assertEquals(Status.REVIEW,decision(base+", "+filler+", договор D-2").status());
        assertEquals(Status.REVIEW,decision(base+", "+filler+", от имени ООО «Маяк»").status());
    }

    @Test
    void explicitBoundsFailClosedAndDoNotAllocateUnboundedAlternatives() {
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->{
            assertTrue(parser.parse("x".repeat(1_000_000)).issues().stream().anyMatch(i->i.contains("SIZE_LIMIT")));
            assertEquals(Status.REVIEW,decision(base+", "+"ИНН 9900000017, ".repeat(300)).status());
            assertEquals(Status.REVIEW,decision("Договор "+"A-".repeat(60)+"1, ИНН 9900000017").status());
            assertTrue(new PurposeParser(1).parse(base).issues().stream().anyMatch(i->i.contains("TIME_LIMIT")));
        });
    }

    @Test
    void zeroCandidatesWithWeakOrConflictingCluesCannotProveAbsence() {
        for(String text:List.of("ФИО Неизвестнов Август Августович","договор UNKNOWN-404, ИНН 9900000031")) {
            var result=DiscoveryTestSupport.evaluate(new Operation(1,text,"LOADED",true,null,parties),parser.parse(text),List.of());
            assertEquals(Status.REVIEW,result.status());
        }
    }

    @Test
    void unicodeUppercaseMustNotExpandOneIdentifierIntoAnother() {
        assertNotEquals(PurposeParser.number("straße-1"),PurposeParser.number("strasse-1"));
        String text="договор straße-1, ИНН 9900000017";
        var result=DiscoveryTestSupport.evaluate(new Operation(1,text,"LOADED",true,null,parties),parser.parse(text),List.of(new Candidate(1,"STRASSE-1",parties)));
        assertEquals(Status.REVIEW,result.status());
    }

    @Test
    void recipientContextDiffersFromRepresentationAndOrdinaryServices() {
        assertEquals(Status.SAFE,decision(base+", в пользу ООО «Маяк»").status());
        assertEquals(Status.SAFE,decision(base+", за услуги связи").status());
        assertEquals(Status.REVIEW,decision(base+", ООО «Маяк").status());
        assertEquals(Status.REVIEW,decision(base+", ИНН 9900000\u200b031").status());
        assertNotEquals(NameNormalizer.organizationKey("ООО Aльфа"),NameNormalizer.organizationKey("ООО Альфа"));
    }

    private Decision decision(String text) {
        return DiscoveryTestSupport.evaluate(new Operation(1,text,"LOADED",true,null,parties),parser.parse(text),List.of(new Candidate(1,"D-1",parties)));
    }
}
