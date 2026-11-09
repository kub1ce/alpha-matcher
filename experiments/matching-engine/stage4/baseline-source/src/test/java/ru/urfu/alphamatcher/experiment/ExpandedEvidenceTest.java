package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class ExpandedEvidenceTest {
    private final PurposeParser parser = new PurposeParser();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Иванова Ивана Ивановича|иванов иван иванович",
            "Иванову Ивану Ивановичу|иванов иван иванович",
            "Ивановым Иваном Ивановичем|иванов иван иванович",
            "Ивановой Анны Сергеевны|иванова анна сергеевна",
            "Иванову Анну Сергеевну|иванова анна сергеевна",
            "Сидоровой Анне Сергеевне|сидорова анна сергеевна",
            "Ким Марии Андреевны|ким мария андреевна",
            "Кима Ивана Ивановича|ким иван иванович",
            "Шевченко Павла Олеговича|шевченко павел олегович",
            "ПЕТРОВА ПЕТРА ПЕТРОВИЧА|петров петр петрович",
            "Петров Пётр Петрович|петров петр петрович",
            "Иванова-Петрова Ивана Ивановича|иванов-петров иван иванович"
    })
    void resolvesExplicitGenderCompatibleLexicalForms(String surface, String expected) {
        var mention = NameNormalizer.resolve(surface);
        assertTrue(mention.recognized(), surface);
        assertEquals(expected, mention.key());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Неизвестнова Августа Августовича", "Иванова Ивана Сергеевны", "Иванов И.И.", "Иванов Иван", "Кима Марии Андреевны"})
    void neverInventsNominativeByRemovingEndings(String value) { assertFalse(NameNormalizer.resolve(value).recognized()); }

    @ParameterizedTest
    @ValueSource(strings = {"За услуги связи", "Оплата за услуги связи по договору D-1", "За период с января по март", "За поставку нового оборудования"})
    void servicePhrasesAreNotPeopleOrThirdParties(String value) {
        var features = parser.parse(value);
        assertFalse(features.thirdParty());
        assertTrue(features.names().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Оплата за Иванова Ивана Ивановича", "Взнос за Ким Марию Андреевну", "За третье лицо", "За ООО «Синтез 12»", "Оплата за ИП Иванова Ивана Ивановича"})
    void recognizesPossibleThirdParties(String value) { assertTrue(parser.parse(value).thirdParty()); }

    @Test
    void normalizesQuotedIdentifiersAndExtractsOrganizationsWithoutTruncatingNumber() {
        var features = parser.parse("Договор № «ab—1/26», ИНН 9900000017 за услуги связи, ООО \"Ёлка 42\"");
        assertEquals(List.of("AB-1/26"), features.contractNumbers());
        assertEquals(List.of("9900000017"), features.inns());
        assertEquals(List.of("ооо елка 42"), features.organizations());
        assertTrue(features.issues().isEmpty());
        assertEquals(List.of("D-1/"), parser.parse("Договор D-1/").contractNumbers());
        assertEquals(Status.REVIEW,decision("Договор D-1/, ИНН 9900000017").status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ФИО Неизвестнов Август Августович", "ФИО Петров Пётр Петрович", "ООО «Чужая организация»", "ФИО Иванов Иван"})
    void additionalConflictingOrIncompleteEvidenceBlocksOtherwiseSafeDecision(String evidence) {
        assertEquals(Status.REVIEW, decision("Договор D-1, ИНН 9900000017, " + evidence).status());
    }

    @Test
    void matchingNamesDoNotReplaceMandatoryInnAndExplicitNumber() {
        assertEquals(Status.SAFE, decision("Договор D-1, ИНН 9900000017, ФИО Иванова Ивана Ивановича").status());
        assertEquals(Status.REVIEW, decision("Договор D-1, ФИО Иванова Ивана Ивановича").status());
        assertEquals(Status.SAFE, decision("Договор D-1, ИНН 9900000017 за услуги связи").status());
    }

    private Decision decision(String purpose) {
        var payer = new Party(1L, "9900000017", "9900000017", true, null, null, "Иванов", "Иван", "Иванович");
        var recipient = new Party(2L, "9900000024", "9900000024", true);
        var parties = Map.of("PAYER", payer, "RECIPIENT", recipient);
        return MatchingEngine.evaluate(new Operation(1, purpose, "LOADED", true, null, parties), parser.parse(purpose),
                List.of(new Candidate(1, "D-1", parties)));
    }
}
