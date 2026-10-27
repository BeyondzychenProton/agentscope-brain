package com.agentscope.brain.projection.internal.web;

import com.agentscope.brain.projection.internal.application.ProcessHistory;
import com.agentscope.brain.projection.internal.application.ProjectionReadFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions/{sessionId}/projection/process")
class ProcessHistoryController {
    private final ProcessHistory history;
    private final ObjectMapper json = new ObjectMapper();

    ProcessHistoryController(ProcessHistory history) {
        this.history = history;
    }

    @GetMapping("/snapshot")
    Object snapshot(
            @PathVariable String sessionId,
            @RequestParam String protocolVersion,
            @RequestParam(defaultValue = "50") int processLimit) {
        return bounded(history.snapshot(sessionId, protocolVersion, processLimit));
    }

    @GetMapping("/after")
    Object after(
            @PathVariable String sessionId,
            @RequestParam String protocolVersion,
            @RequestParam String cursor) {
        return bounded(history.after(sessionId, protocolVersion, cursor));
    }

    @GetMapping("/steps/{stepId}/detail")
    Object detail(
            @PathVariable String sessionId,
            @PathVariable String stepId,
            @RequestParam String protocolVersion,
            @RequestParam String detailVersion) {
        return bounded(history.detail(sessionId, protocolVersion, stepId, detailVersion));
    }

    private Object bounded(Object response) {
        try {
            if (json.writeValueAsBytes(response).length > 2097152) {
                throw new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE");
            }
            return response;
        } catch (ProjectionReadFailure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE");
        }
    }

    @ExceptionHandler(ProjectionReadFailure.class)
    ResponseEntity<?> failure(ProjectionReadFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of(
                "code", failure.getMessage(),
                "message", failure.getMessage(),
                "retryable", failure.status() == 503,
                "supportedVersions", java.util.List.of(ProcessHistory.PROTOCOL)));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() {
        return failure(new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE"));
    }

    @ExceptionHandler({
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<?> invalid() {
        return failure(new ProjectionReadFailure(400, "REQUEST_INVALID"));
    }
}
