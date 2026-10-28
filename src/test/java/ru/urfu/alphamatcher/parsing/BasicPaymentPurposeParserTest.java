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

class BasicPaymentPurposeParserTest {
    private final PaymentPurposeParser parser = new BasicPaymentPurposeParser();

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
        assertEquals(expected, parser.parse(text).fullNames().values().getFirst());
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
    void initialsAreNotReportedAsAFullName() {
        assertEquals(Status.NOT_FOUND, parser.parse("ФИО: Иванов И.И.").fullNames().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ИНН 1234567894; ИНН 123456789047\nпо договору AG-2026-014 и по дог. № AG-2026-021",
            "По договору № 45/26 и договору № 46/26; ИНН 123456789047, ИНН 1234567894"
    })
    void preservesAllCandidatesAndOriginalText(String text) {
        var result = parser.parse(text);
        assertEquals(text, result.originalText());
        assertEquals(Status.AMBIGUOUS, result.inns().status());
        assertEquals(2, result.inns().values().size());
        assertEquals(Status.AMBIGUOUS, result.contracts().status());
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
        assertEquals("ООО «Ромашка»", result.organizations().values().getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Платёж за третье лицо", "Оплата за Иванова Ивана Ивановича"})
    void detectsExplicitThirdPartyWording(String text) {
        assertTrue(parser.parse(text).thirdPartyMentioned());
    }

    @Test
    void rejectsOversizedInputWithoutStoringIt() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("А".repeat(4001)));
    }
}
