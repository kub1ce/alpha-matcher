package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ParserFamilyRegressionTest {
    private final PurposeParser parser = new PurposeParser();
    static Stream<Arguments> typed() {
        return List.of("займа", "поставки", "аренды", "страхования", "обслуживания", "купли-продажи", "оказания услуг").stream()
                .flatMap(type -> List.of("№ ", "# ", "N ").stream().map(marker -> Arguments.of(type, marker)));
    }
    @ParameterizedTest @MethodSource("typed")
    void onlyExplicitTypedNumberIsAnIdentifier(String type, String marker) {
        var result = parser.parse("Оплата по договору " + type + " " + marker + "014/К-26, ИНН 7707083893");
        assertEquals(List.of("014/К-26"), result.contractNumbers());
        assertTrue(result.issues().isEmpty(), result.toString());
        assertEquals(PurposeParser.Completeness.COMPLETE, result.contractAnalysis());
    }
    @ParameterizedTest @ValueSource(strings={"и", ",", ";", "и №"})
    void datedIndependentReferencesDoNotSplitSlashes(String separator) {
        var result = parser.parse("По договорам № А-45/26 от 12.09.2026 " + separator + " А-46/26 от 14.09.2026");
        assertEquals(List.of("А-45/26", "А-46/26"), result.contractNumbers());
        assertEquals(2, result.contractReferences().size());
        assertTrue(result.issues().isEmpty(), result.toString());
    }
    @ParameterizedTest @ValueSource(strings={
            "По договорам № D-1 от 31.02.2026 и № D-2",
            "По договорам № D-1 от 12.09.2026 и № ???",
            "По договору особого вида № D-1",
            "По договору займа без номера",
            "По договору № D-1'; DROP TABLE contract; --"})
    void unresolvedContinuationRemainsIncomplete(String text) {
        assertEquals(PurposeParser.Completeness.INCOMPLETE, parser.parse(text).contractAnalysis());
    }
    @ParameterizedTest @ValueSource(strings={
            "Оплата за оказанные консультационные услуги за октябрь 2026 года.",
            "Оплата за предоставленные информационные услуги за январь.",
            "Оплата за оказанные юридические услуги за декабрь 2027 года.",
            "Оплата за транспортные услуги за март 2026 года согласно действующим договоренностям."})
    void typedServiceAndCalendarObjectDoNotInventActors(String text) {
        var result = parser.parse(text);
        assertFalse(result.thirdParty());
        assertTrue(result.names().isEmpty());
        assertTrue(result.issues().isEmpty(), result.toString());
    }
    @ParameterizedTest @ValueSource(strings={
            "Оплата за оказанные услуги Иванова Ивана Ивановича.",
            "Оплата за оказанные услуги за Петрова Петра Сергеевича.",
            "Оплата за оказанные услуги неизвестного представителя.",
            "Оплата за оказанные неизвестные услуги.",
            "Оплата за оказанные консультационные услуги за октябрь Иванову Ивану Ивановичу."})
    void unknownDependenciesCannotBeLicensedByServiceVocabulary(String text) {
        var result = parser.parse(text);
        assertTrue(result.thirdParty() || !result.issues().isEmpty(), result.toString());
    }
    @ParameterizedTest @ValueSource(strings={"Сведения об отправителе требуют уточнения.",
            "Информация о плательщике уточняется.", "Данные о получателе отсутствуют."})
    void metalinguisticLabelsAreNotNames(String text) {
        var result = parser.parse(text);
        assertTrue(result.names().isEmpty());
        assertTrue(result.issues().stream().noneMatch(PurposeParser::identityIssue), result.toString());
    }
    @ParameterizedTest @ValueSource(strings={"Другов Елисей", "Другов", "???", "требуют уточнения Другов"})
    void metadataPrefixCannotHideAnUnresolvedActor(String actor) {
        String text="Договор ZX-583, ИНН 9900000017; Сведения об отправителе "+actor+".";
        var result=parser.parse(text);
        assertTrue(!result.names().isEmpty() || !result.issues().isEmpty(), "Unresolved actor must remain evidence: " + result);
        assertEquals(MatchingModel.Status.REVIEW,CorrectnessRemediationTest.decide(text,List.of(CorrectnessRemediationTest.CONTRACT)).status());
    }
    @ParameterizedTest @ValueSource(strings={"AB-0О1/26", "АB-001", "AB-001С", "A-1О"})
    void mixedSectionsRetainLiteralCharactersAndWarn(String identifier) {
        var result = parser.parse("По договору № " + identifier);
        assertEquals(List.of(identifier), result.contractNumbers());
        assertTrue(result.issues().stream().anyMatch(i -> i.startsWith("MIXED_SCRIPT_CONTRACT")));
    }
    @ParameterizedTest @ValueSource(strings={"SC-101/А", "X.1000020-А", "AB-001", "АБ-001"})
    void separatedScriptSectionsRemainLiteralWithoutConfusableFolding(String identifier) {
        var result = parser.parse("По договору № " + identifier);
        assertEquals(List.of(identifier), result.contractNumbers());
        assertTrue(result.issues().isEmpty(), result.toString());
    }
}
