package ru.urfu.alphamatcher.experiment;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowedTextTest {
    @Test void allPageBoundariesKeepUnicodeNormalizationAndWhitespaceState() {
        for(int page=1;page<=12;page++)for(int delta=-4;delta<=4;delta++) {
            String prefix="x ".repeat((page*8192+delta)/2);
            String text="\u0001 "+prefix+" И\u0306ванов A\u030a 😀 \u1100\u1161\u11a8\u0301\u0327  \t«номер»—конец \u0002";
            var actual=WindowedText.normalize(text,new ParsingBudget(5_000_000_000L,256));
            assertEquals(PurposeParser.normalize(text),actual.toString(),page+":"+delta);
            for(int edge=8192;edge<actual.length();edge+=8192) {
                int from=Math.max(0,edge-2),to=Math.min(actual.length(),edge+2);
                assertEquals(actual.toString().substring(from,to),actual.subSequence(from,to).toString());
            }
        }
    }
    @Test void fullLogicalInputFindsLateConflictsAndRetainsLegacySafeRestriction() {
        String prefix="Договор D-1, ИНН 9900000017; "+"x ".repeat(34000);
        for(String ending:List.of("ИНН 9900000031","договор D-2","по поручению ФИО Иванов Иван Иванович","получатель ИНН 9900000017","ФИО Сидоров Иван Иванович")) {
            var f=new PurposeParser(5_000_000_000L).parse(prefix+ending);
            assertTrue(f.issues().stream().anyMatch(s->s.startsWith("SIZE_LIMIT_REVIEW")));
            assertEquals(PurposeParser.normalize(prefix+ending),f.normalized());
            if(ending.startsWith("ИНН"))assertEquals(List.of("9900000017","9900000031"),f.inns());
            if(ending.startsWith("договор"))assertEquals(List.of("D-1","D-2"),f.contractNumbers());
            if(ending.startsWith("по поручению"))assertTrue(f.thirdParty());
            if(ending.startsWith("получатель"))assertTrue(f.roleClaims().stream().anyMatch(c->c.role().equals("RECIPIENT")));
            if(ending.startsWith("ФИО"))assertEquals(1,f.names().size());
        }
    }
    @Test void everySplitInsideIdentifiersNamesQuotesListsAndRolesIsInvisibleToRegex() {
        for(String evidence:List.of("договоры «ALPHA/BETA», AB-CD, ИНН 9900000017 / 9900000031", "получатель ФИО Иванова Ивана Ивановича", "от имени ООО «Альфа»", "Договор \"D-1", "ИНН 9900000017 / abc")) {
            for(int split=0;split<evidence.length();split++) {
                String text="x ".repeat((65536-split)/2)+evidence+" конец";
                var budget=new ParsingBudget(5_000_000_000L,256);var paged=WindowedText.normalize(text,budget);
                var a=new PurposeParser.Evidence();var b=new PurposeParser.Evidence();
                IdentifierRules.extract(PurposeParser.normalize(text),a,budget);IdentityRules.extract(PurposeParser.normalize(text),a,budget);ContextRules.uncertainActors(PurposeParser.normalize(text),a,budget);
                var otherBudget=new ParsingBudget(5_000_000_000L,256);
                IdentifierRules.extract(paged,b,otherBudget);IdentityRules.extract(paged,b,otherBudget);ContextRules.uncertainActors(paged,b,otherBudget);
                assertEquals(a.inns,b.inns);assertEquals(a.numbers,b.numbers);assertEquals(a.names,b.names);assertEquals(a.claims,b.claims);assertEquals(a.third,b.third);assertEquals(a.issues,b.issues);
            }
        }
    }
    @Test void boundsAndDeadlinesFailClosedRatherThanTreatPartialEvidenceAsComplete() {
        var parser=new PurposeParser();
        assertTrue(parser.parse("a"+"\u0301".repeat(64000)).issues().stream().anyMatch(s->s.startsWith("SIZE_LIMIT:")));
        assertTrue(parser.parse("x ".repeat(PurposeParser.MAX_INPUT_LENGTH/2+1)).issues().stream().anyMatch(s->s.startsWith("SIZE_LIMIT:")));
        assertTrue(new PurposeParser(1).parse("x ".repeat(35000)).issues().stream().anyMatch(s->s.contains("TIME_LIMIT")));
        assertTrue(parser.parse("x ".repeat(35000)+" ИНН 9900000017, ".repeat(300)).issues().stream().anyMatch(s->s.contains("MATCH_LIMIT")));
        var expired=new ParsingBudget(1,256);
        assertThrows(ParsingBudget.Limit.class,()->WindowedText.normalize("x ".repeat(35000),expired));
    }
}
