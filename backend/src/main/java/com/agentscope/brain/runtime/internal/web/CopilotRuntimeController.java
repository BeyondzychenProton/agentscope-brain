package com.agentscope.brain.runtime.internal.web;

import com.agentscope.brain.conversation.api.CopilotThreadFailure;
import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.identity.api.ExecutionSession;
import com.agentscope.brain.release.api.EmployeeAccess;
import com.agentscope.brain.runtime.internal.application.CopilotTextRuns;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Runtime-only channel: the shared server secret is separate from the user's session and CSRF. */
@RestController
@RequestMapping("/api/copilot/runtime")
@ConditionalOnProperty(name = "brain.copilot.enabled", havingValue = "true")
class CopilotRuntimeController {
    private final CopilotTextRuns runs;
    private final ExecutionSession sessions;
    private final String secret;
    CopilotRuntimeController(CopilotTextRuns runs, ExecutionSession sessions,
            @Value("${brain.copilot.runtime-secret:}") String secret) {
        this.runs = runs; this.sessions = sessions; this.secret = secret;
    }
    record Start(String threadId, String runId, String employeeId, String text, List<ResumeInput> resume) { }
    record ResumeInput(String interruptId, String status, Map<String, Object> payload) { }
    record Stop(String threadId, String runId) { }

    @PostMapping("/csrf-check")
    ResponseEntity<Void> csrfCheck(@RequestHeader(value = "X-Brain-Runtime", required = false) String key) {
        requireRuntime(key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> run(@RequestHeader(value = "X-Brain-Runtime", required = false) String key,
            @RequestBody Start input) {
        requireRuntime(key);
        var viewer = sessions.capture();
        var answers = input.resume() == null ? List.<CopilotThreads.Resume>of()
                : input.resume().stream()
                        .map(r -> new CopilotThreads.Resume(r.interruptId(), r.status(), r.payload()))
                        .toList();
        return encode(runs.start(input.threadId(), input.runId(), input.employeeId(), input.text(), answers), viewer);
    }
    @GetMapping(value = "/connect/{threadId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> connect(@RequestHeader(value = "X-Brain-Runtime", required = false) String key,
            @PathVariable String threadId) {
        requireRuntime(key);
        var viewer = sessions.capture();
        return encode(runs.connect(threadId), viewer);
    }
    @PostMapping("/stop")
    Map<String, Boolean> stop(@RequestHeader(value = "X-Brain-Runtime", required = false) String key, @RequestBody Stop input) {
        requireRuntime(key);
        return Map.of("stopped", runs.stop(input.threadId(), input.runId()));
    }
    private void requireRuntime(String key) {
        if (secret.length() < 32 || key == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)))
            throw new CopilotThreadFailure(403, "RUNTIME_FORBIDDEN");
    }
    private Flux<ServerSentEvent<String>> encode(Flux<AguiEvent> events, ExecutionSession.Lease viewer) {
        var expired = Flux.interval(Duration.ofSeconds(2)).publishOn(Schedulers.boundedElastic())
                .filter(tick -> !viewer.valid());
        return events.publishOn(Schedulers.boundedElastic()).<ServerSentEvent<String>>handle((event, sink) -> {
            if (!viewer.valid()) { sink.complete(); return; }
            sink.next(ServerSentEvent.builder(JsonUtils.getJsonCodec().toJson(event)).build());
        }).takeUntilOther(expired);
    }
    @ExceptionHandler(CopilotThreadFailure.class)
    ResponseEntity<?> failure(CopilotThreadFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.getMessage()));
    }
    @ExceptionHandler(EmployeeAccess.Denied.class)
    ResponseEntity<?> denied(EmployeeAccess.Denied failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.getMessage()));
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("code", "THREAD_UNAVAILABLE")); }
}

