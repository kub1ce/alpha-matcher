package ru.urfu.alphamatcher.experiment;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import java.lang.reflect.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;
/** Research adapter, NOT an oracle. Reads inputs only; candidate discovery delegates unchanged JDBC. */
public final class V4Harness {
 static Path root;static Map<Long,Input> inputs;static boolean metamorphic;
 record Input(long id,long base,String purpose,boolean external){}
 record Result(long id,Decision decision,String error,long totalNs,long parsingNs,long jdbcNs,long rulesNs,long cpuBefore,long cpuAfter){}
 static String json(Object v)throws Exception{
  if(v==null)return "null";if(v instanceof Number||v instanceof Boolean)return v.toString();if(v instanceof Enum<?>)return json(v.toString());
  if(v instanceof CharSequence){StringBuilder b=new StringBuilder("\"");for(char c:v.toString().toCharArray()){switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}
  if(v instanceof Map<?,?> m){var a=new ArrayList<String>();for(var e:m.entrySet())a.add(json(e.getKey().toString())+":"+json(e.getValue()));return "{"+String.join(",",a)+"}";}
  if(v instanceof Iterable<?> list){var a=new ArrayList<String>();for(var x:list)a.add(json(x));return "["+String.join(",",a)+"]";}
  if(v.getClass().isRecord()){var a=new LinkedHashMap<String,Object>();for(var p:v.getClass().getRecordComponents())a.put(p.getName(),p.getAccessor().invoke(v));return json(a);}
  return json(v.toString());
 }
 static void write(Path p,Object value)throws Exception{Files.writeString(p,json(value)+"\n",StandardOpenOption.CREATE_NEW);}
 static Map<Long,Input> readInputs(String mode)throws Exception{
  var m=new LinkedHashMap<Long,Input>();Path p=root.resolve(mode.equals("metamorphic")?"inputs/metamorphic-inputs.tsv":"inputs/operation-inputs.tsv");
  var lines=Files.readAllLines(p);for(String line:lines.subList(1,lines.size())){String[] x=line.split("\t",-1);long id=Long.parseLong(x[0]);boolean meta=mode.equals("metamorphic");long base=meta?Long.parseLong(x[1]):id;String text=new String(Base64.getDecoder().decode(x[meta?2:1]),StandardCharsets.UTF_8);boolean external=meta||Boolean.parseBoolean(x[2]);if(m.put(id,new Input(id,base,text,external))!=null)throw new IllegalStateException("Duplicate input ID");}return m;
 }
 static Connection connection()throws Exception{
  Connection c=ExperimentDatabase.connect();c.setReadOnly(true);MatchingEngine.validateReadOnlyTransaction(c);return c;
 }
 static void ownServer(Connection c)throws Exception{
  try(var s=c.createStatement();var r=s.executeQuery("SELECT @@version, @@datadir, @@port, DATABASE(), @@server_uuid")){
   r.next();Path dir=Path.of(r.getString(2)).toAbsolutePath().normalize();Path expected=Path.of(System.getenv("MATCHING_DB_DATA_DIR")).toAbsolutePath().normalize();
   if(!expected.startsWith(root.resolve("runtime").toAbsolutePath().normalize()))throw new IllegalStateException("Expected data directory outside owned runtime");
   if(!r.getString(1).startsWith("8.4.8")||!dir.equals(expected)||r.getInt(3)!=33347||!r.getString(4).equals("matching_engine_exp"))throw new IllegalStateException("Refuse non-owned disposable server");
  }
 }
 static void load()throws Exception{
  String url=System.getenv("MATCHING_DB_URL");if(!url.startsWith("jdbc:mysql://127.0.0.1:33347/matching_engine_exp?"))throw new IllegalStateException("Unsafe loader URL");
  try(Connection c=DriverManager.getConnection(url,System.getenv("MATCHING_DB_USER"),System.getenv("MATCHING_DB_PASSWORD"))){
   ownServer(c);try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='matching_engine_exp'")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Refuse nonempty schema");}
   for(Path file:List.of(root.resolve("frozen/sql/001_schema.sql"),root.resolve("inputs/load.sql"))){
    StringBuilder cleaned=new StringBuilder();for(String line:Files.readAllLines(file))if(!line.stripLeading().startsWith("--"))cleaned.append(line).append('\n');
    for(String statement:cleaned.toString().split(";"))if(!statement.isBlank())try(var s=c.createStatement()){s.execute(statement);}
   }
  }
  write(root.resolve("audit/load-complete.json"),Map.of("status","PASS","writes","ONLY owned empty disposable catalog","algorithmCalls",0));
 }
 static final class Repository implements MatchingRepository{
  final JdbcMatchingRepository jdbc=new JdbcMatchingRepository();
  public Optional<Operation> operation(Connection c,long id)throws SQLException{
   Input i=inputs.get(id);if(i==null)throw new IllegalArgumentException("Unlisted input");Operation o=jdbc.operation(c,i.base).orElseThrow();
   if(!i.external&&!o.purpose().equals(i.purpose))throw new IllegalStateException("Stored purpose differs from frozen input");
   if(i.external&&!metamorphic&&!o.purpose().equals("[EXTERNAL_LONG_INPUT "+id+"]"))throw new IllegalStateException("Long storage marker differs");
   return Optional.of(i.external?new Operation(id,i.purpose,o.status(),o.activeStatus(),o.linkedContractId(),o.parties()):o);
  }
  public List<Candidate> candidates(Connection c,PurposeParser.Features f)throws SQLException{return jdbc.candidates(c,f);}
 }
 static Map<String,Object> snapshot()throws Exception{
  var out=new LinkedHashMap<String,Object>();try(Connection c=connection()){
   ownServer(c);for(String table:List.of("client","contract","contract_party","operation","operation_party","operation_status","contract_party_role","operation_party_role")){
    MessageDigest digest=MessageDigest.getInstance("SHA-256");int count=0;
    try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY id")){
     int columns=r.getMetaData().getColumnCount();while(r.next()){count++;var row=new ArrayList<Object>();for(int col=1;col<=columns;col++)row.add(r.getString(col));digest.update(json(row).getBytes(StandardCharsets.UTF_8));digest.update((byte)'\n');}
    }out.put(table,Map.of("rows",count,"sha256",HexFormat.of().formatHex(digest.digest())));
   }
  }return out;
 }
 static void validate()throws Exception{
  inputs=readInputs("evaluate");Repository repo=new Repository();int external=0;
  try(Connection c=connection()){
   ownServer(c);for(Input i:inputs.values()){Operation op=repo.operation(c,i.id).orElseThrow();if(!op.purpose().equals(i.purpose))throw new IllegalStateException("Input fidelity failed");if(i.external)external++;}
   var expected=new TreeMap<Long,String>();var lines=Files.readAllLines(root.resolve("inputs/catalog-contracts.tsv"));
   for(String line:lines.subList(1,lines.size())){String[] x=line.split("\t");expected.put(Long.parseLong(x[0]),new String(Base64.getDecoder().decode(x[1]),StandardCharsets.UTF_8));}
   var actual=new TreeMap<Long,String>();try(var st=c.createStatement();var rs=st.executeQuery("SELECT id,contract_number FROM contract ORDER BY id")){while(rs.next())actual.put(rs.getLong(1),rs.getString(2));}
   if(!expected.equals(actual))throw new IllegalStateException("Catalog ID/number differs from frozen input");
   var frequency=new HashMap<String,Integer>();for(String number:expected.values())frequency.merge(number.toLowerCase(Locale.ROOT),1,Integer::sum);
   try(var st=c.createStatement();var rs=st.executeQuery("SELECT c.id,COUNT(*) FROM contract c JOIN contract d ON d.contract_number=c.contract_number GROUP BY c.id")){
    while(rs.next())if(rs.getInt(2)!=frequency.get(expected.get(rs.getLong(1)).toLowerCase(Locale.ROOT)))throw new IllegalStateException("Unexpected SQL collation alternative");
   }
  }
  write(root.resolve("validation/sql-preflight.json"),Map.of("status","PASS","operations",inputs.size(),"externalLongInputs",external,"algorithmInvocations",0,"snapshot",snapshot()));
 }
 static Result one(MatchingEngine engine,Connection c,Input input){
  if(NativeRuntime457.mask()!=15||NativeRuntime457.threadMask()!=15)throw new IllegalStateException("Wrong native worker affinity");int before=NativeRuntime457.cpu();
  try{var t=engine.measure(c,input.id);int after=NativeRuntime457.cpu();if(before>3||after>3)throw new IllegalStateException("CPU outside shared mask");return new Result(input.id,t.decision(),null,t.totalNanos(),t.parsingNanos(),t.sqlNanos(),t.rulesNanos(),before,after);}
  catch(Exception error){return new Result(input.id,null,error.getClass().getName()+": "+error.getMessage(),0,0,0,0,before,NativeRuntime457.cpu());}
 }
 static Result[] phase(boolean parallel)throws Exception{
  Input[] list=inputs.values().toArray(Input[]::new);Result[] result=new Result[list.length];int workers=parallel?4:1;
  try(var pool=Executors.newFixedThreadPool(workers)){
   var tasks=new ArrayList<Future<?>>();for(int w=0;w<workers;w++){int worker=w;tasks.add(pool.submit(()->{
    try(Connection c=connection()){ownServer(c);var engine=new MatchingEngine(new Repository(),new PurposeParser());for(int i=worker;i<list.length;i+=workers)result[i]=one(engine,c,list[i]);}
    catch(Exception error){throw new CompletionException(error);}
   }));}for(var f:tasks)f.get(45,TimeUnit.MINUTES);
  }
  for(Result r:result)if(r==null)throw new IllegalStateException("Lost result");return result;
 }
 static void results(Path dir,String name,Result[] rows)throws Exception{
  try(var out=Files.newBufferedWriter(dir.resolve(name+".jsonl"),StandardOpenOption.CREATE_NEW)){
   for(Result r:rows){var record=new LinkedHashMap<String,Object>();record.put("result",r);record.put("fullDecisionString",r.decision==null?null:r.decision.toString());record.put("inputSource",inputs.get(r.id).external?(metamorphic?"METAMORPHIC_INPUT_OVER_BASE_SQL_OPERATION":"EXTERNAL_LONG_TEXT_OVER_SQL_OPERATION"):"ACTUAL_SQL_TEXT");out.write(json(record));out.write('\n');}
  }
 }
 static void evaluate(String action)throws Exception{
  if(!Files.exists(root.resolve("FREEZE_SHA256SUMS")))throw new IllegalStateException("No frozen corpus/protocol");
  if(java.time.Instant.now().isBefore(java.time.Instant.parse(Files.readString(root.resolve("protocol/FIRST_ALLOWED_UTC.txt")).strip())))throw new IllegalStateException("Before first allowed semantic opening");
  metamorphic=action.equals("metamorphic");inputs=readInputs(action);Path parent=root.resolve("results");Files.createDirectories(parent);Path out=parent.resolve(metamorphic?"metamorphic":"first-v4");Files.createDirectory(out);
  write(out.resolve("OPENED.json"),Map.of("openedAt",java.time.Instant.now().toString(),"mode",action,"workers",4,"nativeMask",NativeRuntime457.mask(),"reportedLogicalCpus",Runtime.getRuntime().availableProcessors(),"source","Frozen unchanged algorithm + real JdbcMatchingRepository","oracleReadByHarness",false));
  var before=snapshot();write(out.resolve("database-before.json"),before);
  Result[] serial=phase(false);results(out,"serial",serial);Result[] parallel=phase(true);results(out,"parallel",parallel);
  var mismatches=new ArrayList<Long>();int errors=0;
  for(int i=0;i<serial.length;i++){if(serial[i].error!=null||parallel[i].error!=null)errors++;if(serial[i].decision==null||!serial[i].decision.equals(parallel[i].decision))mismatches.add(serial[i].id);}
  var after=snapshot();write(out.resolve("database-after.json"),after);boolean unchanged=before.equals(after);
  write(out.resolve("completion.json"),Map.of("status","COMPLETED_FIRST_FROZEN_PROTOCOL","operations",inputs.size(),"serialParallelEqual",mismatches.isEmpty(),"mismatchIds",mismatches,"technicalErrorCases",errors,"databaseUnchanged",unchanged,"finishedAt",java.time.Instant.now().toString()));
  if(!unchanged)throw new IllegalStateException("Unexpected database state mutation");
 }
 public static void main(String[] args)throws Exception{
  root=Path.of(args[0]).toAbsolutePath();String action=args[1];if(NativeRuntime457.mask()!=15)throw new IllegalStateException("Shared two-physical-core mask required");
  try{switch(action){case "load"->load();case "validate"->validate();case "evaluate","metamorphic"->evaluate(action);default->throw new IllegalArgumentException("Unknown fixed action");}}
  catch(Throwable failure){try{write(root.resolve("audit/harness-failure-"+System.currentTimeMillis()+".json"),Map.of("action",action,"error",failure.toString()));}catch(Exception ignored){}throw failure;}
 }
}
