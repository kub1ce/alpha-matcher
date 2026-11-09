package ru.urfu.alphamatcher.stage7;

import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import ru.urfu.alphamatcher.experiment.ExperimentDatabase;

/** The ONLY writer. Refuses anything except the launcher-owned, fresh disposable server. */
@Component
public final class DemoStore {
    public static final List<String> TABLES=List.of("client","contract","contract_party","operation","operation_party",
            "operation_contract_candidate","operation_status","contract_party_role","operation_party_role");
    private static String env(String name){String s=System.getenv(name);if(s==null||s.isBlank())throw new IllegalStateException("Запустите приложение через start-demo.ps1.");return s;}
    public static void assertOwner(Connection c) throws Exception {
        Path run=Path.of(env("STAGE7_RUN_DIR")).toRealPath();
        Path expected=run.resolve("mysql-data").toRealPath();
        if(!Files.isRegularFile(run.resolve("ownership.json")))throw new SecurityException("Нет подтверждения владельца стенда.");
        try(var s=c.createStatement();var rs=s.executeQuery("SELECT @@port,@@datadir,@@server_uuid,DATABASE(),VERSION()")){
            if(!rs.next()||rs.getInt(1)!=Integer.parseInt(env("STAGE7_DB_PORT"))
                    ||!Path.of(rs.getString(2)).toRealPath().equals(expected)
                    ||!rs.getString(3).equals(env("STAGE7_SERVER_UUID"))
                    ||!ExperimentDatabase.CATALOG.equals(rs.getString(4))||!rs.getString(5).startsWith("8.4.8"))
                throw new SecurityException("Сервер не принадлежит текущему disposable-стенду.");
        }
    }
    public void replace(DataImport.Dataset d) throws Exception {
        DataImport.validate(d);
        try(var c=DriverManager.getConnection(env("MATCHING_DB_URL"),env("STAGE7_WRITER_USER"),env("STAGE7_WRITER_PASSWORD"))){
            assertOwner(c);c.setAutoCommit(false);
            try{
                try(var s=c.createStatement()){
                    for(String table:List.of("operation_contract_candidate","operation_party","operation","contract_party","contract","client"))
                        s.executeUpdate("DELETE FROM "+table);
                }
                try(var s=c.prepareStatement("INSERT INTO client(id,client_type_id,inn,name,last_name,first_name,middle_name) VALUES (?,(SELECT id FROM client_type WHERE code=?),?,?,?,?,?)")){
                    for(var x:d.clients()){
                        s.setLong(1,x.id());s.setString(2,x.name()!=null&&!x.name().isBlank()?"LEGAL_ENTITY":"INDIVIDUAL");
                        s.setString(3,x.inn());s.setString(4,x.name());s.setString(5,x.lastName());s.setString(6,x.firstName());s.setString(7,x.middleName());s.addBatch();
                    }s.executeBatch();
                }
                try(var s=c.prepareStatement("INSERT INTO contract(id,contract_type_id,contract_number) VALUES (?,(SELECT id FROM contract_type WHERE code='UNKNOWN'),?)")){
                    for(var x:d.contracts()){s.setLong(1,x.id());s.setString(2,x.number());s.addBatch();}s.executeBatch();
                }
                for(var x:d.contracts()){
                    party(c,"contract",x.id(),"PAYER",x.payer());party(c,"contract",x.id(),"RECIPIENT",x.recipient());
                    if(x.beneficiary()!=null)party(c,"contract",x.id(),"BENEFICIARY",x.beneficiary());
                }
                try(var s=c.prepareStatement("INSERT INTO operation(id,reference,operation_at,payment_purpose,status_id) VALUES (?,?,CURRENT_TIMESTAMP(6),?,(SELECT id FROM operation_status WHERE code='LOADED'))")){
                    for(var x:d.payments()){s.setLong(1,x.id());s.setString(2,"ST7-"+x.id());s.setString(3,x.purpose());s.addBatch();}s.executeBatch();
                }
                for(var x:d.payments()){party(c,"operation",x.id(),"PAYER",x.payer());party(c,"operation",x.id(),"RECIPIENT",x.recipient());}
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    void replacePreset(ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.Dataset input) throws Exception {
        try(var c=DriverManager.getConnection(env("MATCHING_DB_URL"),env("STAGE7_WRITER_USER"),env("STAGE7_WRITER_PASSWORD"))){
            assertOwner(c);c.setAutoCommit(false);
            try{
                try(var statement=c.createStatement()){
                    for(String table:List.of("operation_contract_candidate","operation_party","operation","contract_party","contract","client"))statement.executeUpdate("DELETE FROM "+table);
                }
                new ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader().replace(c,input);c.commit();
            }catch(Exception error){c.rollback();throw error;}
        }
    }
    private static void party(Connection c,String kind,long id,String role,DataImport.Party p)throws SQLException{
        // kind comes from these two constant call sites, never from user input.
        if(!Set.of("operation","contract").contains(kind))throw new IllegalArgumentException();
        String extra=kind.equals("operation")?",client_type_id":"";
        String value=kind.equals("operation")?",(SELECT client_type_id FROM client WHERE id=?)":"";
        try(var s=c.prepareStatement("INSERT INTO "+kind+"_party("+kind+"_id,role_id,client_id,inn"+extra+") VALUES (?,(SELECT id FROM "+kind+"_party_role WHERE code=?),?,?"+value+")")){
            s.setLong(1,id);s.setString(2,role);s.setLong(3,p.clientId());s.setString(4,p.inn());if(kind.equals("operation"))s.setLong(5,p.clientId());s.executeUpdate();
        }
    }
    public Map<String,String> snapshot() throws Exception {
        var values=new LinkedHashMap<String,String>();
        try(var c=ExperimentDatabase.connect()){
            c.setReadOnly(true);assertOwner(c);
            for(String table:TABLES){
                var digest=MessageDigest.getInstance("SHA-256");
                try(var s=c.createStatement();var rs=s.executeQuery("SELECT * FROM "+table+" ORDER BY id")){
                    while(rs.next())for(int col=1;col<=rs.getMetaData().getColumnCount();col++){
                        String v=rs.getString(col);
                        if(v==null)digest.update((byte)0);
                        else {byte[] bytes=v.getBytes(java.nio.charset.StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
                        digest.update((byte)255);
                    }
                }values.put(table,HexFormat.of().formatHex(digest.digest()));
            }c.rollback();
        }return Map.copyOf(values);
    }
}
