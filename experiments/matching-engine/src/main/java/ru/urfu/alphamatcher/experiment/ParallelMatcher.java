package ru.urfu.alphamatcher.experiment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Bounded ordered streaming, one exclusively owned JDBC session per worker; no connection pool. */
public final class ParallelMatcher {
    public enum State { SUCCESS, ERROR, CANCELLED, TIMED_OUT }
    public record Config(int workers,int chunkSize,Duration timeout,Duration shutdownGrace,boolean sqlMetrics) {
        public Config {
            if(!List.of(1,2,4,8,16).contains(workers)||chunkSize<1||chunkSize>256)
                throw new IllegalArgumentException("workers=1/2/4/8/16; chunkSize=1..256");
            if(timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofHours(24))>0
                    ||shutdownGrace==null||shutdownGrace.isNegative()||shutdownGrace.isZero()||shutdownGrace.compareTo(Duration.ofMinutes(1))>0)
                throw new IllegalArgumentException("Positive bounded timeout and shutdown grace required");
        }
        public static Config standard(int workers) { return new Config(workers,16,Duration.ofMinutes(10),Duration.ofSeconds(20),false); }
    }
    public static final class Cancellation {
        private final AtomicBoolean cancelled=new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }
    public record Outcome(long id,State state,MatchingEngine.TimedDecision result,boolean attempted,long elapsedNanos,String diagnostic) {
        public Outcome {
            if((state==State.SUCCESS)!=(result!=null)) throw new IllegalArgumentException("Only success has a business decision");
            if(result!=null && result.decision().operationId()!=id) throw new IllegalArgumentException("Wrong operation ID");
        }
    }
    public record Summary(int input,int delivered,int succeeded,int errors,int cancelled,int timedOut,int attempted,
                          int sessionsOpened,int peakSessions,int workers,int maxInFlight,long setupNanos,
                          long processingNanos,long totalNanos,long selects,boolean sqlCountsComplete) { }
    public static final class BatchException extends RuntimeException {
        private final int delivered;
        BatchException(String message,int delivered,Throwable cause) { super(message,cause);this.delivered=delivered; }
        public int delivered() { return delivered; }
    }
    // Internal fault seam accepts a capability, never a shared JDBC connection. Production uses only OwnedSession.
    interface Session extends AutoCloseable {
        MatchingEngine.TimedDecision measure(long id) throws Exception;
        default long selects() throws Exception { return 0; }
        @Override void close() throws Exception;
    }
    @FunctionalInterface interface Factory { Session open(int worker) throws Exception; }
    private static final AtomicLong BATCHES=new AtomicLong();
    private final Factory factory;
    private final AtomicBoolean running=new AtomicBoolean();
    public ParallelMatcher() { this(worker->new OwnedSession()); }
    ParallelMatcher(Factory factory) { this.factory=Objects.requireNonNull(factory); }
    private static final class OwnedSession implements Session {
        final MatchingEngine.ReadOnlySession session=MatchingEngine.openReadOnlySession();
        OwnedSession() throws Exception { }
        public MatchingEngine.TimedDecision measure(long id) throws Exception { return session.measure(id); }
        public long selects() throws Exception { return session.completedSelects(); }
        public void close() throws Exception { session.close(); }
    }
    private static final class Job {
        final List<Long> ids;
        final Outcome[] outcomes;
        final CompletableFuture<Void> done=new CompletableFuture<>();
        Job(List<Long> ids) { this.ids=ids;outcomes=new Outcome[ids.size()]; }
    }
    private static String diagnostic(Throwable error) {
        String message=error.getClass().getSimpleName()+": "+error.getMessage();
        return message.substring(0,Math.min(message.length(),1024));
    }

    public Summary run(List<Long> input,Config config,Cancellation cancellation,Consumer<Outcome> sink) {
        long fullStart=System.nanoTime();
        Objects.requireNonNull(config);Objects.requireNonNull(cancellation);Objects.requireNonNull(sink);Objects.requireNonNull(input);
        if(input.size()>2_000_000) throw new IllegalArgumentException("Batch exceeds 2M IDs");
        List<Long> ids=List.copyOf(input);
        var seen=new HashSet<Long>();
        for(Long id:ids) if(id<=0||!seen.add(id)) throw new IllegalArgumentException("Nonpositive or duplicate operation ID: "+id);
        if(!running.compareAndSet(false,true)) throw new IllegalStateException("This matcher already has an active batch");
        Control control=new Control(config,cancellation,fullStart);
        var workers=new ArrayList<Worker>();
        int delivered=0,success=0,errors=0,cancelled=0,timed=0,attempted=0,maxInFlight=0;
        long processStart=0,processStop=0;
        RuntimeException failure=null;
        try {
            if(!ids.isEmpty() && control.reason()==null) {
                long batch=BATCHES.incrementAndGet();
                for(int i=0;i<config.workers();i++) workers.add(new Worker(i,control,"matching-batch-"+batch+"-"+i));
                workers.forEach(worker->worker.thread.start());
                while(control.ready.getCount()>0 && control.reason()==null) {
                    try { control.ready.await(10,TimeUnit.MILLISECONDS); }
                    catch(InterruptedException e) { control.interrupted=true;control.abort.set(true); }
                }
            }
            processStart=System.nanoTime();control.start.countDown();
            int window=config.workers()*config.chunkSize();
            for(int offset=0;offset<ids.size();offset+=window) {
                int end=Math.min(ids.size(),offset+window);
                var jobs=new ArrayList<Job>();
                for(int index=offset,w=0;index<end;index+=config.chunkSize(),w++) {
                    Job job=new Job(ids.subList(index,Math.min(end,index+config.chunkSize())));jobs.add(job);
                    State reason=control.reason();
                    if(reason!=null) completeUnstarted(job,reason,"Batch stopped before dispatch");
                    else workers.get(w).submit(job);
                }
                maxInFlight=Math.max(maxInFlight,end-offset);
                for(Job job:jobs) {
                    while(!job.done.isDone()) {
                        try { job.done.get(10,TimeUnit.MILLISECONDS); }
                        catch(TimeoutException e) { /* bounded polling; JDBC cancellation stays with its owner */ }
                        catch(InterruptedException e) { control.interrupted=true;control.abort.set(true); }
                        catch(java.util.concurrent.ExecutionException e) { throw new IllegalStateException("Worker protocol failure",e.getCause()); }
                        if(control.reason()!=null) {
                            if(System.nanoTime()-control.stoppedAt.get()>config.shutdownGrace().toNanos()) throw new BatchException("Worker exceeded cooperative cancellation grace",delivered,null);
                        }
                    }
                    job.done.join();
                    for(Outcome outcome:job.outcomes) {
                        if(outcome==null) throw new IllegalStateException("Worker omitted an operation");
                        sink.accept(outcome);delivered++;
                        if(outcome.attempted()) attempted++;
                        switch(outcome.state()) { case SUCCESS->success++;case ERROR->errors++;case CANCELLED->cancelled++;case TIMED_OUT->timed++; }
                    }
                }
            }
            processStop=System.nanoTime();
        } catch(RuntimeException e) { control.abort.set(true);failure=e; }
        finally {
            control.start.countDown();
            workers.forEach(Worker::stop);
            long stopped=control.stoppedAt.get();
            long joinDeadline=(stopped==0?System.nanoTime():stopped)+config.shutdownGrace().toNanos();
            boolean safeToReuse=true;
            for(Worker worker:workers) {
                while(worker.thread.isAlive() && System.nanoTime()<joinDeadline) {
                    try { worker.thread.join(10); } catch(InterruptedException e) { control.interrupted=true;control.abort.set(true); }
                }
                if(worker.thread.isAlive()) {
                    worker.thread.interrupt();
                    safeToReuse=false;
                    failure=new BatchException("Worker did not terminate: "+worker.thread.getName(),delivered,failure);
                } else if(worker.closeFailure!=null) {
                    safeToReuse=false;
                    failure=new BatchException("Worker session cleanup failed: "+worker.closeFailure,delivered,failure);
                }
            }
            // Fail-stop: a timed-out driver must never overlap a new batch on this instance.
            if(safeToReuse) running.set(false);
            if(control.interrupted) Thread.currentThread().interrupt();
        }
        if(failure!=null) throw new BatchException("Batch aborted after "+delivered+" deliveries",delivered,failure);
        if(delivered!=ids.size()||control.active.get()!=0) throw new BatchException("Incomplete batch or leaked session",delivered,null);
        long setup=workers.stream().mapToLong(w->w.setupNanos).sum();
        long selects=workers.stream().mapToLong(w->w.sqlSelects).sum();
        boolean complete=config.sqlMetrics()&&workers.stream().noneMatch(w->w.sqlIncomplete);
        return new Summary(ids.size(),delivered,success,errors,cancelled,timed,attempted,control.opened.get(),control.peak.get(),workers.size(),maxInFlight,
                setup,processStop-processStart,System.nanoTime()-fullStart,complete?selects:-1,complete);
    }
    private static void completeUnstarted(Job job,State state,String reason) {
        for(int i=0;i<job.ids.size();i++) job.outcomes[i]=new Outcome(job.ids.get(i),state,null,false,0,reason);
        job.done.complete(null);
    }
    private static final class Control {
        final Config config;final Cancellation cancellation;final long deadline;
        final AtomicBoolean abort=new AtomicBoolean();final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),opened=new AtomicInteger();
        final AtomicLong stoppedAt=new AtomicLong();
        final CountDownLatch ready,start=new CountDownLatch(1);
        boolean interrupted;
        Control(Config config,Cancellation cancellation,long started) { this.config=config;this.cancellation=cancellation;deadline=started+config.timeout().toNanos();ready=new CountDownLatch(config.workers()); }
        State reason() {
            State reason=cancellation.isCancelled()||abort.get()?State.CANCELLED:System.nanoTime()>=deadline?State.TIMED_OUT:null;
            if(reason!=null) stoppedAt.compareAndSet(0,System.nanoTime());
            return reason;
        }
    }
    private final class Worker {
        final int index;final Control control;final Thread thread;
        final ArrayBlockingQueue<Job> queue=new ArrayBlockingQueue<>(1);
        boolean stopped;volatile boolean closing;
        String unavailable,closeFailure;
        Session session;long setupNanos,sqlStart,sqlSelects;boolean sqlIncomplete;
        Worker(int index,Control control,String name) { this.index=index;this.control=control;thread=new Thread(this::loop,name);thread.setDaemon(true); }
        synchronized void submit(Job job) {
            if(stopped) completeUnstarted(job,State.ERROR,unavailable==null?"Worker unavailable":unavailable);
            else if(!queue.offer(job)) throw new IllegalStateException("Bounded worker queue invariant violated");
        }
        void stop() { closing=true;queue.offer(new Job(List.of())); }
        void open() throws Exception {
            long start=System.nanoTime();
            try {
                session=Objects.requireNonNull(factory.open(index));
                int active=control.active.incrementAndGet();control.peak.accumulateAndGet(active,Math::max);control.opened.incrementAndGet();
                if(control.config.sqlMetrics()) sqlStart=session.selects();
            } finally { setupNanos+=System.nanoTime()-start; }
        }
        void closeSession() {
            if(session==null) return;
            try {
                if(control.config.sqlMetrics()) {
                    try { sqlSelects+=Math.max(0,session.selects()-sqlStart-1); }
                    catch(Exception e) { sqlIncomplete=true; }
                }
                session.close();
            } catch(Throwable e) { closeFailure=diagnostic(e); }
            finally { session=null;control.active.decrementAndGet(); }
        }
        void loop() {
            Job current=null;
            int attemptedIndex=-1;long attemptStart=0;
            try {
                try { if(control.reason()==null) open(); }
                catch(Exception e) { unavailable=diagnostic(e); }
                finally { control.ready.countDown(); }
                control.start.await();
                while(true) {
                    if(closing&&queue.isEmpty()) break;
                    current=queue.take();if(current.ids.isEmpty()) break;
                    for(int i=0;i<current.ids.size();i++) {
                        long id=current.ids.get(i);State stop=control.reason();
                        if(stop!=null) { current.outcomes[i]=new Outcome(id,stop,null,false,0,"Stopped before operation");continue; }
                        if(unavailable!=null) { current.outcomes[i]=new Outcome(id,State.ERROR,null,false,0,unavailable);continue; }
                        long started=System.nanoTime();boolean attempted=false;
                        try {
                            if(session==null) open();
                            attempted=true;attemptedIndex=i;attemptStart=started;
                            var result=session.measure(id);
                            stop=control.reason();
                            current.outcomes[i]=stop==null?new Outcome(id,State.SUCCESS,result,true,System.nanoTime()-started,"")
                                    :new Outcome(id,stop,null,true,System.nanoTime()-started,"Completed after cancellation/deadline; decision withheld");
                        } catch(Exception e) {
                            stop=control.reason();
                            current.outcomes[i]=new Outcome(id,stop==null?State.ERROR:stop,null,attempted,System.nanoTime()-started,diagnostic(e));
                            closeSession();
                        }
                        attemptedIndex=-1;
                    }
                    current.done.complete(null);current=null;
                }
            } catch(Throwable failure) {
                unavailable=diagnostic(failure);
                if(current!=null) {
                    for(int i=0;i<current.outcomes.length;i++) if(current.outcomes[i]==null)
                        current.outcomes[i]=new Outcome(current.ids.get(i),State.ERROR,null,i==attemptedIndex,
                                i==attemptedIndex?System.nanoTime()-attemptStart:0,"Worker failure: "+unavailable);
                    current.done.complete(null);
                }
            } finally {
                closeSession();
                synchronized(this) {
                    stopped=true;Job pending;
                    while((pending=queue.poll())!=null) completeUnstarted(pending,State.ERROR,"Worker stopped: "+unavailable);
                }
            }
        }
    }
}
