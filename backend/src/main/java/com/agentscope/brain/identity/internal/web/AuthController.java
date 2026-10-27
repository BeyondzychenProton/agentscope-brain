package com.agentscope.brain.identity.internal.web;

import com.agentscope.brain.identity.internal.application.AuthenticationService;
import com.agentscope.brain.identity.internal.application.InitialPasswordService;
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
    public static final String PASSWORD_SQL_COMMITTED = AuthController.class.getName() + ".passwordCommitted";
    public static final String PASSWORD_FAILURE = AuthController.class.getName() + ".passwordFailure";
    private final AuthenticationService authentication;
    private final InitialPasswordService passwords;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrf;
    private final java.time.Clock clock;

    public AuthController(AuthenticationService authentication, InitialPasswordService passwords,
            SecurityContextRepository contexts, CsrfTokenRepository csrf, java.time.Clock clock) {
        this.authentication = authentication; this.passwords = passwords; this.contexts = contexts; this.csrf = csrf; this.clock = clock;
    }

    public record LoginRequest(String phone, String password) { }
    public record PasswordRequest(String currentPassword, String newPassword) { }
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

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        csrf.saveToken(null, request, response);
        if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/password/initial")
    public AuthDto initialPassword(@RequestBody PasswordRequest input, HttpServletRequest request, HttpServletResponse response) {
        try {
            var updated = passwords.change(principal(), input.currentPassword(), input.newPassword());
            request.setAttribute(PASSWORD_SQL_COMMITTED, Boolean.TRUE);
            var user = authentication.current(updated);
            request.changeSessionId();
            var session = request.getSession(false);
            session.setMaxInactiveInterval((int) Math.max(1,
                    java.time.Duration.between(clock.instant(), updated.absoluteExpiresAt()).getSeconds()));
            csrf.saveToken(null, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(updated, null, List.of()));
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            return new AuthDto("READY", user.userId(), user.displayName(), updated.authenticatedAt(), updated.absoluteExpiresAt());
        } catch (RuntimeException failure) {
            String code = Boolean.TRUE.equals(request.getAttribute(PASSWORD_SQL_COMMITTED))
                    ? "PASSWORD_CHANGED_RELOGIN_REQUIRED"
                    : failure instanceof AuthFailure auth && auth.getMessage().equals("AUTH_OUTCOME_UNCERTAIN")
                        ? "AUTH_OUTCOME_UNCERTAIN" : null;
            if (code != null) {
                request.setAttribute(PASSWORD_FAILURE, code);
                abandonContext(request);
                throw new AuthFailure(503, code);
            }
            throw failure;
        }
    }

    public static void abandonContext(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        try { if (request.getSession(false) != null) { request.getSession(false).invalidate(); } }
        catch (RuntimeException unavailable) { /* The client cookie is also expired by the outer boundary. */ }
    }

    public static PlatformPrincipal principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof PlatformPrincipal principal)) {
            throw new AuthFailure(401, "AUTHENTICATION_REQUIRED");
        }
        return principal;
    }
    private static String stage(boolean required) { return required ? "PASSWORD_CHANGE_REQUIRED" : "READY"; }
}
