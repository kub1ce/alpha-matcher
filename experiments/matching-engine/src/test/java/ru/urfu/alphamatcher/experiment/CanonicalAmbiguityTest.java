package ru.urfu.alphamatcher.experiment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalAmbiguityTest {
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "Мельник Аксиньи Яковлевны|Мельник Аксинья Яковлевна|Мельник Аксиния Яковлевна|false",
        "Саито Наоми Викторовны|Саито Наоми Викторовна|Саито Наима Викторовна|false",
        "Крутых Агнии Денисовны|Крутых Агния Денисовна|Крутых Агнесса Денисовна|false",
        "Черняку Илье Кузьмичу|Черняк Илья Кузьмич|Черняк Илия Кузьмич|false",
        "Хартманн Мишель Альбертовны|Хартманн Мишель Альбертовна|Хартманн Мишэль Альбертовна|true",
        "Серых Эвелины Юрьевны|Серых Эвелина Юрьевна|Серых Эльвина Юрьевна|false"})
    void canonicalHypothesisNeverReplacesCompleteDbComparison(String source,String known,String wrong,boolean resolved) {
        var mention=NameNormalizer.resolve(source);
        assertEquals(source,mention.surface());assertFalse(mention.recognized());
        assertEquals(NameNormalizer.key(resolved?known:source),mention.key());
        assertTrue(NameNormalizer.confirm(source,known).fullNameMatched());
        assertFalse(NameNormalizer.confirm(source,wrong).fullNameMatched());
        assertFalse(NameNormalizer.confirm(source,known.replace(known.substring(known.lastIndexOf(' ')+1),"Иванович")).fullNameMatched());
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "Мельник Аксиньи Яковлевны|Мельник Аксинья Яковлевна|Мельник Аксиньи Яковлевна",
        "Саито Наоми Викторовны|Саито Наоми Викторовна|Саито Наомя Викторовна",
        "Крутых Агнии Денисовны|Крутых Агния Денисовна|Крутых Агнии Денисовна",
        "Черняку Илье Кузьмичу|Черняк Илья Кузьмич|Черняку Илья Кузьмич",
        "Серых Эвелины Юрьевны|Серых Эвелина Юрьевна|Серых Эвелины Юрьевна"})
    void indistinguishableForwardFormsRemainUnresolvedWithoutExternalLexicalKnowledge(String source,String one,String two) {
        assertTrue(NameNormalizer.matches(source,one));assertTrue(NameNormalizer.matches(source,two));
        assertEquals(NameNormalizer.key(source),NameNormalizer.resolve(source).key());
    }
}
