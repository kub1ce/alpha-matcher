package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage3DevelopmentMySqlIT {
    @Test
    void unknownNameOnlySqlSearchIsNotAProofThatNoContractExists() throws Exception {
        try(var connection=ExperimentDatabase.connect()) {
            try(var statement=connection.createStatement()) { statement.executeUpdate("UPDATE operation SET payment_purpose='ФИО Неизвестнов Август Августович' WHERE id=1"); }
            try {
                var result=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser()).match(connection,1);
                assertEquals(MatchingModel.Status.REVIEW,result.status()); assertTrue(result.candidateIds().isEmpty());
            } finally { connection.rollback(); }
        }
    }

    @Test
    void fullDevelopmentWorldIncludingFormerFalseSafesIsReadOnlyAndCorrect() throws Exception {
        Path root=Path.of(System.getProperty("basedir","."));
        var cases=Stage3Corpus.read(root.resolve("stage3/corpora/development-v1.tsv"));
        try(var connection=ExperimentDatabase.connect()) {
            try {
                Stage3Corpus.load(connection,root.resolve("stage3/corpora/world-v1.sql"),cases);
                var engine=new MatchingEngine(new JdbcMatchingRepository(),new PurposeParser());
                var assertions=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
                for(var example:cases) {
                    var decision=engine.match(connection,example.id());
                    var again=engine.match(connection,example.id());
                    assertions.add(()->{
                        assertEquals(example.truth().expectedStatus(),decision.status().name(),()->example.label()+": "+decision);
                        assertEquals(example.truth().safeContract(),decision.proposedContractId(),example.label());
                        assertEquals(decision,again);
                    });
                }
                assertAll(assertions);
                try(var statement=connection.createStatement();var rs=statement.executeQuery("SELECT COUNT(*) FROM operation WHERE id BETWEEN 32000000 AND 32999999 AND contract_id IS NOT NULL")) {
                    rs.next(); assertEquals(1,rs.getInt(1));
                }
            } finally { connection.rollback(); }
        }
    }
}
