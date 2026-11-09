package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;

class GeneratedQualityMySqlIT {
    private Connection connection;
    @BeforeEach void open() throws Exception { connection=ExperimentDatabase.connect(); }
    @AfterEach void close() throws Exception { if(connection!=null) { connection.rollback(); connection.close(); } }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void generatedWorldsAreCheckedAgainstIndependentOracle(Profile profile) throws Exception {
        for(long seed:new long[]{7,20261029}) {
            var data=new SyntheticGenerator().generate(Config.standard(seed,24,48,220,profile));
            new SyntheticLoader().replace(connection,data);
            var counter=new QueryCounter(); var measured=counter.wrap(connection);
            var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
            var report=new QualityReport();
            for(var legacy:data.truth()) {
                var truth=Stage3QualityMain.referenceV3(legacy);
                var decision=engine.match(measured,truth.id());
                var errors=report.accept(truth,decision);
                assertEquals(truth.expectedStatus(),decision.status().name(),()->"seed="+seed+" "+truth+" "+decision+" "+errors);
                assertEquals(truth.safeContract(),decision.proposedContractId());
            }
            assertEquals(0L,report.counts().get("wrongSafe"));
            assertEquals(0L,report.counts().get("expectedSafeMissed"));
            assertTrue(counter.queries()>=data.payments().size());
            assertEquals(20+data.payments().size(),count("operation"));
        }
    }

    @Test
    void repeatedReplacePreservesBaselineAndDoesNotAccumulateRows() throws Exception {
        var data=new SyntheticGenerator().generate(Config.standard(1,24,48,100,Profile.MIXED));
        new SyntheticLoader().replace(connection,data); new SyntheticLoader().replace(connection,data);
        assertEquals(4+data.clients().size(),count("client")); assertEquals(9+data.contracts().size(),count("contract")); assertEquals(20+data.payments().size(),count("operation"));
        assertEquals(MatchingModel.Status.SAFE,new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser()).match(connection,1).status());
    }

    @Test
    void refusesRecordsOutsideGeneratedNamespaceBeforeDeletingAnything() throws Exception {
        var data=new SyntheticGenerator().generate(Config.standard(1,24,48,10,Profile.SIMPLE));
        var forged=new Dataset(data.config(),java.util.List.of(new Client(1,"LEGAL_ENTITY","9900000017",null,null,null,"Wrong")),data.contracts(),data.payments(),data.truth());
        assertThrows(IllegalArgumentException.class,()->new SyntheticLoader().replace(connection,forged));
        assertEquals(20,count("operation")); assertEquals(4,count("client"));
    }

    @Test
    void quotedAndUnicodeDashNumbersDoNotMissTheExistingContract() throws Exception {
        try(var statement=connection.createStatement()) { statement.executeUpdate("UPDATE contract SET contract_number='D–001' WHERE id=101"); }
        var result=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser()).match(connection,1);
        assertEquals(MatchingModel.Status.SAFE,result.status()); assertEquals(101L,result.proposedContractId());
    }

    @Test
    void nameOnlyLookupFindsDeclinedNameAcrossYoAndCaseVariants() throws Exception {
        try(var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE client SET last_name='Петров',first_name='Пётр',middle_name='Петрович' WHERE id=4");
            statement.executeUpdate("UPDATE operation SET payment_purpose='ФИО Петрова Петра Петровича' WHERE id=16");
        }
        var result=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser()).match(connection,16);
        assertEquals(MatchingModel.Status.REVIEW,result.status());
        assertTrue(result.candidateIds().contains(108L));
        assertEquals("петров петр петрович",result.features().names().getFirst().key());
    }

    @Test
    void organizationOnlyLookupFindsMasterAndExternalPartyNamesButNeverBecomesSafe() throws Exception {
        try(var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE client SET name='ООО «Ёлка 42»' WHERE id=4");
            statement.executeUpdate("UPDATE contract_party SET name='АО «Ёлка 43»' WHERE contract_id=105 AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER')");
            statement.executeUpdate("UPDATE operation SET payment_purpose='ООО \"Елка 42\"' WHERE id=16");
        }
        var repository=new JdbcMatchingRepository(); var parser=new PurposeParser();
        assertTrue(repository.candidates(connection,parser.parse("АО \"Елка 43\"")).stream().anyMatch(c->c.id()==105));
        var result=new MatchingEngine(repository,parser).match(connection,16);
        assertEquals(MatchingModel.Status.REVIEW,result.status()); assertTrue(result.candidateIds().contains(108L));
    }

    @Test
    void oversizedPurposeIsReviewRatherThanAbortingEntireRun() throws Exception {
        try(var statement=connection.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=1")) {
            statement.setString(1,"x".repeat(5000)); statement.executeUpdate();
        }
        assertEquals(MatchingModel.Status.REVIEW,new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser()).match(connection,1).status());
    }

    private long count(String table) throws Exception {
        try(var statement=connection.createStatement(); var rs=statement.executeQuery("SELECT COUNT(*) FROM "+table)) { rs.next(); return rs.getLong(1); }
    }
}
