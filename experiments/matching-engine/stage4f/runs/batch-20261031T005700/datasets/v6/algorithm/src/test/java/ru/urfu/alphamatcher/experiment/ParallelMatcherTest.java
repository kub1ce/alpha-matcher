package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParallelMatcherTest {
    @Test void emptyDuplicateAndInvalidInputNeverOpenAConnection() {
        var opens=new AtomicInteger();var batch=new ParallelMatcher(w->{opens.incrementAndGet();throw new AssertionError();});
        var c=ParallelMatcher.Config.standard(4);
        assertEquals(0,batch.run(List.of(),c,new ParallelMatcher.Cancellation(),r->fail()).sessionsOpened());
        assertThrows(IllegalArgumentException.class,()->batch.run(List.of(1L,1L),c,new ParallelMatcher.Cancellation(),r->fail()));
        assertThrows(IllegalArgumentException.class,()->batch.run(List.of(0L),c,new ParallelMatcher.Cancellation(),r->fail()));
        assertEquals(0,opens.get());
    }
    @Test void preCancelledAndExpiredBatchesHaveExactlyOneTerminalOutcomePerId() {
        var batch=new ParallelMatcher(w->{throw new AssertionError("Should not connect");});
        var token=new ParallelMatcher.Cancellation();token.cancel();
        var rows=new ArrayList<ParallelMatcher.Outcome>();
        var result=batch.run(List.of(5L,3L,9L),ParallelMatcher.Config.standard(4),token,rows::add);
        assertEquals(List.of(5L,3L,9L),rows.stream().map(ParallelMatcher.Outcome::id).toList());
        assertEquals(3,result.cancelled());assertEquals(0,result.attempted());assertEquals(0,result.sessionsOpened());
        rows.clear();
        result=batch.run(List.of(5L,3L,9L),new ParallelMatcher.Config(1,1,Duration.ofNanos(1),Duration.ofSeconds(1),false),new ParallelMatcher.Cancellation(),rows::add);
        assertEquals(3,result.timedOut());assertEquals(0,result.sessionsOpened());
    }
    @Test void workerStartupFailureIsReportedWithoutLosingIds() {
        var rows=new ArrayList<ParallelMatcher.Outcome>();
        var summary=new ParallelMatcher(w->{throw new AssertionError("Fatal worker injection");})
                .run(List.of(8L,4L,2L),ParallelMatcher.Config.standard(2),new ParallelMatcher.Cancellation(),rows::add);
        assertEquals(3,summary.errors());assertEquals(3,summary.delivered());assertEquals(0,summary.attempted());
        assertEquals(List.of(8L,4L,2L),rows.stream().map(ParallelMatcher.Outcome::id).toList());
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().startsWith("matching-batch-")));
    }
    @Test void invalidWorkerAndBufferLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new ParallelMatcher.Config(3,16,Duration.ofSeconds(1),Duration.ofSeconds(1),false));
        assertThrows(IllegalArgumentException.class,()->new ParallelMatcher.Config(16,10000,Duration.ofSeconds(1),Duration.ofSeconds(1),false));
    }
    @Test void unresponsiveWorkerPoisonsTheInstanceUntilDiscardedInsteadOfOverlappingNewConnections() throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);var closed=new java.util.concurrent.CountDownLatch(1);
        var batch=new ParallelMatcher(w->new ParallelMatcher.Session() {
            public MatchingEngine.TimedDecision measure(long id) {
                while(release.getCount()>0) { try {release.await();} catch(InterruptedException ignored) { /* simulate an unresponsive driver */ } }
                throw new IllegalStateException("Released fake driver");
            }
            public void close() {closed.countDown();}
        });
        var config=new ParallelMatcher.Config(1,1,Duration.ofMillis(100),Duration.ofMillis(100),false);
        try {
            assertThrows(ParallelMatcher.BatchException.class,()->batch.run(List.of(1L),config,new ParallelMatcher.Cancellation(),o->fail()));
            assertThrows(IllegalStateException.class,()->batch.run(List.of(),config,new ParallelMatcher.Cancellation(),o->fail()));
        } finally {release.countDown();assertTrue(closed.await(3,java.util.concurrent.TimeUnit.SECONDS));}
        long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("matching-batch-"))&&System.nanoTime()<until) Thread.sleep(5);
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().startsWith("matching-batch-")));
    }
}
