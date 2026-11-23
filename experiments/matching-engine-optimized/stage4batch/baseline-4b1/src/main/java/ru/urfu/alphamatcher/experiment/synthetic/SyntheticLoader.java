package ru.urfu.alphamatcher.experiment.synthetic;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import ru.urfu.alphamatcher.experiment.ExperimentDatabase;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;

/** Transactional replacement of the reserved generated namespace, preserving stage-1 fixtures. */
public final class SyntheticLoader {
    private static final long END_ID = 10_000_000;

    public void replace(Connection connection, Dataset dataset) throws SQLException {
        if (!ExperimentDatabase.CATALOG.equals(connection.getCatalog()) || connection.getAutoCommit()
                || connection.isReadOnly() || !"MySQL".equals(connection.getMetaData().getDatabaseProductName()))
            throw new IllegalArgumentException("Загрузка разрешена только в транзакцию выделенной экспериментальной MySQL");
        boolean validIds=dataset.clients().stream().allMatch(client->reserved(client.id()))
                && dataset.contracts().stream().allMatch(contract->reserved(contract.id()) && reserved(contract.payer()) && reserved(contract.recipient()) && (contract.beneficiary()==null || reserved(contract.beneficiary())))
                && dataset.payments().stream().allMatch(payment->reserved(payment.id()) && (payment.payer()==null || reserved(payment.payer())) && reserved(payment.recipient()) && (payment.linked()==null || reserved(payment.linked())));
        if(!validIds) throw new IllegalArgumentException("Все данные генератора должны находиться в его зарезервированном диапазоне ID");
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM operation_contract_candidate WHERE operation_id >= 1000000 AND operation_id < 10000000 OR contract_id >= 1000000 AND contract_id < 10000000");
            for (String table : new String[]{"operation_party", "contract_party"}) {
                String owner = table.equals("operation_party") ? "operation_id" : "contract_id";
                statement.executeUpdate("DELETE FROM " + table + " WHERE " + owner + ">=" + SyntheticGenerator.FIRST_ID + " AND " + owner + "<" + END_ID);
            }
            for (String table : new String[]{"operation", "contract", "client"})
                statement.executeUpdate("DELETE FROM " + table + " WHERE id >= " + SyntheticGenerator.FIRST_ID + " AND id < " + END_ID);
        }
        Map<String,Integer> clientTypes = dictionary(connection,"client_type");
        Map<String,Integer> contractRoles = dictionary(connection,"contract_party_role");
        Map<String,Integer> operationRoles = dictionary(connection,"operation_party_role");
        Map<String,Integer> statuses = dictionary(connection,"operation_status");
        int contractType = dictionary(connection,"contract_type").get("UNKNOWN");
        Map<Long,Client> clients = new HashMap<>();
        try(var statement=connection.prepareStatement("INSERT INTO client (id,bank_client_id,client_type_id,inn,last_name,first_name,middle_name,name) VALUES (?,?,?,?,?,?,?,?)")) {
            int count=0;
            for(Client client:dataset.clients()) {
                clients.put(client.id(),client);
                statement.setLong(1,client.id()); statement.setString(2,"EXPGEN-"+client.id());
                statement.setInt(3,clientTypes.get(client.type())); statement.setString(4,client.inn());
                statement.setString(5,client.last()); statement.setString(6,client.first());
                statement.setString(7,client.middle()); statement.setString(8,client.name());
                statement.addBatch(); if(++count%500==0) statement.executeBatch();
            }
            statement.executeBatch();
        }
        try(var statement=connection.prepareStatement("INSERT INTO contract (id,contract_type_id,contract_number,contract_date) VALUES (?,?,?,'2026-01-01')")) {
            int count=0;
            for(Contract contract:dataset.contracts()) {
                statement.setLong(1,contract.id()); statement.setInt(2,contractType); statement.setString(3,contract.number());
                statement.addBatch(); if(++count%500==0) statement.executeBatch();
            }
            statement.executeBatch();
        }
        try(var statement=connection.prepareStatement("INSERT INTO contract_party (contract_id,role_id,client_id) VALUES (?,?,?)")) {
            int count=0;
            for(Contract contract:dataset.contracts()) {
                for(String role:new String[]{"PAYER","RECIPIENT","BENEFICIARY"}) {
                    Long client=role.equals("PAYER")?Long.valueOf(contract.payer()):role.equals("RECIPIENT")?Long.valueOf(contract.recipient()):contract.beneficiary();
                    if(client==null) continue;
                    statement.setLong(1,contract.id()); statement.setInt(2,contractRoles.get(role)); statement.setLong(3,client);
                    statement.addBatch(); if(++count%500==0) statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
        try(var statement=connection.prepareStatement("INSERT INTO operation (id,reference,operation_at,amount_rub,payment_purpose,status_id,contract_id) VALUES (?,?,'2026-10-01 12:00:00',1000.00,?,?,?)")) {
            int count=0;
            for(Payment payment:dataset.payments()) {
                statement.setLong(1,payment.id()); statement.setString(2,payment.reference()); statement.setString(3,payment.purpose());
                statement.setInt(4,statuses.get(payment.status())); statement.setObject(5,payment.linked());
                statement.addBatch(); if(++count%500==0) statement.executeBatch();
            }
            statement.executeBatch();
        }
        try(var statement=connection.prepareStatement("INSERT INTO operation_party (operation_id,role_id,client_id,client_type_id,inn,name) VALUES (?,?,?,?,?,?)")) {
            int count=0;
            for(Payment payment:dataset.payments()) {
                for(String role:new String[]{"PAYER","RECIPIENT"}) {
                    Long clientId=role.equals("PAYER")?payment.payer():Long.valueOf(payment.recipient());
                    Client client=clients.get(clientId);
                    statement.setLong(1,payment.id()); statement.setInt(2,operationRoles.get(role)); statement.setObject(3,clientId);
                    statement.setInt(4,client==null?clientTypes.get("INDIVIDUAL"):clientTypes.get(client.type()));
                    statement.setString(5,role.equals("PAYER")?payment.payerInn():payment.recipientInn());
                    statement.setString(6,client==null?null:client.name());
                    statement.addBatch(); if(++count%500==0) statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static boolean reserved(long id) { return id>=SyntheticGenerator.FIRST_ID && id<END_ID; }

    private static Map<String,Integer> dictionary(Connection connection,String table) throws SQLException {
        Map<String,Integer> values=new HashMap<>();
        try(var statement=connection.createStatement(); var rs=statement.executeQuery("SELECT id,code FROM "+table)) {
            while(rs.next()) values.put(rs.getString("code"),rs.getInt("id"));
        }
        return values;
    }
}
