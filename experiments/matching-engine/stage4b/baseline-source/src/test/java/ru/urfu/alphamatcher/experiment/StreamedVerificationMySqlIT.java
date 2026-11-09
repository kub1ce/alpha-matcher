package ru.urfu.alphamatcher.experiment;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StreamedVerificationMySqlIT {
    @TempDir Path dir;
    @Test void realJdbcErrorKeepsAllIdsAndPartialSuccessOnDisk() throws Exception {
        var ids=List.of(1L,9999999L,2L);Path file=dir.resolve("reference.bin");
        try(var writer=new StreamingReference.Writer(file,3);var session=MatchingEngine.openReadOnlySession()) {
            for(long id:ids)writer.add(StreamingReferenceTest.truth(id),id==9999999?"missing operation":session.match(id).toString());writer.publish();
        }
        try(var sink=new StreamedVerification(file,dir.resolve("out"),3,()->{})) {
            var s=new ParallelMatcher().run(ids,ParallelMatcher.Config.standard(1),new ParallelMatcher.Cancellation(),sink);
            assertEquals(3,s.delivered());assertEquals(1,s.errors());assertEquals(2,s.succeeded());assertEquals(0,sink.differences);assertFalse(sink.finish(3));
        }
        assertEquals(4,Files.readAllLines(dir.resolve("out/outcomes.tsv")).size());assertNoWorkersOrSessions();
    }
    @Test void cancellationAndDeadlinePreserveCompleteTechnicalAccounting() throws Exception {
        Path file=dir.resolve("reference.bin");var ids=List.of(1L,2L,3L);
        try(var writer=new StreamingReference.Writer(file,3);var session=MatchingEngine.openReadOnlySession()){for(long id:ids)writer.add(StreamingReferenceTest.truth(id),session.match(id).toString());writer.publish();}
        for(boolean cancel:new boolean[]{true,false}) {
            var token=new ParallelMatcher.Cancellation();if(cancel)token.cancel();
            try(var sink=new StreamedVerification(file,dir.resolve("out-"+cancel),3,()->{})) {
                var config=new ParallelMatcher.Config(4,1,cancel?Duration.ofSeconds(10):Duration.ofNanos(1),Duration.ofSeconds(20),false);
                var s=new ParallelMatcher().run(ids,config,token,sink);assertEquals(3,s.delivered());assertEquals(3,cancel?s.cancelled():s.timedOut());assertFalse(sink.finish(3));
            }
            assertNoWorkersOrSessions();
        }
    }
    private void assertNoWorkersOrSessions() throws Exception {
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("matching-batch-")));
        try(var c=ExperimentDatabase.connect()) {long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();long n;do{try(var s=c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_USER='matcher' AND PROCESSLIST_ID<>CONNECTION_ID()")){rs.next();n=rs.getLong(1);}if(n==0)break;Thread.sleep(20);}while(System.nanoTime()<end);assertEquals(0,n);}
    }
}
