package ru.urfu.alphamatcher.stage7;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import ru.urfu.alphamatcher.experiment.MatchingEngine;

@Component
public final class EngineIdentity {
    public record Manifest(String version,Map<String,String> classes){}
    private final String version;
    public EngineIdentity() throws Exception {
        Manifest m;
        try(var in=getClass().getResourceAsStream("/accepted-engine.json")){m=new JsonMapper().readValue(in,Manifest.class);}
        if(!m.version().equals("stage6-accepted-1ebb240ff4bc"))throw new IllegalStateException("Неверный engine manifest.");
        for(var entry:m.classes().entrySet()){
            try(var in=MatchingEngine.class.getClassLoader().getResourceAsStream(entry.getKey())){
                if(in==null||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())).equals(entry.getValue()))
                    throw new IllegalStateException("Классы Matching Engine не совпадают с принятой Stage 6: "+entry.getKey());
            }
        }
        version=m.version();
    }
    public String version(){return version;}
}
