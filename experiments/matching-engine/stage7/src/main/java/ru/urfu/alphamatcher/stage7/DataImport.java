package ru.urfu.alphamatcher.stage7;

import java.util.*;
import java.nio.charset.StandardCharsets;
import ru.urfu.alphamatcher.experiment.Inn;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;

/** Strict JSON adapter, not a parser of payment semantics. Raw purpose is retained. */
public final class DataImport {
    public static final int MAX_BYTES=4*1024*1024;
    public record Client(long id,String inn,String name,String lastName,String firstName,String middleName){}
    public record Party(long clientId,String inn){}
    public record Contract(long id,String number,Party payer,Party recipient,Party beneficiary){}
    public enum Fault { NONE, JDBC_ERROR }
    public record Payment(long id,String purpose,Party payer,Party recipient,Fault fault){}
    public record Dataset(String name,List<Client> clients,List<Contract> contracts,List<Payment> payments){
        public Dataset {
            clients=clients==null?List.of():List.copyOf(clients);
            contracts=contracts==null?List.of():List.copyOf(contracts);
            payments=payments==null?List.of():List.copyOf(payments);
        }
    }
    private static final JsonMapper JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static Dataset parse(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Размер JSON должен быть от 1 байта до 4 МБ.");
        Dataset data;
        try {data=JSON.readValue(bytes,Dataset.class);}
        catch(RuntimeException e){throw new IllegalArgumentException("Некорректный JSON. Проверьте поля и типы по примеру импорта.");}
        validate(data);return data;
    }
    public static void validate(Dataset d){
        if(d==null)fail("Набор не указан.");
        text(d.name(),120,"Название набора");
        if(d.clients().isEmpty()||d.clients().size()>6000)fail("Нужно от 1 до 6000 клиентов.");
        if(d.contracts().size()>1000)fail("Не более 1000 договоров.");
        if(d.payments().isEmpty()||d.payments().size()>1000)fail("Нужно от 1 до 1000 платежей.");
        if((long)d.payments().size()*Math.max(1,d.contracts().size())>50000)fail("Набор слишком велик для подробной демонстрации: платежи × договоры не более 50 000.");
        Set<Long> clients=new HashSet<>(),contracts=new HashSet<>(),payments=new HashSet<>();
        for(Client c:d.clients()){
            if(c==null)fail("Пустая запись клиента.");
            id(c.id(),clients,"клиента");
            if(!Inn.valid(c.inn()))fail("Некорректный ИНН клиента "+c.id()+".");
            if(c.name()!=null&&!c.name().isBlank())text(c.name(),128,"Название клиента");
            else {text(c.lastName(),64,"Фамилия");text(c.firstName(),64,"Имя");text(c.middleName(),64,"Отчество");}
        }
        for(Contract c:d.contracts()){
            if(c==null)fail("Пустая запись договора.");
            id(c.id(),contracts,"договора");text(c.number(),128,"Номер договора");
            party(c.payer(),clients);party(c.recipient(),clients);
            if(c.beneficiary()!=null)party(c.beneficiary(),clients);
        }
        for(Payment p:d.payments()){
            if(p==null)fail("Пустая запись платежа.");
            id(p.id(),payments,"платежа");
            if(p.purpose()==null||p.purpose().isBlank()||p.purpose().getBytes(StandardCharsets.UTF_8).length>60000)
                fail("Назначение платежа "+p.id()+": требуется текст до 60 000 байт UTF-8.");
            party(p.payer(),clients);party(p.recipient(),clients);
        }
    }
    private static void party(Party p,Set<Long> ids){
        if(p==null||!ids.contains(p.clientId()))fail("Участник ссылается на отсутствующего клиента.");
        if(p.inn()!=null&&!Inn.valid(p.inn()))fail("Некорректный ИНН участника.");
    }
    private static void id(long id,Set<Long> ids,String type){
        if(id<1||id>999999999L||!ids.add(id))fail("Повторяющийся или недопустимый ID "+type+": "+id+".");
    }
    private static void text(String v,int max,String label){
        if(v==null||v.isBlank()||v.length()>max||v.chars().anyMatch(Character::isISOControl))fail(label+": требуется строка до "+max+" символов.");
    }
    private static void fail(String s){throw new IllegalArgumentException(s);}
    private DataImport(){}
}
