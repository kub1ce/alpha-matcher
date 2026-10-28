package ru.urfu.alphamatcher.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-classpath-only control plane for a real Vaadin callback blocked under its session lock. */
@RestController
@RequestMapping("/qa/barrier-control")
public class QaBarrierController {
    private static volatile CountDownLatch entered = new CountDownLatch(1);
    private static volatile CountDownLatch release = new CountDownLatch(1);
    private static volatile Map<String, Object> result = Map.of();

    @PostMapping("/reset")
    public String reset() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        result = Map.of();
        return "ready";
    }

    @GetMapping("/status")
    public String status() { return entered.getCount() == 0 ? "entered" : "waiting"; }

    @PostMapping("/release")
    public String release() { release.countDown(); return "released"; }

    @GetMapping("/result")
    public Map<String, Object> result() { return result; }

    static void record(ru.urfu.alphamatcher.demo.DemoOperationState state) {
        var match = state.match("PAY-2026-000187");
        result = Map.of("contract", match == null ? "" : match.contractId(),
                "queued", state.isManualQueued("PAY-2026-000187"),
                "revision", state.revision(), "runs", state.batchRuns().size(),
                "audit", state.auditEventsForOperation("PAY-2026-000187").size(),
                "actor", match == null ? "" : match.actor(),
                "reason", match == null ? "" : match.justification());
    }

    static void awaitRelease() {
        entered.countDown();
        try {
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("QA barrier timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("QA barrier interrupted", error);
        }
    }
}
