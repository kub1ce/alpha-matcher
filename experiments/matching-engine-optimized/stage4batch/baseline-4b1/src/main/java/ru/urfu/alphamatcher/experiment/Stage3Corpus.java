package ru.urfu.alphamatcher.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.Scenario;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.Truth;

/** Frozen, hand-labelled worlds. Never calls the parser/engine to manufacture labels. */
public final class Stage3Corpus {
    public record Case(long id,String label,String purpose,Truth truth,boolean automatable,String expectedReason,
                       long payer,long recipient,String payerInn,String recipientInn,String state,Long linked) { }
    private Stage3Corpus() { }

    public static List<Case> read(Path path) throws Exception {
        List<Case> result=new ArrayList<>();
        List<String> lines=Files.readAllLines(path);
        for(String line:lines.subList(1,lines.size())) {
            if(line.isBlank()) continue;
            String[] p=line.split("\t",-1);
            if(p.length!=20) throw new IllegalArgumentException("Expected 20 columns: "+line);
            long id=32_000_000+Long.parseLong(p[0]);
            var truth=new Truth(id,Scenario.CLEAR,p[3],optionalLong(p[4]),optionalLong(p[5]),list(p[12]),list(p[13]),
                    list(p[14]),list(p[15]),list(p[16]),Boolean.parseBoolean(p[17]));
            String purpose=expand(p[2]);
            result.add(new Case(id,p[1],purpose,truth,Boolean.parseBoolean(p[18]),p[19],Long.parseLong(p[6]),Long.parseLong(p[7]),p[8],p[9],p[10],optionalLong(p[11])));
        }
        return List.copyOf(result);
    }

    private static String expand(String text) {
        var matcher=java.util.regex.Pattern.compile("\\{\\{(PAD|INNS):([0-9]+)}}").matcher(text);
        var out=new StringBuilder();
        while(matcher.find()) {
            int size=Integer.parseInt(matcher.group(2));
            if(size>70000) throw new IllegalArgumentException("Corpus expansion is bounded");
            String replacement=matcher.group(1).equals("PAD")?"x ".repeat((size+1)/2).substring(0,size)
                    : "ИНН 990000010152, ".repeat(size);
            matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out); return out.toString();
    }

    public static void load(Connection connection,Path world,List<Case> cases) throws Exception {
        if(!ExperimentDatabase.CATALOG.equals(connection.getCatalog()) || connection.getAutoCommit())
            throw new IllegalArgumentException("Disposable transaction required");
        try(var statement=connection.createStatement()) {
            statement.executeUpdate("DELETE FROM operation_contract_candidate WHERE operation_id BETWEEN 30000000 AND 39999999 OR contract_id BETWEEN 30000000 AND 39999999");
            statement.executeUpdate("DELETE FROM operation_party WHERE operation_id BETWEEN 30000000 AND 39999999");
            statement.executeUpdate("DELETE FROM contract_party WHERE contract_id BETWEEN 30000000 AND 39999999");
            for(String table:List.of("operation","contract","client")) statement.executeUpdate("DELETE FROM "+table+" WHERE id BETWEEN 30000000 AND 39999999");
            String sql=Files.readString(world).replaceAll("(?m)^--[^\\r\\n]*", "");
            for(String part:sql.split(";")) if(!part.isBlank()) statement.execute(part);
        }
        try(var operation=connection.prepareStatement("INSERT INTO operation(id,reference,operation_at,payment_purpose,status_id,contract_id) VALUES (?,?,'2026-10-29 10:00:00',?,(SELECT id FROM operation_status WHERE code=?),?)");
            var party=connection.prepareStatement("INSERT INTO operation_party(operation_id,role_id,client_id,client_type_id,inn,name) SELECT ?,r.id,cl.id,cl.client_type_id,COALESCE(?,cl.inn),cl.name FROM client cl JOIN operation_party_role r ON r.code=? WHERE cl.id=?")) {
            for(Case value:cases) {
                operation.setLong(1,value.id()); operation.setString(2,"CORPUS-"+value.id()); operation.setString(3,value.purpose()); operation.setString(4,value.state()); operation.setObject(5,value.linked()); operation.executeUpdate();
                for(String role:List.of("PAYER","RECIPIENT")) {
                    long client=role.equals("PAYER")?value.payer():value.recipient(); if(client==0) continue;
                    String inn=role.equals("PAYER")?value.payerInn():value.recipientInn();
                    party.setLong(1,value.id()); party.setString(2,inn.equals("-")?null:inn); party.setString(3,role); party.setLong(4,client); party.executeUpdate();
                }
            }
        }
    }

    private static Long optionalLong(String value) { return value.equals("-")?null:Long.valueOf(value); }
    private static List<String> list(String value) { return value.equals("-")?List.of():List.copyOf(Arrays.asList(value.split("\\|"))); }
}
