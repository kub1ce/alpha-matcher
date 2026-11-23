package ru.urfu.alphamatcher.experiment;

import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NameEvidenceMySqlIT {
    @ParameterizedTest @ValueSource(longs={32000806,32000807,32000811})
    void completeDbNamesCanConfirmSupportedFormsWithoutInnSubstitutionOrWrites(long id) throws Exception {
        Path corpora=Path.of(System.getProperty("basedir",".")).resolve("stage3/corpora");
        var cases=Stage3Corpus.read(corpora.resolve("final-v3.tsv"));
        try(var c=ExperimentDatabase.connect()) {try {
            Stage3Corpus.load(c,corpora.resolve("world-v3.sql"),cases);
            var repository=new JdbcMatchingRepository();var engine=new MatchingEngine(repository,new PurposeParser());
            var operation=repository.operation(c,id).orElseThrow();
            var result=engine.match(c,id);
            var truth=cases.stream().filter(e->e.id()==id).findFirst().orElseThrow().truth();
            assertEquals(MatchingModel.Status.SAFE,result.status());assertEquals(truth.safeContract(),result.proposedContractId());
            assertEquals(operation,repository.operation(c,id).orElseThrow(),"Preview must not modify the operation");
            assertNull(operation.linkedContractId());
            for(String conflictingName:java.util.List.of("Лебедев Савелий Артёмович","Рябцев Теодор Артёмович","Рябцев Савелий Филиппович","Smith Jon George","Smіth John George")) {
                try(var s=c.prepareStatement("UPDATE operation SET payment_purpose=? WHERE id=?")) {
                    s.setString(1,operation.purpose()+"; ФИО "+conflictingName);s.setLong(2,id);assertEquals(1,s.executeUpdate());
                }
                var refused=engine.match(c,id);assertEquals(MatchingModel.Status.REVIEW,refused.status(),conflictingName);assertNull(refused.proposedContractId());
                assertNull(repository.operation(c,id).orElseThrow().linkedContractId());
            }
        }finally{c.rollback();}}
    }
}
