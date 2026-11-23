package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PurposeParserTest {
    private final PurposeParser parser = new PurposeParser();

    @ParameterizedTest
    @ValueSource(strings = {"9900000017", "9900000024", "9900000031", "990000000115"})
    void validatesFixedSyntheticChecksums(String inn) { assertTrue(Inn.valid(inn)); }

    @ParameterizedTest
    @ValueSource(strings = {"", "0000000000", "000000000000", "9900000018", "990000000116", "99000000170", "99A0000017", " 9900000017"})
    void rejectsInvalidChecksumsAndFormats(String inn) { assertFalse(Inn.valid(inn)); }

    @Test
    void extractsNormalizedNumberAndSeparatedInnWithoutGuessingRoles() {
        var result = parser.parse("Оплата\nпо договору № ab–12/26, ИНН: 990\u00a0000\u202f0017.");
        assertEquals(List.of("AB-12/26"), result.contractNumbers());
        assertEquals(List.of("9900000017"), result.inns());
        assertTrue(result.issues().isEmpty());
        assertFalse(result.thirdParty());
    }

    @Test
    void keepsAmbiguousAndInvalidEvidenceInsteadOfChoosingFirst() {
        var result = parser.parse("Договор № D-1, договор № D-2, ИНН 9900000017, ИНН 9900000018");
        assertEquals(List.of("D-1", "D-2"), result.contractNumbers());
        assertEquals(List.of("9900000017"), result.inns());
        assertEquals(List.of("9900000018"), result.invalidInns());
    }

    @Test
    void doesNotTruncateLongNumericOrContractTokensIntoValidEvidence() {
        assertEquals(List.of("9900000017123456789"), parser.parse("ИНН 9900000017123456789").invalidInns());
        var result = parser.parse("Договор № D-" + "1".repeat(130));
        assertTrue(result.contractNumbers().isEmpty());
        assertFalse(result.issues().isEmpty());
    }

    @Test
    void handlesMissingAndUnparseableEvidenceExplicitly() {
        assertTrue(parser.parse(null).inns().isEmpty());
        assertFalse(parser.parse("Договор отсутствует, ИНН неизвестен").issues().isEmpty());
        assertTrue(parser.parse("Заказ 123, код 9900000017").inns().isEmpty());
        assertTrue(parser.parse("Оплата за третье лицо по договору № D-1").thirdParty());
        assertTrue(parser.parse("По договору D-1 за Примерова Тестимира Примеровича").thirdParty());
        assertFalse(parser.parse("ИНН 9900000017A").issues().isEmpty());
        assertFalse(parser.parse("Договор D-1, договор №").issues().isEmpty());
    }

    @Test
    void enforcesDocumentedInputBound() {
        assertTrue(parser.parse("x".repeat(4001)).issues().isEmpty());
        assertTrue(parser.parse("x".repeat(PurposeParser.MAX_LENGTH+1)).issues().stream().anyMatch(issue->issue.startsWith("SIZE_LIMIT")));
    }
}
