package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;

class ParallelMatcherMySqlIT {
    private static final List<Long> FIXTURE=java.util.stream.LongStream.rangeClosed(1,20).boxed().toList();
    private static List<MatchingModel.Decision> reference(List<Long> ids) throws Exception {
        var result=new ArrayList<MatchingModel.Decision>();
        try(var session=MatchingEngine.openReadOnlySession()) { for(long id:ids) result.add(session.match(id)); }
        return result;
    }
    private static void noWorkersRemain() {
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().startsWith("matching-batch-")));
    }
    @ParameterizedTest @ValueSource(ints={1,2,4,8,16})
    void repeatedFixtureBatchesHaveExactDecisionsOrderAndBoundedConnections(int workers) throws Exception {
        var expected=reference(FIXTURE);var batch=new ParallelMatcher();
        for(int repeat=0;repeat<3;repeat++) {
            var ids=repeat==1?FIXTURE.reversed():FIXTURE;
            var orderedExpected=repeat==1?expected.reversed():expected;
            var rows=new ArrayList<ParallelMatcher.Outcome>();
            var summary=batch.run(ids,ParallelMatcher.Config.standard(workers),new ParallelMatcher.Cancellation(),rows::add);
            assertEquals(orderedExpected,rows.stream().map(r->r.result().decision()).toList());
            assertEquals(ids,rows.stream().map(ParallelMatcher.Outcome::id).toList());
            assertEquals(20,summary.succeeded());assertEquals(20,summary.attempted());assertEquals(0,summary.errors());
            assertEquals(workers,summary.sessionsOpened());assertTrue(summary.peakSessions()<=workers);
            assertTrue(summary.maxInFlight()<=workers*16);assertTrue(summary.totalNanos()>=summary.processingNanos());
            noWorkersRemain();
        }
    }
    @Test void largeCandidateAndLongPurposeGeneratedDataRemainExactlyEquivalent() throws Exception {
        var config=Config.standard(20261029,120,240,1000,Profile.STRESS);
        var data=new SyntheticGenerator().generate(config);
        try(var writer=ExperimentDatabase.connect()) {
            new SyntheticLoader().replace(writer,data);writer.commit();
            try {
                var ids=data.truth().stream().map(Truth::id).toList();var expected=reference(ids);
                for(int workers:List.of(1,2,4,8,16)) {
                    var rows=new ArrayList<MatchingModel.Decision>();
                    var result=new ParallelMatcher().run(ids,ParallelMatcher.Config.standard(workers),new ParallelMatcher.Cancellation(),o->{assertEquals(ParallelMatcher.State.SUCCESS,o.state());rows.add(o.result().decision());});
                    assertEquals(expected,rows);assertEquals(1000,result.succeeded());noWorkersRemain();
                }
            } finally { new SyntheticLoader().replace(writer,new Dataset(config,List.of(),List.of(),List.of(),List.of()));writer.commit(); }
        }
    }
    @Test void serverSelectCounterExcludesItsOwnProfilingQueries() {
        var config=new ParallelMatcher.Config(1,1,Duration.ofMinutes(1),Duration.ofSeconds(20),true);
        var summary=new ParallelMatcher().run(List.of(1L),config,new ParallelMatcher.Cancellation(),o->assertEquals(ParallelMatcher.State.SUCCESS,o.state()));
        assertTrue(summary.sqlCountsComplete());assertEquals(2,summary.selects());noWorkersRemain();
    }
    @Test void jdbcFailureClosesSessionAndLaterIdsRecoverWithoutRetryingTheFailedId() throws Exception {
        var expected=reference(FIXTURE);
        var calls=new AtomicInteger();
        var batch=new ParallelMatcher(w->{
            var actual=MatchingEngine.openReadOnlySession();long connectionId=actual.connectionId();
            return new ParallelMatcher.Session() {
                public MatchingEngine.TimedDecision measure(long id) throws Exception {
                    calls.incrementAndGet();
                    if(id==3) try(var killer=ExperimentDatabase.connect();var statement=killer.createStatement()) { statement.execute("KILL CONNECTION "+connectionId); }
                    return actual.measure(id);
                }
                public void close() throws Exception { actual.close(); }
            };
        });
        var rows=new ArrayList<ParallelMatcher.Outcome>();
        var summary=batch.run(FIXTURE,ParallelMatcher.Config.standard(1),new ParallelMatcher.Cancellation(),rows::add);
        assertEquals(20,calls.get());assertEquals(20,rows.size());assertEquals(1,summary.errors());assertEquals(19,summary.succeeded());
        assertEquals(3,rows.get(2).id());assertEquals(ParallelMatcher.State.ERROR,rows.get(2).state());
        assertEquals(2,summary.sessionsOpened());assertEquals(1,summary.peakSessions());noWorkersRemain();
        for(int i=0;i<rows.size();i++) if(rows.get(i).state()==ParallelMatcher.State.SUCCESS) assertEquals(expected.get(i),rows.get(i).result().decision());
    }
    @Test void cancellationDuringStreamingPreservesCompletedOutcomesAndAccountsForEveryId() throws Exception {
        var expected=reference(FIXTURE);
        var token=new ParallelMatcher.Cancellation();var rows=new ArrayList<ParallelMatcher.Outcome>();
        var config=new ParallelMatcher.Config(4,1,Duration.ofMinutes(1),Duration.ofSeconds(20),false);
        var summary=new ParallelMatcher().run(FIXTURE,config,token,o->{rows.add(o);if(rows.size()==3) token.cancel();});
        assertEquals(FIXTURE,rows.stream().map(ParallelMatcher.Outcome::id).toList());
        assertTrue(summary.succeeded()>0);assertTrue(summary.cancelled()>0);assertEquals(20,summary.succeeded()+summary.cancelled());
        assertEquals(0,summary.errors());noWorkersRemain();
        for(int i=0;i<rows.size();i++) if(rows.get(i).state()==ParallelMatcher.State.SUCCESS) assertEquals(expected.get(i),rows.get(i).result().decision());
    }
    @Test void deadlineDuringAnOperationWithholdsLateDecisionAndClosesEverything() throws Exception {
        var batch=new ParallelMatcher(w->{var actual=MatchingEngine.openReadOnlySession();return new ParallelMatcher.Session() {
            public MatchingEngine.TimedDecision measure(long id) throws Exception { Thread.sleep(1000);return actual.measure(id); }
            public void close() throws Exception { actual.close(); }
        };});
        var rows=new ArrayList<ParallelMatcher.Outcome>();
        var summary=batch.run(FIXTURE,new ParallelMatcher.Config(2,1,Duration.ofMillis(500),Duration.ofSeconds(20),false),new ParallelMatcher.Cancellation(),rows::add);
        assertEquals(20,summary.timedOut());assertEquals(0,summary.succeeded());assertEquals(20,rows.size());noWorkersRemain();
        assertTrue(summary.attempted()>0,"Must exercise an actual in-flight deadline");
    }
    @Test void fatalMidWorkerFailureRetainsCompletedSlotsAndNeverRetriesAnId() throws Exception {
        var expected=reference(FIXTURE);
        var batch=new ParallelMatcher(w->{var actual=MatchingEngine.openReadOnlySession();return new ParallelMatcher.Session() {
            public MatchingEngine.TimedDecision measure(long id) throws Exception { if(id==3) throw new AssertionError("Fatal worker");return actual.measure(id); }
            public void close() throws Exception {actual.close();}
        };});
        var rows=new ArrayList<ParallelMatcher.Outcome>();
        var summary=batch.run(FIXTURE,new ParallelMatcher.Config(1,2,Duration.ofMinutes(1),Duration.ofSeconds(20),false),new ParallelMatcher.Cancellation(),rows::add);
        assertEquals(FIXTURE,rows.stream().map(ParallelMatcher.Outcome::id).toList());
        assertEquals(2,summary.succeeded());assertEquals(18,summary.errors());assertEquals(3,summary.attempted());noWorkersRemain();
        assertEquals(expected.subList(0,2),rows.subList(0,2).stream().map(o->o.result().decision()).toList());
    }
    @Test void physicalServerConnectionsNeverExceedWorkerLimitAndDisappearAfterShutdown() throws Exception {
        int workers=16;var entered=new CountDownLatch(workers);var release=new CountDownLatch(1);
        var error=new AtomicReference<Throwable>();var summary=new AtomicReference<ParallelMatcher.Summary>();
        var batch=new ParallelMatcher(w->{var actual=MatchingEngine.openReadOnlySession();return new ParallelMatcher.Session() {
            public MatchingEngine.TimedDecision measure(long id) throws Exception {
                entered.countDown();if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier");return actual.measure(id);
            }
            public void close() throws Exception {actual.close();}
        };});
        Thread caller=Thread.ofPlatform().start(()->{try {summary.set(batch.run(FIXTURE,new ParallelMatcher.Config(workers,1,Duration.ofMinutes(1),Duration.ofSeconds(20),false),new ParallelMatcher.Cancellation(),o->{}));}catch(Throwable e){error.set(e);}});
        try(var observer=ExperimentDatabase.connect()) {
            try {
                assertTrue(entered.await(15,TimeUnit.SECONDS));
                assertEquals(workers,otherConnections(observer));
            } finally {release.countDown();caller.join(20000);}
            assertFalse(caller.isAlive());assertNull(error.get(),()->String.valueOf(error.get()));
            assertEquals(workers,summary.get().peakSessions());noWorkersRemain();
            // COM_QUIT has no ordinary OK handshake; server-side teardown can lag socket.close().
            long started=System.nanoTime(),deadline=started+Duration.ofSeconds(5).toNanos();
            int initial=otherConnections(observer),remaining=initial;
            while(remaining!=0&&System.nanoTime()<deadline){Thread.sleep(10);remaining=otherConnections(observer);}
            System.out.println("Worker connection teardown: initial="+initial+", remaining="+remaining+", waitNs="+(System.nanoTime()-started));
            assertEquals(0,remaining,()->connectionSnapshot(observer));
        }
    }
    private static int otherConnections(java.sql.Connection observer) throws Exception {
        try(var statement=observer.createStatement();var rs=statement.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER='matcher' AND DB='matching_engine_exp' AND ID<>CONNECTION_ID()")) {rs.next();return rs.getInt(1);}
    }
    private static String connectionSnapshot(java.sql.Connection observer) {
        try(var statement=observer.createStatement();var rs=statement.executeQuery("SELECT ID,COMMAND,TIME,STATE,INFO FROM information_schema.PROCESSLIST WHERE USER='matcher' AND DB='matching_engine_exp' AND ID<>CONNECTION_ID()")) {
            var text=new StringBuilder("Server connections still present: ");
            while(rs.next())text.append(rs.getLong(1)).append('/').append(rs.getString(2)).append('/').append(rs.getLong(3)).append('/').append(rs.getString(4)).append('/').append(rs.getString(5)).append(';');
            return text.toString();
        }catch(Exception e){return e.toString();}
    }
    @Test void cancellationWhileARealDecisionIsInFlightWithholdsItAndClosesSessions() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var token=new ParallelMatcher.Cancellation();
        var rows=new ArrayList<ParallelMatcher.Outcome>();var result=new AtomicReference<ParallelMatcher.Summary>();var error=new AtomicReference<Throwable>();
        var batch=new ParallelMatcher(w->{var actual=MatchingEngine.openReadOnlySession();return new ParallelMatcher.Session() {
            public MatchingEngine.TimedDecision measure(long id) throws Exception {var decision=actual.measure(id);entered.countDown();if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Barrier");return decision;}
            public void close() throws Exception {actual.close();}
        };});
        Thread caller=Thread.ofPlatform().start(()->{try{result.set(batch.run(FIXTURE,ParallelMatcher.Config.standard(2),token,rows::add));}catch(Throwable e){error.set(e);}});
        try {assertTrue(entered.await(15,TimeUnit.SECONDS));token.cancel();}finally{release.countDown();caller.join(20000);}
        assertFalse(caller.isAlive());assertNull(error.get(),()->String.valueOf(error.get()));
        assertEquals(20,result.get().cancelled());assertEquals(0,result.get().succeeded());assertEquals(20,rows.size());noWorkersRemain();
    }
    @Test void consumerFailureStillClosesAllOwnedSessions() throws Exception {
        var batch=new ParallelMatcher();
        var error=assertThrows(ParallelMatcher.BatchException.class,()->batch.run(FIXTURE,ParallelMatcher.Config.standard(4),new ParallelMatcher.Cancellation(),o->{throw new IllegalArgumentException("Sink failure");}));
        assertEquals(0,error.delivered());noWorkersRemain();
        assertEquals(20,batch.run(FIXTURE,ParallelMatcher.Config.standard(4),new ParallelMatcher.Cancellation(),o->{}).succeeded());
    }
    @Test void independentWorkerSnapshotsCanObserveDifferentCommittedVersions() throws Exception {
        var firstSnapshot=new CountDownLatch(1);var changed=new CountDownLatch(1);
        var old=new AtomicReference<MatchingModel.Decision>();var fresh=new AtomicReference<MatchingModel.Decision>();var error=new AtomicReference<Throwable>();
        Thread first=Thread.ofPlatform().start(()->{
            try(var session=MatchingEngine.openReadOnlySession()) {
                var before=session.match(1);firstSnapshot.countDown();
                if(!changed.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Writer did not finish");
                assertEquals(before,session.match(1));old.set(before);
            } catch(Throwable e) {error.set(e);firstSnapshot.countDown();}
        });
        try(var writer=ExperimentDatabase.connect()) {
            String previous;
            try(var query=writer.createStatement();var rs=query.executeQuery("SELECT inn FROM contract_party WHERE contract_id=101 AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER')")) {rs.next();previous=rs.getString(1);}
            writer.rollback();assertTrue(firstSnapshot.await(15,TimeUnit.SECONDS));
            try {
                try(var query=writer.createStatement()) {query.executeUpdate("UPDATE contract_party SET inn='9900000031' WHERE contract_id=101 AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER')");}writer.commit();
                Thread second=Thread.ofPlatform().start(()->{try(var session=MatchingEngine.openReadOnlySession()){fresh.set(session.match(1));}catch(Throwable e){error.set(e);}});
                second.join(15000);assertFalse(second.isAlive());changed.countDown();first.join(15000);assertFalse(first.isAlive());
                assertNull(error.get(),()->String.valueOf(error.get()));assertEquals(MatchingModel.Status.SAFE,old.get().status());assertEquals(MatchingModel.Status.REVIEW,fresh.get().status());
            } finally {
                changed.countDown();
                try(var restore=writer.prepareStatement("UPDATE contract_party SET inn=? WHERE contract_id=101 AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER')")){restore.setString(1,previous);restore.executeUpdate();}writer.commit();first.join(15000);
            }
        }
    }
}
