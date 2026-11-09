package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class QualityGeneralizationTest {
    @ParameterizedTest @CsvSource(delimiter='|', value={
        "Гринько Олеси Валерьевны|Гринько Олеся Валерьевна",
        "Вороновой Алины Олеговны|Воронова Алина Олеговна",
        "Бенуа Этьена Карловича|Бенуа Этьен Карлович",
        "Хасановой Лейлы Ринатовны|Хасанова Лейла Ринатовна",
        "Фроловой Эльвиры Никитичны|Фролова Эльвира Никитична",
        "Блок Злате Михайловне|Блок Злата Михайловна",
        "Блок Златой Михайловной|Блок Злата Михайловна",
        "Бардин Златой Михайловной|Бардин Злата Михайловна",
        "Гай Мэри Павловной|Гай Мэри Павловна",
        "Моруа Ренату Викторовичу|Моруа Ренат Викторович",
        "Делакруа Рене Карловичем|Делакруа Рене Карлович",
        "Шмидта Арсения Никитича|Шмидт Арсений Никитич",
        "Майеру Евсею Карловичу|Майер Евсей Карлович",
        "Кимом Львом Ивановичем|Ким Лев Иванович",
        "Бенуа-Шмидта Этьена Карловича|Бенуа-Шмидт Этьен Карлович",
        "Ким-Фроловой Аделины Павловны|Ким-Фролова Аделина Павловна",
        "Вороновой Юлии Олеговны|Воронова Юлия Олеговна",
        "Вороновой Дарье Олеговне|Воронова Дарья Олеговна",
        "Вороновой Зое Олеговне|Воронова Зоя Олеговна"})
    void forwardParadigmsUseTheWholeKnownDbName(String observed,String known) {
        assertTrue(NameNormalizer.matches(observed,known), observed);
    }
    @ParameterizedTest @CsvSource(delimiter='|', value={
        "Хасановой Лейлы Ринатович|Хасанова Лейла Ринатовна",
        "Хасановой Лейле Ринатовны|Хасанова Лейла Ринатовна",
        "Хасанову Лейлы Ринатовны|Хасанова Лейла Ринатовна",
        "Хасановой Лианы Ринатовны|Хасанова Лейла Ринатовна",
        "Хасановой Лейлы Романовны|Хасанова Лейла Ринатовна",
        "Бенуи Этьена Карловича|Бенуа Этьен Карлович",
        "Бенуа Этьену Карловича|Бенуа Этьен Карлович",
        "Бенуа Этьеном Карловича|Бенуа Этьен Карлович",
        "Кима Златы Михайловны|Ким Злата Михайловна",
        "Шмидт Арсения Никитича|Шмидт Арсений Никитич",
        "Бенуа-Шмидт Этьена Карловича|Бенуа-Шмидт Этьен Карлович",
        "Ким Лева Ивановича|Ким Лев Иванович",
        "Дарвиным Ренатом Карловичем|Дарвин Ренат Карлович",
        "Ким Л. И.|Ким Лев Иванович",
        "Вороновой Зои Олеговне|Воронова Зоя Олеговна",
        "Вороновой Юлие Олеговне|Воронова Юлия Олеговна"})
    void noEditDistanceMixedCasesWrongGenderOrInventedStems(String observed,String known) {
        assertFalse(NameNormalizer.matches(observed,known), observed);
    }
    @ParameterizedTest @ValueSource(strings={
        "Договоры D-1, D-2", "Договоры № D-1, № D-2", "По договорам D-1 и D-2",
        "Договоров D-1; D-2", "Договоры \"D-1\", \"D-2\"", "Договоры N D-1, N D-2"})
    void pluralAndRepeatedMarkersDoNotLoseTheSecondNumber(String text) {
        assertEquals(List.of("D-1","D-2"),new PurposeParser().parse(text).contractNumbers());
        assertEquals(Status.REVIEW, decision(text+", ИНН 9900000017").status());
    }
    @Test void markerNIsNotPartOfAnIdentifierUnlessSeparated() {
        assertEquals(List.of("N123"),new PurposeParser().parse("Договор N123").contractNumbers());
        assertEquals(List.of("N-123","N-124"),new PurposeParser().parse("Договоры № N-123, № N-124").contractNumbers());
    }
    @ParameterizedTest @ValueSource(strings={"Договор D-1, (ИНН 9900000017)","Договор D-1, [INN 9900000017]"})
    void aParenthesizedNextFieldIsNotTreatedAsAnotherContractNumber(String text) {
        assertTrue(new PurposeParser().parse(text).issues().isEmpty());
        assertEquals(Status.SAFE,decision(text).status());
    }
    @ParameterizedTest @ValueSource(strings={"Договоры D-1, №", "Договоры D-1, @D-2", "Договоры D-1, № \"", "Договоры AA, ИНН 9900000017"})
    void malformedOrUnsupportedListCannotLeaveAFalseSafeFirstNumber(String text) {
        var features=new PurposeParser().parse(text);
        assertFalse(features.issues().isEmpty());
        assertEquals(Status.REVIEW,decision(text+", ИНН 9900000017").status());
    }
    @ParameterizedTest @ValueSource(strings={"получатель ИНН плательщика 9900000017", "плательщик ИНН получателя 9900000024"})
    void suffixCannotOverwriteAConflictingPrefixRole(String text) {
        var parsed=new PurposeParser().parse("Договор D-1, "+text);
        assertTrue(parsed.issues().stream().anyMatch(i->i.contains("CONFLICTING_ROLE_CLAIMS")));
        assertEquals(2,parsed.roleClaims().size());
        assertEquals(Status.REVIEW,decision("Договор D-1, "+text).status());
    }
    @Test void generatedUnknownNameFormsNeverBecomeStandaloneLexicalCertainty() {
        assertFalse(NameNormalizer.resolve("Хасановой Лейлы Ринатовны").recognized());
        assertFalse(NameNormalizer.resolve("Бенуа Этьена Карловича").recognized());
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "Петровой-Сидоровой Татьяны Александровны|петрова-сидорова татьяна александровна",
        "Романовой-Соколовой Ирине Павловне|романова-соколова ирина павловна"})
    void adjectiveLikeObliqueSurnameCannotBecomeAnInventedIndeclinableCanonical(String surface,String expected) {
        assertEquals(expected,NameNormalizer.resolve(surface).key());
    }
    @Test void ambiguousAdjectiveLikeForeignSurnameNeedsAdditionalMorphologicalEvidence() {
        assertFalse(NameNormalizer.matches("Топчий Аделины Павловны","Топчий Аделина Павловна"));
    }
    @Test void multipleInnsAndThirdPartiesRemainManualEvenAfterBetterNames() {
        for(String suffix:List.of(", ИНН 9900000031", ", от имени ООО «Маяк»", ", за клиента"))
            assertEquals(Status.REVIEW,decision("Договор D-1, ИНН 9900000017"+suffix).status());
    }
    private static Decision decision(String purpose) {
        var parties=Map.of("PAYER",new Party(1L,"9900000017","9900000017",true),"RECIPIENT",new Party(2L,"9900000024","9900000024",true));
        return MatchingEngine.evaluate(new Operation(1,purpose,"LOADED",true,null,parties),new PurposeParser().parse(purpose),List.of(new Candidate(1,"D-1",parties)));
    }
}
