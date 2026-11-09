package ru.urfu.alphamatcher.experiment;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.sql.*;import java.util.*;
/** Fidelity check ONLY. Does not construct a parser, matching engine or read decision oracle. */
public class Stage4FDriver {
 static String encode(String v){return v==null?"-":Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8));}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]).toAbsolutePath();V4Harness.root=root;Path data=root.resolve("datasets/v6");
  if(NativeRuntime457.mask()!=15||!args[1].equals("snapshot"))throw new IllegalArgumentException("Preflight only");
  var queries=new LinkedHashMap<String,String>();
  queries.put("client","SELECT c.id,t.code,c.inn,c.name,c.last_name,c.first_name,c.middle_name FROM client c JOIN client_type t ON t.id=c.client_type_id");
  queries.put("contract","SELECT id,contract_number,contract_date FROM contract");
  queries.put("contract-party","SELECT p.contract_id,r.code,p.client_id FROM contract_party p JOIN contract_party_role r ON r.id=p.role_id");
  queries.put("operation","SELECT o.id,o.reference,o.payment_purpose,s.code,o.contract_id FROM operation o JOIN operation_status s ON s.id=o.status_id");
  queries.put("operation-party","SELECT p.operation_id,r.code,p.client_id,t.code,p.inn,p.name FROM operation_party p JOIN operation_party_role r ON r.id=p.role_id JOIN client_type t ON t.id=p.client_type_id");
  var counts=new LinkedHashMap<String,Integer>();
  try(var c=V4Harness.connection()){
   V4Harness.ownServer(c);
   for(var entry:queries.entrySet()){
    var actual=new ArrayList<String>();try(var s=c.createStatement();var rows=s.executeQuery(entry.getValue())){
     int n=rows.getMetaData().getColumnCount();while(rows.next()){var values=new ArrayList<String>();for(int i=1;i<=n;i++)values.add(encode(rows.getString(i)));actual.add(String.join("\t",values));}
    }
    var expected=new ArrayList<>(Files.readAllLines(data.resolve("validation/expected-"+entry.getKey()+".tsv")));Collections.sort(actual);Collections.sort(expected);
    if(!expected.equals(actual))throw new IllegalStateException("Fidelity mismatch "+entry.getKey()+" expected="+expected.size()+" actual="+actual.size());counts.put(entry.getKey(),actual.size());
   }
   var numbers=new HashMap<String,Integer>();var byId=new HashMap<Long,String>();
   for(String line:Files.readAllLines(data.resolve("validation/expected-contract.tsv"))){String[] fields=line.split("\\t");long id=Long.parseLong(new String(Base64.getDecoder().decode(fields[0]),StandardCharsets.UTF_8));String number=new String(Base64.getDecoder().decode(fields[1]),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);byId.put(id,number);numbers.merge(number,1,Integer::sum);}
   try(var st=c.createStatement();var rows=st.executeQuery("SELECT c.id,COUNT(*) FROM contract c JOIN contract d ON d.contract_number=c.contract_number GROUP BY c.id")){
    while(rows.next())if(rows.getInt(2)!=numbers.get(byId.get(rows.getLong(1))))throw new IllegalStateException("Unplanned MySQL collation collision");
   }
  }
  V4Harness.write(root.resolve(args[2]),Map.of("status","PASS","algorithmInvocations",0,"tablesVerified",counts,"snapshot",V4Harness.snapshot(),"affinityMask",15));
 }
}
