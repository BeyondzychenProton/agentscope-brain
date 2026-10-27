package com.agentscope.brain.projection.internal.web;

import com.agentscope.brain.projection.internal.application.TextHistory;
import com.agentscope.brain.projection.internal.application.ProjectionReadFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
                "retryable", failure.status() == 503, "supportedVersions", java.util.List.of(TextHistory.PROTOCOL)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() { return failure(new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE")); }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return failure(new ProjectionReadFailure(400, "REQUEST_INVALID")); }
}
