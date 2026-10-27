package com.agentscope.brain.conversation.internal.web;

import com.agentscope.brain.conversation.internal.application.TextSubmission;
import com.agentscope.brain.conversation.internal.application.SessionReadFailure;
import com.agentscope.brain.release.api.EmployeeAccess;
import com.agentscope.brain.runtime.api.OrdinaryDispatch;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class TextSubmissionController {
    private final TextSubmission submissions;
    TextSubmissionController(TextSubmission submissions) { this.submissions=submissions; }
    @PostMapping("/api/session-intents") Object prepare(@RequestBody Map<String,Object> body) {
        keys(body,Set.of("requestKey","clientIntentId","employeeId"),Set.of());
        return submissions.prepare(new TextSubmission.Prepare(string(body,"requestKey",false),string(body,"clientIntentId",false),string(body,"employeeId",false)));
    }
    @GetMapping("/api/session-intents/{intentId}") Object intent(@PathVariable String intentId) { return submissions.intent(intentId); }
    @PostMapping("/api/submissions") ResponseEntity<?> submit(@RequestBody Map<String,Object> body) {
        keys(body,Set.of("submissionKey","target","text","uploadIds"),Set.of());
        if(!(body.get("target") instanceof Map<?,?> target) || target.size()!=1 ||
                !Set.of("sessionId","intentId").containsAll(target.keySet())) { throw new SessionReadFailure(400,"TARGET_INVALID"); }
        var parsedTarget=new TextSubmission.Target(string(target,"intentId",true),string(target,"sessionId",true));
        if(!(body.get("uploadIds") instanceof List<?> uploads) || uploads.stream().anyMatch(x -> !(x instanceof String))) {
            throw new SessionReadFailure(400,"UPLOAD_SET_INVALID");
        }
        var input=new TextSubmission.Payload(string(body,"submissionKey",false),parsedTarget,string(body,"text",false),uploads.stream().map(String.class::cast).toList());
        return ResponseEntity.accepted().body(submissions.submit(input));
    }
    @GetMapping("/api/submissions/{submissionKey}") Object lookup(@PathVariable String submissionKey) { return submissions.lookup(submissionKey); }
    @PostMapping("/api/submissions/{submissionKey}/close") Object close(@PathVariable String submissionKey,@RequestBody Map<String,Object> body) {
        keys(body,Set.of("requestKey"),Set.of("expectedSubmissionVersion"));
        return submissions.close(submissionKey,new TextSubmission.Close(string(body,"requestKey",false),string(body,"expectedSubmissionVersion",true)));
    }
    private static String string(Map<?,?> body,String key,boolean optional) {
        Object value=body.get(key);
        if(optional && value==null) return null;
        if(!(value instanceof String text)) throw new SessionReadFailure(400,"REQUEST_INVALID");
        return text;
    }
    private static void keys(Map<String,Object> body,Set<String> required,Set<String> optional) {
        if(body==null || !body.keySet().containsAll(required) || body.keySet().stream().anyMatch(k -> !required.contains(k) && !optional.contains(k))) {
            throw new SessionReadFailure(400,"REQUEST_INVALID");
        }
    }
    @ExceptionHandler(SessionReadFailure.class) ResponseEntity<?> failure(SessionReadFailure failure) {
        if(failure instanceof TextSubmission.IntentAlreadyAccepted accepted) {
            return ResponseEntity.status(409).body(Map.of("code",failure.getMessage(),"result",accepted.result()));
        }
        return error(failure.status(),failure.getMessage());
    }
    @ExceptionHandler(EmployeeAccess.Denied.class) ResponseEntity<?> release(EmployeeAccess.Denied failure) { return error(failure.status(),failure.getMessage()); }
    @ExceptionHandler(OrdinaryDispatch.Denied.class) ResponseEntity<?> queue(OrdinaryDispatch.Denied failure) { return error(failure.status(),failure.getMessage()); }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class,IllegalStateException.class,IllegalArgumentException.class})
    ResponseEntity<?> uncertain() { return error(503,"SUBMISSION_OUTCOME_UNCERTAIN"); }
    private ResponseEntity<?> error(int status,String code) { return ResponseEntity.status(status).body(Map.of("code",code)); }
}