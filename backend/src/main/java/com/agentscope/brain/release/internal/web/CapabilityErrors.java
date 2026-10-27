package com.agentscope.brain.release.internal.web;

import com.agentscope.brain.release.api.EmployeeAccess;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class CapabilityErrors {
    @ExceptionHandler(EmployeeAccess.Denied.class)
    ResponseEntity<?> denied(EmployeeAccess.Denied failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.getMessage(),
                "message", failure.getMessage(), "retryable", failure.status() == 503));
    }
}
