package com.agentscope.brain.conversation.internal.web;

import com.agentscope.brain.conversation.internal.application.SessionDirectory;
import com.agentscope.brain.conversation.internal.application.SessionReadFailure;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions")
class SessionDirectoryController {
    private final SessionDirectory directory;
    SessionDirectoryController(SessionDirectory directory) { this.directory = directory; }
    @GetMapping
    Object list(@RequestParam String employeeId, @RequestParam(required=false) String q,
            @RequestParam(required=false) String pageCursor, @RequestParam(defaultValue="50") int limit) {
        return directory.list(employeeId, q, pageCursor, limit);
    }
    @ExceptionHandler(SessionReadFailure.class)
    ResponseEntity<?> failure(SessionReadFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() { return failure(new SessionReadFailure(503, "SESSION_READ_UNAVAILABLE")); }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return failure(new SessionReadFailure(400, "REQUEST_INVALID")); }
}
