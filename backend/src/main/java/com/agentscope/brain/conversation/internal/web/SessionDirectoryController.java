package com.agentscope.brain.conversation.internal.web;

import com.agentscope.brain.conversation.internal.application.SessionDirectory;
import com.agentscope.brain.conversation.internal.application.SessionReadFailure;
import com.agentscope.brain.conversation.internal.application.SessionTitleRename;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions")
class SessionDirectoryController {
    private final SessionDirectory directory;
    private final SessionTitleRename titles;
    SessionDirectoryController(SessionDirectory directory, SessionTitleRename titles) {
        this.directory = directory;
        this.titles = titles;
    }
    @GetMapping
    Object list(@RequestParam String employeeId, @RequestParam(required=false) String q,
            @RequestParam(required=false) String pageCursor, @RequestParam(defaultValue="50") int limit) {
        return directory.list(employeeId, q, pageCursor, limit);
    }
    @PatchMapping("/{sessionId}/title")
    Object rename(@PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        keys(body, Set.of("requestKey", "expectedSessionVersion", "title"));
        return titles.rename(sessionId, new SessionTitleRename.Input(
                string(body, "requestKey"), string(body, "expectedSessionVersion"), string(body, "title")));
    }
    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text)) throw new SessionReadFailure(400, "REQUEST_INVALID");
        return text;
    }
    private static void keys(Map<String, Object> body, Set<String> required) {
        if (body == null || !body.keySet().equals(required)) {
            throw new SessionReadFailure(400, "REQUEST_INVALID");
        }
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
