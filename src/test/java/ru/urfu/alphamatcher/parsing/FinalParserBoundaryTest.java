package ru.urfu.alphamatcher.parsing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.demo.DemoOperationState;

class FinalParserBoundaryTest {
    private final EnginePaymentPurposeParser parser = new EnginePaymentPurposeParser();

    @Test
    void specialCharactersMultipleValuesAndLimitKeepReadOnlyState() {
        DemoOperationState state = new DemoOperationState(List.of());
        long beforeRevision = state.revision();
        var result = parser.parse("Оплата\t ООО «Ромашка»\n по договору AG-2026-014 и по дог. № 45/26; "
                + "ИНН 1234567894, ИНН 123456789047; ФИО: Петров Пётр Петрович");
        assertEquals(2, result.contracts().values().size());
        assertEquals(2, result.inns().values().size());
        assertFalse(result.features().names().isEmpty());
        assertFalse(result.features().organizations().isEmpty());
        assertEquals(ParseResult.Status.MULTIPLE, result.contracts().status());
        assertEquals(ParseResult.Status.MULTIPLE, result.inns().status());
        var invalid = parser.parse("ИНН 1234567890; ИНН 1234567894");
        assertEquals(List.of("1234567890"), invalid.inns().invalidValues());
        assertEquals(List.of("1234567894"), invalid.inns().values());
        assertEquals(4000, parser.parse("А".repeat(4000)).originalText().length());
        assertThrows(IllegalArgumentException.class, () -> parser.parse("А".repeat(EnginePaymentPurposeParser.MAX_LENGTH + 1)));
        assertEquals(beforeRevision, state.revision());
        assertTrue(state.batchRuns().isEmpty());
        assertTrue(state.auditEvents().isEmpty());
    }
}
