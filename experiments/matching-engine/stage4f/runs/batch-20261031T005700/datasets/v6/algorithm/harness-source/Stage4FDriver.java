package ru.urfu.alphamatcher.experiment;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.MessageDigest;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Shared read-only measurement harness; reads inputs, never decision/feature oracle. */
public final class Stage4FDriver {
 static Path root,dataset;static Map<Long,Input> inputs;
 record Input(long id,String text,boolean external){}
 record Row(long id,Decision decision,String error,long totalNs,long parsingNs,long jdbcNs,long rulesNs,int cpuBefore,int cpuAfter){}
 static void sql(Connection c,Path file)throws Exception{
  StringBuilder text=new StringBuilder();for(String line:Files.readAllLines(file))if(!line.stripLeading().startsWith("--"))text.append(line).append('\n');
  for(String part:text.toString().split(";"))if(!part.isBlank())try(var s=c.createStatement()){s.execute(part);}
 }
 static void load(Path source,Path output)throws Exception{
  try(var c=DriverManager.getConnection(System.getenv("MATCHING_DB_URL"),System.getenv("MATCHING_DB_USER"),System.getenv("MATCHING_DB_PASSWORD"))){
   V4Harness.ownServer(c); // BEFORE any DDL: exact version, port, catalog and NEW owned runtime/datadir.
   try(var s=c.createStatement()){s.execute("DROP DATABASE matching_engine_exp");s.execute("CREATE DATABASE matching_engine_exp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");s.execute("USE matching_engine_exp");}
   sql(c,root.resolve("setup/001_schema.sql"));sql(c,source);
  }
  V4Harness.write(output,Map.of("status","PASS","sourceSHA256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))),"writes","OWNED disposable catalog reset only","matchingCalls",0,"snapshot",V4Harness.snapshot()));
 }
 static MatchingEngine engine(){
  var jdbc=new JdbcMatchingRepository();
  // Forward both legacy candidates() and current discover() without fabricating completeness.
  MatchingRepository adapter=(MatchingRepository)Proxy.newProxyInstance(MatchingRepository.class.getClassLoader(),new Class<?>[]{MatchingRepository.class},(proxy,method,args)->{
   try{
    if(!method.getName().equals("operation"))return method.invoke(jdbc,args);
    Input i=inputs.get((Long)args[1]);if(i==null)throw new IllegalArgumentException("Unlisted input");
    Operation op=jdbc.operation((Connection)args[0],i.id).orElseThrow();
    String expected=i.external?"[EXTERNAL_LONG_INPUT "+i.id+"]":i.text;
    if(!op.purpose().equals(expected))throw new IllegalStateException("Actual SQL input fidelity failure "+i.id);
    return Optional.of(i.external?new Operation(i.id,i.text,op.status(),op.activeStatus(),op.linkedContractId(),op.parties()):op);
   }catch(InvocationTargetException error){throw error.getCause();}
  });
  return new MatchingEngine(adapter,new PurposeParser());
 }
 static Connection reader()throws Exception{var c=V4Harness.connection();V4Harness.ownServer(c);return c;}
 static Row one(MatchingEngine e,Connection c,Input input){
  if(NativeRuntime457.mask()!=15||NativeRuntime457.threadMask()!=15)throw new IllegalStateException("Wrong shared affinity");int before=NativeRuntime457.cpu();
  try{var t=e.measure(c,input.id);return new Row(input.id,t.decision(),null,t.totalNanos(),t.parsingNanos(),t.sqlNanos(),t.rulesNanos(),before,NativeRuntime457.cpu());}
  catch(Exception failure){return new Row(input.id,null,failure.getClass().getName()+": "+failure.getMessage(),0,0,0,0,before,NativeRuntime457.cpu());}
 }
 static Row[] phase(boolean parallel)throws Exception{
  var values=new ArrayList<>(inputs.values());Row[] rows=new Row[values.size()];
  if(!parallel){try(var c=reader()){var e=engine();for(int i=0;i<rows.length;i++)rows[i]=one(e,c,values.get(i));}return rows;}
  var pool=Executors.newFixedThreadPool(4);var next=new AtomicInteger();var futures=new ArrayList<Future<?>>();
  try{for(int worker=0;worker<4;worker++)futures.add(pool.submit(()->{try(var c=reader()){var e=engine();for(int i;(i=next.getAndIncrement())<rows.length;)rows[i]=one(e,c,values.get(i));}catch(Exception failure){throw new RuntimeException(failure);}}));for(var f:futures)f.get();}
  finally{pool.shutdownNow();if(!pool.awaitTermination(30,TimeUnit.SECONDS))throw new IllegalStateException("Worker leak");}
  return rows;
 }
 static void freezeCheck()throws Exception{
  for(String line:Files.readAllLines(dataset.resolve("FREEZE_SHA256SUMS"))){String[] p=line.split("  ",2);String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dataset.resolve(p[1]))));if(!actual.equals(p[0]))throw new IllegalStateException("Frozen artifact modified "+p[1]);}
  if(java.time.Instant.now().isBefore(java.time.Instant.parse(Files.readString(dataset.resolve("protocol/FIRST_ALLOWED_UTC.txt")).strip())))throw new IllegalStateException("Blind opening too early");
 }
 static void evaluate(Path out,boolean blind)throws Exception{
  if(blind)freezeCheck();Files.createDirectories(out.getParent());Files.createDirectory(out);
  inputs=new LinkedHashMap<>();var lines=Files.readAllLines(dataset.resolve("inputs/operation-inputs.tsv"));
  for(String line:lines.subList(1,lines.size())){String[] p=line.split("\t",-1);long id=Long.parseLong(p[0]);var input=new Input(id,new String(Base64.getDecoder().decode(p[1]),StandardCharsets.UTF_8),Boolean.parseBoolean(p[2]));if(inputs.put(id,input)!=null)throw new IllegalStateException("Duplicate input");}
  V4Harness.write(out.resolve("OPENED.json"),Map.of("openedAt",java.time.Instant.now().toString(),"kind",blind?"FIRST_FROZEN_V6":"KNOWN_REGRESSION","operations",inputs.size(),"workers",4,"mask",15,"oracleRead",false));
  var before=V4Harness.snapshot();V4Harness.write(out.resolve("database-before.json"),before);var serial=phase(false);writeRows(out.resolve("serial.jsonl"),serial);var parallel=phase(true);writeRows(out.resolve("parallel.jsonl"),parallel);
  var differences=new ArrayList<Long>();int errors=0;for(int i=0;i<serial.length;i++){if(serial[i].error!=null||parallel[i].error!=null)errors++;if(serial[i].decision==null||!serial[i].decision.equals(parallel[i].decision))differences.add(serial[i].id);}
  var after=V4Harness.snapshot();V4Harness.write(out.resolve("database-after.json"),after);V4Harness.write(out.resolve("completion.json"),Map.of("operations",inputs.size(),"serialParallelEqual",differences.isEmpty(),"mismatchIds",differences,"technicalErrorCases",errors,"databaseUnchanged",before.equals(after),"completedAt",java.time.Instant.now().toString()));
  if(!before.equals(after))throw new IllegalStateException("Matching mutated tables");
 }
 static void writeRows(Path path,Row[] rows)throws Exception{
  try(var writer=Files.newBufferedWriter(path,StandardOpenOption.CREATE_NEW)){for(var row:rows){writer.write(V4Harness.json(Map.of("result",row)));writer.newLine();}}
 }
 public static void main(String[] args)throws Exception{
  root=Path.of(args[0]).toAbsolutePath();V4Harness.root=root;if(NativeRuntime457.mask()!=15)throw new IllegalStateException("Wrong CPU domain");
  switch(args[1]){
   case "load"->load(root.resolve(args[2]),root.resolve(args[3]));
   case "evaluate"->{dataset=root.resolve(args[2]);evaluate(root.resolve(args[3]),args[4].equals("blind"));}
   case "snapshot"->V4Harness.write(root.resolve(args[2]),V4Harness.snapshot());
   default->throw new IllegalArgumentException("Fixed action required");
  }
 }
}
