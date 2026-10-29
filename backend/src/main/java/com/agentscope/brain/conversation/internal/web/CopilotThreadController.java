package com.agentscope.brain.conversation.internal.web;

import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.conversation.api.CopilotThreadFailure;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The ordinary session endpoints remain separate; all thread reads use the current platform session. */
@RestController
@RequestMapping("/api/copilot/threads")
@ConditionalOnProperty(name = "brain.copilot.enabled", havingValue = "true")
class CopilotThreadController {
    private final CopilotThreads threads;
    CopilotThreadController(CopilotThreads threads) { this.threads = threads; }

    @GetMapping
    Object list(@RequestParam String employeeId, @RequestParam(required = false) String q,
                @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 99 || offset < 0 || offset > Integer.MAX_VALUE - limit)
            throw new CopilotThreadFailure(400, "REQUEST_INVALID");
        var found = threads.list(employeeId, q, limit + 1, offset);
        boolean more = found.size() > limit;
        return Map.of("items", more ? found.subList(0, limit) : found,
                "hasMore", more, "nextOffset", more ? offset + limit : -1);
    }

    @GetMapping("/{threadId}")
    Object open(@PathVariable String threadId) { return threads.open(threadId); }

    record Rename(String expectedVersion, String title) { }
    @PatchMapping("/{threadId}/title")
    ResponseEntity<Void> rename(@PathVariable String threadId, @RequestBody Rename body) {
        threads.rename(threadId, body.expectedVersion(), body.title());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(CopilotThreadFailure.class)
    ResponseEntity<?> failure(CopilotThreadFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.getMessage()));
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("code", "THREAD_UNAVAILABLE")); }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("code", "REQUEST_INVALID")); }
}
