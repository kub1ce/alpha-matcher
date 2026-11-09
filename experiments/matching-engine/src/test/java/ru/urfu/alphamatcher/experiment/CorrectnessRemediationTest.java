package ru.urfu.alphamatcher.experiment;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** New development cases; no v4 examples, oracle or predictions are imported. */
class CorrectnessRemediationTest {
    static final Party PAYER=new Party(1L,"9900000017","9900000017",true,
            null,null,"Туманов","Елисей","Захарович");
    static final Party RECIPIENT=new Party(2L,"9900000024","9900000024",true);
    static final Map<String,Party> PARTIES=Map.of("PAYER",PAYER,"RECIPIENT",RECIPIENT);
    static final Candidate CONTRACT=new Candidate(101,"ZX-583",PARTIES);
    static Decision decide(String text,List<Candidate> candidates) {
        return DiscoveryTestSupport.evaluate(new Operation(1,text,"LOADED",true,null,PARTIES),new PurposeParser().parse(text),candidates);
    }
    static String purpose(String reference) { return "Договор "+reference+" ИНН 9900000017"; }
    static void passed(Decision decision,String gate) {
        assertTrue(decision.checks().stream().anyMatch(c->c.code().equals(gate)&&c.passed()),decision.toString());
    }
    @ParameterizedTest @ValueSource(strings={"ZX-583.","ZX-583. ИНН 9900000017"})
    void sentenceDotHasTwoBoundedCatalogInterpretations(String reference) {
        String text=reference.contains("ИНН")?"Договор "+reference:"ИНН 9900000017; Договор "+reference;
        var f=new PurposeParser().parse(text);
        assertTrue(f.issues().isEmpty(),f.toString());
        assertEquals(List.of("ZX-583.","ZX-583"),f.contractSearchNumbers());
        assertEquals(1,f.contractNumbers().size());
        var safe=decide(text,List.of(CONTRACT));assertEquals(Status.SAFE,safe.status());passed(safe,"PURPOSE_NAMES");
        var dotted=new Candidate(102,"ZX-583.",PARTIES);
        assertEquals(Status.SAFE,decide(text,List.of(dotted)).status());
        var ambiguous=decide(text,List.of(CONTRACT,dotted));
        assertEquals(Status.REVIEW,ambiguous.status());assertNull(ambiguous.proposedContractId());
    }
    @ParameterizedTest @ValueSource(strings={"\"ZX-583.\"","\"ZX-583.\".","\"ZX.583\"","ZX.583"})
    void genuineDotsAndQuotedBoundariesAreNotTrimmed(String reference) {
        var f=new PurposeParser().parse(purpose(reference));
        String expected=reference.contains("ZX-583.")?"ZX-583.":"ZX.583";
        assertEquals(List.of(expected),f.contractSearchNumbers());assertTrue(f.issues().isEmpty(),f.toString());
        assertEquals(Status.SAFE,decide(purpose(reference),List.of(new Candidate(101,expected,PARTIES))).status());
        assertEquals(Status.REVIEW,decide(purpose(reference),List.of(CONTRACT)).status());
    }
    @Test void alphabeticFinalDotAndQuotedLiteralAreDistinct() {
        var unquoted=new PurposeParser().parse("Договор № QRS. ИНН 9900000017");
        assertTrue(unquoted.issues().isEmpty(),unquoted.toString());assertEquals(List.of("QRS.","QRS"),unquoted.contractSearchNumbers());
        var quoted=new PurposeParser().parse("Договор № \"QRS.\". ИНН 9900000017");
        assertTrue(quoted.issues().isEmpty(),quoted.toString());assertEquals(List.of("QRS."),quoted.contractSearchNumbers());
    }
    @Test void alternativeExpansionRemainsBoundedAndEvidenceCannotChooseTwoObligations() {
        var tooMany=new PurposeParser().parse("Договор ZZ-AA-BB-CC-DD. ИНН 9900000017");
        assertTrue(tooMany.issues().stream().anyMatch(i->i.startsWith("MATCH_LIMIT")));
        var d=decide("Договоры ZX-583, ZX-584, ИНН 9900000017",List.of());
        assertEquals(Status.REVIEW,d.status());assertNull(d.proposedContractId());
        assertEquals(Status.NO_CONTRACT,decide("Договор ZX-584, ИНН 9900000017",List.of()).status());
    }
    @ParameterizedTest @ValueSource(strings={"Тумaнов Елисей Захарович","Туманов Елиcей Захарович",
            "Туманов Елисей Зaхарович","Τуманов Елисей Захарович","Tumanov Elisei Zakharovich",
            "Туманов Елисей Назарович","Туманов Елисей","???"})
    void explicitUnconfirmedActorReachesIdentityGuardAndCannotHideBehindInn(String value) {
        var d=decide("Договор ZX-583, ИНН 9900000017; Плательщик: "+value,List.of(CONTRACT));
        assertEquals(Status.REVIEW,d.status());assertNull(d.proposedContractId());
        passed(d,"INPUT_VALID");passed(d,"CONTRACT_NUMBER");passed(d,"PARTY_PAYER");passed(d,"PURPOSE_INN");
        assertTrue(d.checks().stream().anyMatch(c->c.code().equals("PURPOSE_NAMES")&&!c.passed()),d.toString());
        assertTrue(!d.features().names().isEmpty() || d.features().issues().stream().anyMatch(PurposeParser::identityIssue));
        assertEquals(Status.REVIEW,decide("Договор MISSING-586, ИНН 9900000017; Плательщик: "+value,List.of()).status());
    }
    @Test void absentNameAndConfirmedNameAreSeparateSupportedStates() {
        var absent=decide("Договор ZX-583, ИНН 9900000017",List.of(CONTRACT));
        var observed=decide("Договор ZX-583, ИНН 9900000017; Плательщик: Туманов Елисей Захарович",List.of(CONTRACT));
        assertEquals(Status.SAFE,absent.status());assertTrue(absent.features().names().isEmpty());
        assertEquals(Status.SAFE,observed.status());assertEquals(1,observed.features().names().size());
        passed(observed,"PURPOSE_NAMES");passed(observed,"ROLE_CLAIMS");
    }
    @Test void observedInitialsRetainRoleWithoutBecomingFullIdentity() {
        var f=new PurposeParser().parse("Плательщик: Туманов Е.З.; договор ZX-583, ИНН 9900000017");
        assertTrue(f.roleClaims().stream().anyMatch(c->c.kind().equals("NAME")&&c.role().equals("PAYER")));
        assertEquals(Status.REVIEW,decide(f.normalized(),List.of(CONTRACT)).status());
    }
}
