package ru.urfu.alphamatcher.stage7;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Launcher-only lifecycle; localhost binding + random per-run token. No data API. */
@RestController
public final class LocalLifecycle {
    private final ConfigurableApplicationContext context;
    public LocalLifecycle(ConfigurableApplicationContext context){this.context=context;}
    private void check(String token){
        String expected=System.getenv("STAGE7_LIFECYCLE_TOKEN");
        if(expected==null||token==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    @GetMapping("/internal/health") public String health(@RequestHeader(value="X-Stage7-Token",required=false) String token){check(token);return "ready";}
    @PostMapping("/internal/shutdown") public String shutdown(@RequestHeader(value="X-Stage7-Token",required=false) String token){
        check(token);Thread.ofPlatform().name("stage7-graceful-stop").start(()->{try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();}context.close();});return "stopping";
    }
}
