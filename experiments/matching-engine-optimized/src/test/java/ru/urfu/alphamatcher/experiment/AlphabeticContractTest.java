package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AlphabeticContractTest {
    @ParameterizedTest @ValueSource(strings={"ALPHA/BETA","AB-CD","АБ/ВГ","ABC_DEF","AA.BB","CAFÉ/ÉTÉ"})
    void structuredUppercaseIdentifiersNeedNoDigit(String number) {
        var f=new PurposeParser().parse("Договор "+number+", ИНН 9900000017");
        assertEquals(List.of(number),f.contractNumbers());assertTrue(f.issues().isEmpty(),f.issues().toString());
    }
    @ParameterizedTest @ValueSource(strings={"Договор № ALPHA","Договор # ALPHA","Договор N ALPHA","Договор \"ALPHA\"","Договор № alpha","Договор № alpha/beta"})
    void explicitNumberContextAllowsOtherwiseAmbiguousLetterWord(String text) {
        var f=new PurposeParser().parse(text+", ИНН 9900000017");assertTrue(f.issues().isEmpty(),f.issues().toString());assertEquals(1,f.contractNumbers().size());
    }
    @ParameterizedTest @ValueSource(strings={"Договор поставки","Договор № поставки","Договор КУПЛИ-ПРОДАЖИ","Договор купли-продажи","Договор ALPHA","Договоры AA","Договор по","Договор ИНН 9900000017","Договор alpha/beta"})
    void proseAndUnmarkedLetterWordsAreNotInventedIdentifiers(String text) {
        var f=new PurposeParser().parse(text);assertTrue(f.contractNumbers().isEmpty());assertFalse(f.issues().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"Договор AB- CD","Договор ALPHA//BETA","Договор ALPHA/BETA/","Договор № ALPHA/BETA!","Договор \"ALPHA/BETA","Договоры ALPHA/BETA, №","Договоры ALPHA/BETA, @AB-CD","Договоры ALPHA/BETA AB-CD"})
    void incompleteOrUnseparatedEvidenceBlocksAutomaticDecision(String text) {
        assertFalse(new PurposeParser().parse(text+", ИНН 9900000017").issues().isEmpty(),text);
    }
    @ParameterizedTest @ValueSource(strings={"Договоры ALPHA/BETA, AB-CD","Договоры ALPHA/BETA; AB-CD","Договоры ALPHA/BETA и AB-CD","Договоры ALPHA/BETA / AB-CD","Договоры № ALPHA/BETA, № AB-CD"})
    void listNeverSilentlyPicksFirst(String text) {
        var f=new PurposeParser().parse(text+", ИНН 9900000017");assertEquals(List.of("ALPHA/BETA","AB-CD"),f.contractNumbers());assertTrue(f.issues().isEmpty(),f.issues().toString());
    }
    @ParameterizedTest @ValueSource(strings={"Договор AВ-CD","Договор ΑLPHA/BETA","Договор ALPHA/БЕТА","Договор AB\u200b-CD"})
    void newAlphabeticSupportDoesNotFoldMixedScriptsOrFormatControls(String text) {assertFalse(new PurposeParser().parse(text+", ИНН 9900000017").issues().isEmpty());}
    @Test void fieldsAndDashVariantsRetainTheirBoundariesAndResourceLimits() {
        var f=new PurposeParser().parse("Договор AB—CD, ИНН 9900000017, ФИО Иванов Иван Иванович");
        assertEquals(List.of("AB-CD"),f.contractNumbers());assertEquals(List.of("9900000017"),f.inns());assertEquals(1,f.names().size());assertTrue(f.issues().isEmpty());
        assertFalse(new PurposeParser().parse("Договор AA-BB-CC-DD-EE, ИНН 9900000017").issues().isEmpty());
    }
    @Test void repeatedContractLabelIsNotAbsorbedAsAnotherAlphabeticNumber() {
        var f=new PurposeParser().parse("Договор № ALPHA, договор № BETA, ИНН 9900000017");
        assertEquals(List.of("ALPHA","BETA"),f.contractNumbers());assertTrue(f.issues().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"x x x ","описание выполненных услуг "})
    void ordinaryNarrativeAfterNumericNumberDoesNotBecomeAnUnsupportedIdentifier(String prose) {
        var f=new PurposeParser().parse("Договор ZQ-901, "+prose.repeat(20)+" ИНН 9900000017");
        assertEquals(List.of("ZQ-901"),f.contractNumbers());assertTrue(f.issues().isEmpty(),f.issues().toString());
    }
    @Test void ambiguousNewAlphabeticOrPluralContinuationCannotSilentlyChooseFirst() {
        for(String text:List.of("Договор № ALPHA, beta","Договор ALPHA/BETA, gamma","Договоры D-1, beta"))
            assertFalse(new PurposeParser().parse(text+", ИНН 9900000017").issues().isEmpty(),text);
    }
}
