package ru.urfu.alphamatcher.stage7;

import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;

/** Reconstructs historical INPUTS, never historical Outcomes. Fingerprint before SQL loading. */
public final class PresetCatalog {
    public record Spec(String id,String profile,long seed,int clients,int contracts,int generatedPayments,
                       int payments,String datasetSHA256,String source,String sourceManifestSHA256,
                       String sourceConfig,String subset){}
    public record Prepared(String key,DataImport.Dataset display,SyntheticGenerator.Dataset synthetic,String fullDatasetSHA256){}
    public static List<Spec> specs(){
        try(var in=PresetCatalog.class.getResourceAsStream("/presets.json")){
            return List.of(new JsonMapper().readValue(in,Spec[].class));
        }catch(Exception e){throw new IllegalStateException("Не удалось прочитать список наборов.",e);}
    }
    public static Prepared generate(String id){
        Spec s=specs().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Набор недоступен."));
        if(s.generatedPayments()!=10000||s.payments()>10000||s.payments()<1)throw new IllegalStateException("Неверный manifest набора.");
        var original=new SyntheticGenerator().generate(SyntheticGenerator.Config.standard(s.seed(),s.clients(),s.contracts(),s.generatedPayments(),SyntheticGenerator.Profile.valueOf(s.profile())));
        String sha;
        try{sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
        if(!sha.equals(s.datasetSHA256()))throw new IllegalStateException("Исторический fingerprint набора не совпал.");
        // Truth is deliberately removed from runtime input. It is used only by separate verification.
        var input=new SyntheticGenerator.Dataset(original.config(),original.clients(),original.contracts(),
                original.payments().subList(0,s.payments()),List.of());
        var clients=input.clients().stream().map(c->new DataImport.Client(c.id(),c.inn(),c.name(),c.last(),c.first(),c.middle())).toList();
        var contracts=input.contracts().stream().map(c->new DataImport.Contract(c.id(),c.number(),new DataImport.Party(c.payer(),null),new DataImport.Party(c.recipient(),null),c.beneficiary()==null?null:new DataImport.Party(c.beneficiary(),null))).toList();
        var payments=input.payments().stream().map(p->new DataImport.Payment(p.id(),p.purpose(),p.payer()==null?null:new DataImport.Party(p.payer(),p.payerInn()),new DataImport.Party(p.recipient(),p.recipientInn()),DataImport.Fault.NONE)).toList();
        return new Prepared(s.id(),new DataImport.Dataset(label(s.id()),clients,contracts,payments),input,sha);
    }
    public static String label(String key){return switch(key){case "MIXED1K"->"1 000 платежей";case "MIXED10K"->"MIXED 10 000";case "STRESS10K"->"STRESS 10 000";default->key;};}
    private PresetCatalog(){}
}
