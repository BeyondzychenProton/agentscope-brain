package com.agentscope.brain.identity.internal.web;

import com.agentscope.brain.identity.internal.domain.AuthFailure;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<?> failure(AuthFailure failure) { return ResponseEntity.status(failure.status()).body(Map.of("code", failure.getMessage())); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("code", "AUTH_DATA_UNAVAILABLE")); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidInput() { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST")); }
}
