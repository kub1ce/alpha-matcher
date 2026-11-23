package ru.urfu.alphamatcher.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.urfu.alphamatcher.parsing.ParseResult.Status;

class EnginePaymentPurposeParserTest {
    private final PaymentPurposeParser parser = new EnginePaymentPurposeParser();
    @Test void malformedTypedNameIsRetainedWithoutInventingAResolvedPersonRole() {
        var result=parser.parse("Payer Full name: Brown John George Extra");
        assertEquals(Status.AMBIGUOUS,result.fullNames().status());
        assertTrue(result.features().names().getFirst().surface().endsWith("Extra"));
        assertTrue(result.features().roleClaims().stream().noneMatch(c->c.kind().equals("NAME")));
    }


    @Test void differentParticipantsAreMultipleButContradictoryRolesAreAmbiguous() {
        assertEquals(Status.MULTIPLE, parser.parse("ИНН плательщика 7707083893; ИНН получателя 000012345627").inns().status());
        var conflicting = parser.parse("Получатель ИНН плательщика 7707083893");
        assertEquals(Status.AMBIGUOUS, conflicting.inns().status());
        assertTrue(conflicting.diagnostics().stream().anyMatch(d -> d.message().contains("противоречащие")));
        assertTrue(conflicting.features().issues().stream().anyMatch(i -> i.startsWith("CONFLICTING_ROLE_CLAIMS")));
    }

    @Test void humanDiagnosticsAreRussianWhileTechnicalIssuesRemainAvailable() {
        var result = parser.parse("По договору № AB-0О1/26");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("кириллица и латиница")));
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.message().contains("MIXED_SCRIPT_CONTRACT")));
        assertTrue(result.features().issues().stream().anyMatch(i -> i.startsWith("MIXED_SCRIPT_CONTRACT")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567894", "123456789047"})
    void acceptsValidTenAndTwelveDigitInns(String inn) {
        var result = parser.parse("ИНН " + inn);
        assertEquals(Status.FOUND, result.inns().status());
        assertEquals(inn, result.inns().values().getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "123456789012", "123456789", "12345678901"})
    void reportsWrongControlDigitsAndLengths(String inn) {
        var result = parser.parse("ИНН: " + inn);
        assertEquals(Status.INVALID, result.inns().status());
        assertEquals(inn, result.inns().invalidValues().getFirst());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.kind() == ParseResult.Kind.FORMAT));
    }

    @ParameterizedTest
    @CsvSource({
            "Оплата по договору AG-2026-031,AG-2026-031",
            "Оплата по дог. № 45/26,45/26",
            "По договору № АБ-15/24,АБ-15/24"
    })
    void extractsContractFromSupportedMarkers(String text, String expected) {
        assertEquals(expected, parser.parse(text).contracts().values().getFirst());
    }

    @ParameterizedTest
    @CsvSource({
            "Оплата за Иванова Ивана Ивановича,Иванова Ивана Ивановича",
            "ФИО: Петров Пётр Петрович,Петров Пётр Петрович"
    })
    void extractsOnlyExplicitSupportedPersonalNames(String text, String expected) {
        assertTrue(parser.parse(text).features().names().stream().anyMatch(name -> name.surface().equals(expected)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Оплата за услуги связи и аренды",
            "Поставка товаров по договору AG-2026-031",
            "За качественное обслуживание клиентов"
    })
    void doesNotInventPersonOrThirdPartyFromOrdinaryWords(String text) {
        var result = parser.parse(text);
        assertTrue(result.fullNames().values().isEmpty());
        assertFalse(result.thirdPartyMentioned());
    }

    @Test
    void initialsRemainAsUnresolvedEvidence() {
        assertFalse(parser.parse("ФИО: Иванов И.И.").features().names().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ИНН 1234567894; ИНН 123456789047\nпо договору AG-2026-014 и по дог. № AG-2026-021",
            "По договору № 45/26 и договору № 46/26; ИНН 123456789047, ИНН 1234567894"
    })
    void preservesAllCandidatesAndOriginalText(String text) {
        var result = parser.parse(text);
        assertEquals(text, result.originalText());
        assertEquals(Status.MULTIPLE, result.inns().status());
        assertEquals(2, result.inns().values().size());
        assertEquals(Status.MULTIPLE, result.contracts().status());
        assertEquals(2, result.contracts().values().size());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " \n "})
    void handlesEmptyInput(String text) {
        assertEquals(Status.NOT_FOUND, parser.parse(text).contracts().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Оплата\u00a0ООО «Ромашка»\nпо дог.\u00a0№ 45/26",
            "Оплата  ООО «Ромашка» по дог. № 45/26"})
    void handlesNonstandardSpacingAndOrganization(String text) {
        var result = parser.parse(text);
        assertEquals("45/26", result.contracts().values().getFirst());
        assertFalse(result.features().organizations().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Платёж за третье лицо", "Оплата за Иванова Ивана Ивановича"})
    void detectsExplicitThirdPartyWording(String text) {
        assertTrue(parser.parse(text).thirdPartyMentioned());
    }

    @Test
    void rejectsOversizedInputWithoutStoringIt() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("А".repeat(EnginePaymentPurposeParser.MAX_LENGTH + 1)));
    }
}
