package ru.urfu.alphamatcher.stage7;
import java.util.*;
import java.util.concurrent.*;
import java.sql.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import ru.urfu.alphamatcher.experiment.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;
@Service
public final class DemoRuns {
    public enum State { EMPTY, PREPARING, READY, RUNNING, COMPLETE, ERROR }
    public record Result(long id,String purpose,Operation operation,Decision decision,List<Candidate> candidates,String error,boolean injectedFault,long elapsedNanos){
        public String status(){return decision==null?"TECHNICAL_ERROR":decision.status().name();}
        public String reason(){
            if(decision==null)return error;
            return decision.checks().stream().filter(c->!c.passed()).map(Check::explanation).findFirst()
                    .orElse(decision.status()==Status.SAFE?"Все выполненные SAFE-проверки пройдены.":"Движок завершил проверку.");
        }
    }
    public record Metrics(int total,int safe,int review,int noContract,int errors,int skipped,double safePercent){
        public static Metrics of(List<Result> rows){
            int safe=0,review=0,no=0,errors=0,skip=0;
            for(var r:rows)switch(r.status()){case "SAFE"->safe++;case "REVIEW","ALREADY_MANUAL"->review++;case "NO_CONTRACT"->no++;case "TECHNICAL_ERROR"->errors++;default->skip++;}
            return new Metrics(rows.size(),safe,review,no,errors,skip,rows.isEmpty()?0:100.0*safe/rows.size());
        }
    }
    public record Row(String runId,long id,String purpose,String status,Long proposedId,String contract,int candidateCount,String reason){}
    public record Snapshot(long revision,State state,String message,DataImport.Dataset data,List<Result> results,long elapsedNanos,
                           boolean databaseUnchanged,String runId,int processed,int total,Metrics metrics,long startedNanos,long sampledHeapPeak,List<Row> rows){
        public boolean busy(){return state==State.PREPARING||state==State.RUNNING;}
        public long currentElapsedNanos(){return state==State.RUNNING?System.nanoTime()-startedNanos:elapsedNanos;}
    }
    private final DemoStore store;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("stage7-demo-worker").factory());
    private volatile Snapshot snapshot=empty(0,"Подготовьте набор данных.");
    private volatile ResultStore resultStore;
    private String preset="CUSTOM";
    public DemoRuns(DemoStore store,EngineIdentity identity){this.store=store;}
    private static Snapshot empty(long revision,String text){return new Snapshot(revision,State.EMPTY,text,null,List.of(),0,false,"",0,0,new Metrics(0,0,0,0,0,0,0),0,0,List.of());}
    public Snapshot snapshot(){return snapshot;}
    private void available(){if(snapshot.busy())throw new IllegalStateException("Дождитесь завершения текущей операции.");}
    public synchronized CompletableFuture<Void> prepare(DataImport.Dataset data){DataImport.validate(data);return prepareTask(()->{store.replace(data);return data;},"CUSTOM");}
    public synchronized CompletableFuture<Void> prepareImported(DataImport.Dataset data){
        available();
        DataImport.validate(data);
        try{Files.writeString(Path.of(System.getenv("STAGE7_RUN_DIR")).resolve("USER_IMPORT_PRESENT"),"Preserve user input database; no automatic deletion.\n");}catch(Exception e){throw new IllegalStateException("Не удалось защитить пользовательский набор.",e);}
        return prepare(data);
    }
    public synchronized CompletableFuture<Void> preparePreset(String key){
        if(key.equals("BASE")||key.equals("BASE6")){
            try(var in=getClass().getResourceAsStream("/META-INF/resources/example.json")){
                var d=DataImport.parse(in.readAllBytes());if(key.equals("BASE6"))d=new DataImport.Dataset(d.name(),d.clients(),d.contracts(),d.payments().stream().filter(x->x.fault()!=DataImport.Fault.JDBC_ERROR).toList());
                var data=d;return prepareTask(()->{store.replace(data);return data;},key);
            }catch(Exception e){throw new IllegalStateException("Набор недоступен.",e);}
        }
        return prepareTask(()->{var input=PresetCatalog.generate(key);store.replacePreset(input.synthetic());return input.display();},key);
    }
    private CompletableFuture<Void> prepareTask(Callable<DataImport.Dataset> load,String key){
        available();snapshot=new Snapshot(snapshot.revision()+1,State.PREPARING,"Подготовка данных...",null,List.of(),0,false,"",0,0,Metrics.of(List.of()),0,0,List.of());
        return CompletableFuture.runAsync(()->{
            try{var data=load.call();preset=key;snapshot=new Snapshot(snapshot.revision()+1,State.READY,"Данные подготовлены.",data,List.of(),0,false,"",0,data.payments().size(),Metrics.of(List.of()),0,0,List.of());}
            catch(Exception e){snapshot=new Snapshot(snapshot.revision()+1,State.ERROR,"Не удалось подготовить данные: "+e.getClass().getSimpleName(),null,List.of(),0,false,"",0,0,Metrics.of(List.of()),0,0,List.of());}
        },worker);
    }
    public synchronized CompletableFuture<Void> run(){
        available();if(snapshot.data()==null)throw new IllegalStateException("Сначала подготовьте данные.");
        var data=snapshot.data();String runId=UUID.randomUUID().toString();long start=System.nanoTime();
        snapshot=new Snapshot(snapshot.revision()+1,State.RUNNING,"Сопоставление выполняется...",data,List.of(),0,false,runId,0,data.payments().size(),Metrics.of(List.of()),start,0,List.of());
        return CompletableFuture.runAsync(()->execute(data,runId,start),worker);
    }
    private void execute(DataImport.Dataset data,String runId,long start){
        Path folder=Path.of(System.getenv("STAGE7_RUN_DIR")).resolve("results").resolve(runId);
        var summaries=new ArrayList<Row>();int safe=0,review=0,no=0,errors=0,skip=0;long peak=0;
        try(var sink=new ResultStore(folder.resolve("outcomes.bin"))){
            resultStore=sink;var before=store.snapshot();Connection reader=null;
            try{
                for(var payment:data.payments()){
                    if(reader==null||reader.isClosed()){reader=ExperimentDatabase.connect();reader.setReadOnly(true);DemoStore.assertOwner(reader);}
                    Result result=evaluate(reader,payment);sink.append(result);
                    if(result.decision()==null){reader.close();reader=null;}
                    String number=result.decision()==null||result.decision().proposedContractId()==null?"Не предложен":result.candidates().stream().filter(c->c.id()==result.decision().proposedContractId()).map(c->c.number()+" · "+c.id()).findFirst().orElse("Не предложен");
                    String reason=result.reason();if(reason.length()>240)reason=reason.substring(0,240)+"…";
                    summaries.add(new Row(runId,result.id(),payment.purpose(),result.status(),result.decision()==null?null:result.decision().proposedContractId(),number,result.decision()==null?-1:result.candidates().size(),reason));
                    switch(result.status()){case "SAFE"->safe++;case "REVIEW"->review++;case "NO_CONTRACT"->no++;case "TECHNICAL_ERROR"->errors++;default->skip++;}
                    int done=summaries.size();
                    if(done%100==0||done==data.payments().size()){
                        peak=Math.max(peak,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                        var metrics=new Metrics(done,safe,review,no,errors,skip,100.0*safe/done);
                        snapshot=new Snapshot(snapshot.revision()+1,State.RUNNING,"Обработано "+done+" из "+data.payments().size(),data,List.of(),System.nanoTime()-start,false,runId,done,data.payments().size(),metrics,start,peak,List.of());
                        if(sink.bytes()>2L*1024*1024*1024)throw new java.io.IOException("Полная диагностика превысила 2 GiB; запуск не принят.");
                    }
                }
            }finally{if(reader!=null){reader.rollback();reader.close();}}
            boolean unchanged=before.equals(store.snapshot());if(!unchanged)throw new IllegalStateException("База изменилась во время сопоставления.");
            sink.close();long elapsed=System.nanoTime()-start;var metrics=new Metrics(summaries.size(),safe,review,no,errors,skip,100.0*safe/summaries.size());
            var json=new tools.jackson.databind.json.JsonMapper();
            Files.write(folder.resolve("summary.json"),json.writeValueAsBytes(Map.of("preset",preset,"runId",runId,"metrics",metrics,"elapsedNanos",elapsed,"sampledHeapPeakBytes",peak,"databaseUnchanged",true,"status","COMPLETE")));
            snapshot=new Snapshot(snapshot.revision()+1,State.COMPLETE,errors==0?"Обработка завершена.":"Обработка завершена с техническими ошибками: "+errors+".",data,sink.lazyList(),elapsed,true,runId,summaries.size(),data.payments().size(),metrics,start,peak,List.copyOf(summaries));
            retireFiles(folder);
        }catch(Exception e){snapshot=new Snapshot(snapshot.revision()+1,State.ERROR,"Обработка прервана: "+e.getClass().getSimpleName()+". Неполные результаты не приняты.",data,List.of(),System.nanoTime()-start,false,runId,summaries.size(),data.payments().size(),snapshot.metrics(),start,peak,List.of());}
    }
    // Keep only two complete unpinned result files per owned session. No historical research path is touched.
    private static void retireFiles(Path current)throws Exception{
        try(var dirs=Files.list(current.getParent())){
            var old=dirs.filter(Files::isDirectory).filter(p->!p.equals(current)&&!Files.exists(p.resolve("PINNED"))).sorted(Comparator.comparing(p->p.toFile().lastModified(),Comparator.reverseOrder())).toList();
            for(var p:old.stream().skip(1).toList()){
                if(!Files.exists(p.resolve("summary.json")))continue;
                Files.deleteIfExists(p.resolve("outcomes.bin")); // summary is retained, not the heavy temporary payload
            }
        }
    }
    public List<Row> query(String text,String status,String sort){
        String q=Objects.toString(text,"").toLowerCase(Locale.ROOT);
        Comparator<Row> order=switch(Objects.toString(sort,"ID ↑")){case "ID ↓"->Comparator.comparingLong(Row::id).reversed();case "Статус"->Comparator.comparing(Row::status).thenComparingLong(Row::id);case "Кандидаты ↓"->Comparator.comparingInt(Row::candidateCount).reversed().thenComparingLong(Row::id);default->Comparator.comparingLong(Row::id);};
        return snapshot.rows().stream().filter(x->status==null||status.equals("Все")||x.status().equals(status)).filter(x->q.isBlank()||(x.id()+" "+x.purpose()+" "+x.reason()+" "+x.contract()).toLowerCase(Locale.ROOT).contains(q)).sorted(order).toList();
    }
    public Result detail(Row row){var s=snapshot;if(s.state()!=State.COMPLETE||!s.runId().equals(row.runId()))throw new IllegalStateException("Результаты относятся к другому запуску.");return resultStore.read(row.id());}
    Result evaluate(DataImport.Payment p){try(var c=ExperimentDatabase.connect()){c.setReadOnly(true);DemoStore.assertOwner(c);return evaluate(c,p);}catch(Exception e){return new Result(p.id(),p.purpose(),null,null,List.of(),"Ошибка чтения: "+e.getClass().getSimpleName(),false,0);}}
    private Result evaluate(Connection c,DataImport.Payment p){
        long start=System.nanoTime();Operation[] operation={null};CandidateSearch.Result[] found={null};boolean[] injected={false};MatchingRepository jdbc=new JdbcMatchingRepository();
        MatchingRepository recording=new MatchingRepository(){
            public Optional<Operation> operation(Connection connection,long id)throws SQLException{var value=jdbc.operation(connection,id);operation[0]=value.orElse(null);return value;}
            public List<Candidate> candidates(Connection connection,PurposeParser.Features f)throws SQLException{return discover(connection,f).candidates();}
            public CandidateSearch.Result discover(Connection connection,PurposeParser.Features f)throws SQLException{
                if(p.fault()==DataImport.Fault.JDBC_ERROR){injected[0]=true;throw new SQLTransientException("Explicit Stage7 injected JDBC failure");}
                return found[0]=jdbc.discover(connection,f);
            }
        };
        try{var d=new MatchingEngine(recording,new PurposeParser()).match(c,p.id());return new Result(p.id(),p.purpose(),operation[0],d,found[0]==null?List.of():found[0].candidates(),null,false,System.nanoTime()-start);}
        catch(Exception e){return new Result(p.id(),p.purpose(),operation[0],null,List.of(),injected[0]?"Смоделированный отказ JDBC: движок не вернул Decision.":"Ошибка обработки: "+e.getClass().getSimpleName(),injected[0],System.nanoTime()-start);}
    }
    public synchronized void reset(){available();snapshot=empty(snapshot.revision()+1,"Подготовьте набор данных.");}
    @PreDestroy void close(){worker.shutdown();try{if(!worker.awaitTermination(30,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();worker.shutdownNow();}}
}
