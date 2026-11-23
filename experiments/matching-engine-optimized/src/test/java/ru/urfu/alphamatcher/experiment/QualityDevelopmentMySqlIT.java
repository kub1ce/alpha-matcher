package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QualityDevelopmentMySqlIT {
    private final Path root=Path.of(System.getProperty("basedir","."));
    private final MatchingEngine engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
    @ParameterizedTest @ValueSource(longs={32000102L,32000104L,32000106L})
    void formerlyMissedRareDeclinedNamesUseRealDbIdentity(long id) throws Exception {
        var cases=Stage3Corpus.read(root.resolve("stage3/corpora/final-v1.tsv"));
        try(var c=ExperimentDatabase.connect()) {
            try {
                Stage3Corpus.load(c,root.resolve("stage3/corpora/world-v1.sql"),cases);
                var truth=cases.stream().filter(value->value.id()==id).findFirst().orElseThrow().truth();
                var result=engine.match(c,id);
                assertEquals("SAFE",truth.expectedStatus());
                assertEquals(MatchingModel.Status.SAFE,result.status());
                assertEquals(truth.safeContract(),result.proposedContractId());
            } finally { c.rollback(); }
        }
    }
    @Test void knownV2NameAndPluralAreCorrectedWithoutRelaxingDuplicateNumberPolicy() throws Exception {
        var cases=Stage3Corpus.read(root.resolve("stage3/corpora/final-v2.tsv"));
        try(var c=ExperimentDatabase.connect()) {
            try {
                Stage3Corpus.load(c,root.resolve("stage3/corpora/world-v2.sql"),cases);
                var name=engine.match(c,32000509L);
                assertEquals(MatchingModel.Status.SAFE,name.status());
                assertEquals(31000506L,name.proposedContractId());
                var list=engine.match(c,32000518L);
                assertEquals(List.of("V2-702","V2-703"),list.features().contractNumbers());
                assertEquals(List.of(31000504L,31000505L),list.candidateIds());
                assertEquals(MatchingModel.Status.REVIEW,list.status());
                var duplicate=engine.match(c,32000501L);
                assertEquals(MatchingModel.Status.REVIEW,duplicate.status());
                assertEquals(List.of(31000501L,31000502L),duplicate.candidateIds());
            } finally { c.rollback(); }
        }
    }
    @Test void contradictoryRoleLabelsCannotHideBehindAnOtherwiseSafeRealContract() throws Exception {
        try(var c=ExperimentDatabase.connect()) {
            try {
                try(var update=c.createStatement()) { update.executeUpdate("UPDATE operation SET payment_purpose='Договор D-001, получатель ИНН плательщика 9900000017' WHERE id=1"); }
                var result=engine.match(c,1);
                assertEquals(MatchingModel.Status.REVIEW,result.status());
                assertNull(result.proposedContractId());
                assertEquals(2,result.features().roleClaims().size());
                assertTrue(result.features().issues().stream().anyMatch(issue->issue.contains("CONFLICTING_ROLE_CLAIMS")));
            } finally { c.rollback(); }
        }
    }
}
