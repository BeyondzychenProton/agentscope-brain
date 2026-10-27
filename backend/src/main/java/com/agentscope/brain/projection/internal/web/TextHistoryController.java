package com.agentscope.brain.projection.internal.web;

import com.agentscope.brain.projection.internal.application.TextHistory;
import com.agentscope.brain.projection.internal.application.ProjectionReadFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/sessions/{sessionId}/projection")
class TextHistoryController {
    private final TextHistory history;
    private final ObjectMapper json = new ObjectMapper();
    TextHistoryController(TextHistory history) { this.history = history; }

    @GetMapping("/snapshot")
    Object snapshot(@PathVariable String sessionId, @RequestParam String protocolVersion,
            @RequestParam(defaultValue="50") int messageLimit, @RequestParam(defaultValue="20") int processLimit) {
        return bounded(history.snapshot(sessionId, protocolVersion, messageLimit, processLimit));
    }

    @GetMapping("/messages")
    Object messages(@PathVariable String sessionId, @RequestParam String protocolVersion, @RequestParam String pageCursor,
            @RequestParam(defaultValue="50") int limit) { return bounded(history.messages(sessionId, protocolVersion, pageCursor, limit)); }

    @GetMapping("/content/{contentId}")
    Object content(@PathVariable String sessionId, @PathVariable String contentId, @RequestParam String protocolVersion,
            @RequestParam String contentVersion, @RequestParam(required=false) String partCursor) {
        if (partCursor != null) { throw new ProjectionReadFailure(409, "CURSOR_INVALID"); }
        return bounded(history.content(sessionId, protocolVersion, contentId, contentVersion));
    }

    @GetMapping("/events")
    Object events(
            @PathVariable String sessionId,
            @RequestParam String protocolVersion,
            @RequestParam String after,
            @RequestParam(defaultValue = "100") int limit) {
        return bounded(history.events(sessionId, protocolVersion, after, limit));
    }

    /**
     * Read-only SSE of committed {@code web-projection-v1} batches.
     * Explicit {@code after} is the only application cursor; {@code Last-Event-ID} is ignored.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            @PathVariable String sessionId,
            @RequestParam String protocolVersion,
            @RequestParam String after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // lastEventId intentionally unused — must not override application cursor.
        SseEmitter emitter = new SseEmitter(0L);
        String cursor = after;
        AtomicLong lastAuthCheck = new AtomicLong(System.currentTimeMillis());
        Thread worker = new Thread(() -> {
            try {
                String applied = cursor;
                while (!Thread.currentThread().isInterrupted()) {
                    long now = System.currentTimeMillis();
                    if (now - lastAuthCheck.get() >= 15_000L) {
                        // Re-enter ownership via events() which calls owned()/CurrentUser.
                        history.events(sessionId, protocolVersion, applied, 1);
                        lastAuthCheck.set(now);
                    }
                    var page = history.events(sessionId, protocolVersion, applied, 50);
                    if (!page.batches().isEmpty()) {
                        for (Map<String, Object> batch : page.batches()) {
                            String id = String.valueOf(batch.get("streamId")) + ":" + String.valueOf(batch.get("seq"));
                            emitter.send(SseEmitter.event().name("projection.batch").id(id).data(batch));
                        }
                        applied = page.lastCursor();
                        lastAuthCheck.set(System.currentTimeMillis());
                    } else {
                        emitter.send(SseEmitter.event().name("caught_up").data(Map.of(
                                "headSeq", page.headSeq(),
                                "cursor", page.lastCursor())));
                        Thread.sleep(1000L);
                    }
                }
            } catch (ProjectionReadFailure failure) {
                try {
                    if (failure.status() == 401 || "PASSWORD_CHANGE_REQUIRED".equals(failure.getMessage())
                            || failure.status() == 403) {
                        emitter.send(SseEmitter.event().name("auth_required").data(Map.of("code", failure.getMessage())));
                    } else if (failure.status() == 410 || "RESET_REQUIRED".equals(failure.getMessage())) {
                        emitter.send(SseEmitter.event().name("reset_required").data(Map.of("code", failure.getMessage())));
                    }
                } catch (IOException ignored) {
                    // emitter already dead
                }
                emitter.completeWithError(failure);
            } catch (Exception failure) {
                emitter.completeWithError(failure);
            }
        }, "projection-sse-" + sessionId);
        worker.setDaemon(true);
        worker.start();
        emitter.onCompletion(worker::interrupt);
        emitter.onTimeout(worker::interrupt);
        emitter.onError(error -> worker.interrupt());
        return emitter;
    }

    private Object bounded(Object response) {
        try {
            if (json.writeValueAsBytes(response).length > 2097152) { throw new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE"); }
            return response;
        } catch (ProjectionReadFailure failure) { throw failure; }
        catch (Exception invalid) { throw new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE"); }
    }
    @ExceptionHandler(ProjectionReadFailure.class)
    ResponseEntity<?> failure(ProjectionReadFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.getMessage(), "message", failure.getMessage(),
                "retryable", failure.status() == 503, "supportedVersions", List.of(TextHistory.PROTOCOL)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() { return failure(new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE")); }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return failure(new ProjectionReadFailure(400, "REQUEST_INVALID")); }
}
