package com.agentscope.brain.identity.internal.web;

import com.agentscope.brain.identity.internal.application.AuthenticationService;
import com.agentscope.brain.identity.internal.domain.AuthFailure;
import com.agentscope.brain.identity.internal.domain.PlatformPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final AuthenticationService authentication;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrf;

    public AuthController(AuthenticationService authentication, SecurityContextRepository contexts, CsrfTokenRepository csrf) {
        this.authentication = authentication; this.contexts = contexts; this.csrf = csrf;
    }

    public record LoginRequest(String phone, String password) { }
    public record AuthDto(String authStage, String userId, String displayName, Instant authenticatedAt, Instant absoluteExpiresAt) { }
    public record MeDto(String authStage, String userId, String displayName, Instant authenticatedAt,
            Instant absoluteExpiresAt, String maskedPhone, List<Object> employees) { }

    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("headerName", token.getHeaderName(), "token", token.getToken()); }

    @PostMapping("/api/auth/login")
    public AuthDto login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        var principal = authentication.login(input.phone(), input.password());
        var user = authentication.current(principal);
        // Rotate the anonymous CSRF session before persisting the trusted context. No password enters the session.
        if (request.getSession(false) != null) { request.changeSessionId(); }
        var session = request.getSession(true);
        session.setMaxInactiveInterval(86400);
        csrf.saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        try { contexts.saveContext(context, request, response); }
        catch (RuntimeException failure) {
            SecurityContextHolder.clearContext();
            try { session.invalidate(); } catch (RuntimeException ignored) { /* DB remains unavailable; no success is returned. */ }
            throw failure;
        }
        return new AuthDto(stage(user.changeRequired()), user.userId(), user.displayName(), principal.authenticatedAt(), principal.absoluteExpiresAt());
    }

    @GetMapping("/api/me")
    public MeDto me() {
        var principal = principal();
        var user = authentication.current(principal);
        var phone = user.phone();
        return new MeDto(stage(user.changeRequired()), user.userId(), user.displayName(), principal.authenticatedAt(),
                principal.absoluteExpiresAt(), "***" + phone.substring(Math.max(0, phone.length() - 4)), List.of());
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        csrf.saveToken(null, request, response);
        if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/password/initial")
    public void initialPassword() { throw new AuthFailure(501, "INITIAL_PASSWORD_CHANGE_NOT_IMPLEMENTED"); }

    public static PlatformPrincipal principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof PlatformPrincipal principal)) {
            throw new AuthFailure(401, "AUTHENTICATION_REQUIRED");
        }
        return principal;
    }
    private static String stage(boolean required) { return required ? "PASSWORD_CHANGE_REQUIRED" : "READY"; }
}
