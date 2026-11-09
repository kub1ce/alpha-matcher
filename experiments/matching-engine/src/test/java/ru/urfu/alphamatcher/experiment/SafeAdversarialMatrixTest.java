package ru.urfu.alphamatcher.experiment;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class SafeAdversarialMatrixTest {
    @Test void independentNegativeConditionsReachTheirOwnGuard()throws Exception{
        String base="Договор ZX-583, ИНН 9900000017; ";var cases=new LinkedHashMap<String,String[]>();
        cases.put("conflicting-inn",new String[]{"Договор ZX-583, ИНН 9900000031","PURPOSE_INN"});
        cases.put("wrong-person",new String[]{base+"Плательщик: Другов Елисей Захарович","PURPOSE_NAMES"});
        cases.put("wrong-role",new String[]{base+"Получатель: Туманов Елисей Захарович","ROLE_CLAIMS"});
        cases.put("third-party",new String[]{base+"Оплата за третье лицо","NO_THIRD_PARTY"});
        cases.put("unknown-representative",new String[]{base+"По поручению доверителя","NO_THIRD_PARTY"});
        cases.put("conflicting-organization",new String[]{base+"ООО «Посторонняя организация»","PURPOSE_ORGANIZATIONS"});
        cases.put("partial-name",new String[]{base+"Плательщик: Туманов Е.З.","PURPOSE_NAMES"});
        cases.put("unsupported-explicit-actor",new String[]{base+"Payer: ???","PURPOSE_NAMES"});
        cases.put("unicode-format",new String[]{"Договор ZX-583\u2060., ИНН 9900000017","CONTRACT_ANALYSIS_COMPLETE"});
        cases.put("interpretation-budget",new String[]{"Договор ZZ-AA-BB-CC-DD., ИНН 9900000017","CONTRACT_ANALYSIS_COMPLETE"});
        cases.put("size-budget",new String[]{base+"x".repeat(PurposeParser.MAX_INPUT_LENGTH),"CONTRACT_ANALYSIS_COMPLETE"});
        var captured=new StringBuilder();
        for(var entry:cases.entrySet()){
            var d=CorrectnessRemediationTest.decide(entry.getValue()[0],List.of(CorrectnessRemediationTest.CONTRACT));
            assertEquals(Status.REVIEW,d.status(),entry.getKey());assertNull(d.proposedContractId());
            assertTrue(d.checks().stream().anyMatch(c->c.code().equals(entry.getValue()[1])&&!c.passed()),entry.getKey()+" "+d);
            captured.append(entry.getKey()).append(" expected failed gate ").append(entry.getValue()[1]).append('\n').append(d).append('\n');
        }
        var sameNameOtherId=new Party(99L,"9900000017","9900000017",true,null,null,"Туманов","Елисей","Захарович");
        var mismatched=new Candidate(101,"ZX-583",Map.of("PAYER",sameNameOtherId,"RECIPIENT",CorrectnessRemediationTest.RECIPIENT));
        var identity=CorrectnessRemediationTest.decide(base+"Плательщик: Туманов Елисей Захарович",List.of(mismatched));
        assertEquals(Status.REVIEW,identity.status());SafeDiscoveryTest.passed(identity,"PURPOSE_NAMES");
        assertTrue(identity.checks().stream().anyMatch(c->c.code().equals("PARTY_PAYER")&&!c.passed()));captured.append("same-name-other-id\n").append(identity);
        var duplicate=CorrectnessRemediationTest.decide(base,List.of(CorrectnessRemediationTest.CONTRACT,new Candidate(102,"ZX-583",CorrectnessRemediationTest.PARTIES)));
        assertEquals(Status.REVIEW,duplicate.status());SafeDiscoveryTest.passed(duplicate,"CANDIDATE_SEARCH_COMPLETE");
        assertTrue(duplicate.checks().stream().anyMatch(c->c.code().equals("EXPLICIT_UNIQUE_CONTRACT")&&!c.passed()));captured.append("\ndifferent-contract-ids\n").append(duplicate);
        var timeout=new PurposeParser(1).parse(base);var op=new Operation(1,base,"LOADED",true,null,CorrectnessRemediationTest.PARTIES);
        assertEquals(Status.REVIEW,MatchingEngine.evaluate(op,timeout,CandidateSearch.declaredFixture(timeout,List.of(CorrectnessRemediationTest.CONTRACT))).status());
        String root=System.getenv("STAGE4F_ROOT");if(root!=null)Files.writeString(Path.of(root,"audit/adversarial-gates-"+System.nanoTime()+".txt"),captured,StandardOpenOption.CREATE_NEW);
    }
}
