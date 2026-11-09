package ru.urfu.alphamatcher.experiment;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;
import ru.urfu.alphamatcher.experiment.PurposeParser.*;

class SafeDiscoveryTest {
    private static final Map<String,Party> PARTIES=CorrectnessRemediationTest.PARTIES;
    static final List<String> TERMINATORS=List.of(""," ","\n","\r\n","; ",", ",";,; ",") ","] ","! ","? ",": ","； ","， ","\u00a0","\u2028");
    static String purpose(String n,String ending){return "ИНН 9900000017; Договор № "+n+"."+ending;}
    static Decision apply(String text,List<Candidate> candidates){return DiscoveryTestSupport.evaluate(new Operation(1,text,"LOADED",true,null,PARTIES),new PurposeParser().parse(text),candidates);}
    static void passed(Decision d,String gate){assertTrue(d.checks().stream().anyMatch(c->c.code().equals(gate)&&c.passed()),d.toString());}
    @Test void generatedTerminatorCatalogMatrixReachesUniquenessForEveryCompleteCollision(){
        int count=0;
        for(String n:List.of("BC-468","574932","ZX.YT"))for(String ending:TERMINATORS){
            String text=purpose(n,ending);var f=new PurposeParser().parse(text);
            assertEquals(Completeness.COMPLETE,f.contractAnalysis(),text+" "+f.issues());assertEquals(List.of(n+".",n),f.contractSearchNumbers());
            Candidate plain=new Candidate(91,n,PARTIES),dotted=new Candidate(92,n+".",PARTIES);
            for(Candidate only:List.of(plain,dotted)){var d=apply(text,List.of(only));assertEquals(Status.SAFE,d.status(),d.toString());passed(d,"PURPOSE_NAMES");passed(d,"ROLE_CLAIMS");}
            var collision=apply(text,List.of(plain,dotted));assertEquals(Status.REVIEW,collision.status());assertNull(collision.proposedContractId());
            passed(collision,"INPUT_VALID");passed(collision,"OPERATION_PARTIES");passed(collision,"CANDIDATE_SEARCH_COMPLETE");
            assertTrue(collision.checks().stream().anyMatch(c->c.code().equals("EXPLICIT_UNIQUE_CONTRACT")&&!c.passed()));
            assertEquals(Status.NO_CONTRACT,apply(text,List.of()).status());count++;
        }
        assertEquals(48,count);
    }
    @Test void quotesInternalDotsBracketedReferencesAndDotRunsRetainAllMeanings(){
        for(String ref:List.of("\"BC-468.\"","«BC-468.»","[\"BC-468.\"]","(\"BC-468.\")")){
            String text="Договор № "+ref+".; ИНН 9900000017";
            var f=new PurposeParser().parse(text);assertEquals(Completeness.COMPLETE,f.contractAnalysis(),f.toString());assertEquals(List.of("BC-468."),f.contractSearchNumbers());
        }
        var internal=new PurposeParser().parse("Договор № BC.468, ИНН 9900000017");assertEquals(List.of("BC.468"),internal.contractSearchNumbers());
        var dots=new PurposeParser().parse("Договор № BC-468...; ИНН 9900000017");
        assertEquals(List.of("BC-468...","BC-468..","BC-468.","BC-468"),dots.contractSearchNumbers());assertEquals(Completeness.COMPLETE,dots.contractAnalysis());
        assertEquals(Completeness.INCOMPLETE,new PurposeParser().parse("Договор № BC-468.........; ИНН 9900000017").contractAnalysis());
    }
    @Test void multipleTextAliasesCanResolveToOneIdButNeverDiscardASecondContract(){
        var plain=new Candidate(91,"BC-468",PARTIES);String text="Договор № BC-468.; договор № \"BC-468\"; ИНН 9900000017";
        assertEquals(Status.SAFE,apply(text,List.of(plain,plain)).status());
        assertEquals(Status.REVIEW,apply(text,List.of(plain,new Candidate(92,"BC-468.",PARTIES))).status());
        assertEquals(Status.REVIEW,apply("Договоры № BC-468.; № BC-469; ИНН 9900000017",List.of(plain)).status());
        assertEquals(Status.REVIEW,apply("Договоры № BC-468.)!;, № BC-469; ИНН 9900000017",List.of(plain)).status());
    }
    @Test void engineRejectsUnknownPartialCoverageMissingBindingsAndDiscardedInterpretations(){
        String text=purpose("BC-468","; ");var f=new PurposeParser().parse(text);var op=new Operation(1,text,"LOADED",true,null,PARTIES);var candidate=new Candidate(92,"BC-468.",PARTIES);
        assertEquals(Status.REVIEW,MatchingEngine.evaluate(op,f,List.of(candidate)).status());
        assertEquals(Status.REVIEW,MatchingEngine.evaluate(op,f,List.of()).status(),"Unknown completeness cannot prove absence");
        var full=CandidateSearch.declaredFixture(f,List.of(candidate)).evidence();
        for(var status:Completeness.values()){
            var partial=new CandidateSearch.Evidence(status,full.requiredKeys(),List.of(full.searchedKeys().getFirst()),full.boundParameters(),full.route(),true,full.origin(),"Injected partial search");
            var d=MatchingEngine.evaluate(op,f,new CandidateSearch.Result(List.of(candidate),partial));assertEquals(Status.REVIEW,d.status());
            assertTrue(d.checks().stream().anyMatch(c->c.code().equals("CANDIDATE_SEARCH_COMPLETE")&&!c.passed()));
        }
        var noCursor=new CandidateSearch.Evidence(Completeness.COMPLETE,full.requiredKeys(),full.searchedKeys(),full.boundParameters(),full.route(),false,full.origin(),"Cursor incomplete");
        assertEquals(Status.REVIEW,MatchingEngine.evaluate(op,f,new CandidateSearch.Result(List.of(candidate),noCursor)).status());
        var badReference=new ContractReference("BC-468.",List.of("BC-468."),false,List.of("Falsely declared complete"),Completeness.COMPLETE);
        var dropped=new Features(f.normalized(),f.inns(),f.invalidInns(),f.contractNumbers(),f.thirdParty(),f.issues(),f.names(),f.organizations(),f.roleClaims(),List.of(badReference),Completeness.COMPLETE);
        assertEquals(Status.REVIEW,MatchingEngine.evaluate(op,dropped,CandidateSearch.declaredFixture(dropped,List.of(candidate))).status());
    }
    @Test void adjacentMarkersAndCompactPluralSlashNotationCannotLoseObligations(){
        for(String text:List.of("Договоры № BC-468. № BC-469; ИНН 9900000017",
                "Договоры № BC-468. ABC; ИНН 9900000017","Договоры № ALPHA/BETA; ИНН 9900000017")){
            var f=new PurposeParser().parse(text);assertEquals(Completeness.INCOMPLETE,f.contractAnalysis(),f.toString());
            assertEquals(Status.REVIEW,apply(text,List.of(new Candidate(91,"BC-468.",PARTIES))).status());
        }
    }
    @ParameterizedTest @ValueSource(strings={"BC-468'","\"BC-468.","[BC-468.","BC-468·","BC-468\u200b.","AA-BB-CC-DD-EE.","BC-468.\u0085","BC-468.\u001c"})
    void unsupportedOrOverBudgetEvidenceNeverBecomesSafe(String reference){
        String text="Договор № "+reference+"; ИНН 9900000017";
        assertEquals(Status.REVIEW,apply(text,List.of(new Candidate(91,"BC-468",PARTIES))).status());
    }
}
