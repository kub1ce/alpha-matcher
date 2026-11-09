package ru.urfu.alphamatcher.experiment;
import java.nio.file.*;
import java.util.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Final OLD v1/v2/v3 regression only. Never matches any v5 operation. */
public class FinalKnownRegression {
 static Path root;
 static void clear(java.sql.Connection c)throws Exception{
  try(var s=c.createStatement()){
   s.executeUpdate("DELETE FROM operation_contract_candidate WHERE operation_id BETWEEN 30000000 AND 39999999 OR contract_id BETWEEN 30000000 AND 39999999");
   s.executeUpdate("DELETE FROM operation_party WHERE operation_id BETWEEN 30000000 AND 39999999");
   s.executeUpdate("DELETE FROM contract_party WHERE contract_id BETWEEN 30000000 AND 39999999");
   for(String table:List.of("operation","contract","client"))s.executeUpdate("DELETE FROM "+table+" WHERE id BETWEEN 30000000 AND 39999999");
  }c.commit();
 }
 public static void main(String[] args)throws Exception{
  root=Path.of(args[0]);V4Harness.root=Path.of(args[1]);
  if(NativeRuntime457.mask()!=15 || NativeRuntime457.threadMask()!=15)throw new IllegalStateException("Wrong affinity");
  try(var c=ExperimentDatabase.connect()){
   V4Harness.ownServer(c);
   try(var s=c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM operation WHERE id BETWEEN 30000000 AND 39999999")){rs.next();if(rs.getInt(1)!=0)throw new IllegalStateException("Reserved test range is occupied");}
  }
  Stage4BatchQualityMain.main(new String[]{"after"});
  var before=V4Harness.snapshot();V4Harness.write(root.resolve("database-before.json"),before);var results=new ArrayList<Object>();
  try(var c=ExperimentDatabase.connect()){
   V4Harness.ownServer(c);
   for(String corpus:List.of("development-v1","final-v1","final-v2","final-v3")){
    var cases=Stage3Corpus.read(Path.of("stage3/corpora/"+corpus+".tsv"));String version=corpus.substring(corpus.length()-2);
    try{
     Stage3Corpus.load(c,Path.of("stage3/corpora/world-"+version+".sql"),cases);
     if(!version.equals("v1"))try(var s=c.createStatement()){
      String sql=Files.readString(Path.of("stage3/corpora/external-"+version+".sql")).replaceAll("(?m)^--[^\\r\\n]*","");for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);
     }c.commit();
     var serial=new ArrayList<Decision>();try(var session=MatchingEngine.openReadOnlySession()){for(var value:cases)serial.add(session.match(value.id()));}
     var parallel=new ArrayList<Decision>();var ids=cases.stream().map(Stage3Corpus.Case::id).toList();
     var summary=new ParallelMatcher().run(ids,ParallelMatcher.Config.standard(4),new ParallelMatcher.Cancellation(),o->{if(o.state()!=ParallelMatcher.State.SUCCESS)throw new IllegalStateException("Technical failure");parallel.add(o.result().decision());});
     var q=new QualityReport();for(int i=0;i<cases.size();i++)q.accept(cases.get(i).truth(),serial.get(i));
     V4Harness.write(root.resolve(corpus+"-full-decisions.json"),Map.of("serial",serial,"parallel",parallel));
     boolean equal=serial.equals(parallel);results.add(Map.of("corpus",corpus,"cases",cases.size(),"fullEquality",equal,"quality",q.counts(),"summary",summary));
     if(!equal || q.counts().get("wrongSafe")!=0 || q.counts().get("statusMismatch")!=0)throw new IllegalStateException("Known corpus regression");
    }finally{c.rollback();clear(c);}
   }
  }
  var after=V4Harness.snapshot();V4Harness.write(root.resolve("database-after.json"),after);
  V4Harness.write(root.resolve("COMPLETED.json"),Map.of("corpora",results,"databaseUnchanged",before.equals(after),"v5MatcherCalls",0,"mask",15));
  if(!before.equals(after))throw new IllegalStateException("Historical regression changed v5 DB");
 }
}
