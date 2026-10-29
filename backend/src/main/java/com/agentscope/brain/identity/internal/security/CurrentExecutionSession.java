package com.agentscope.brain.identity.internal.security;

import com.agentscope.brain.identity.api.ExecutionSession;
import com.agentscope.brain.identity.internal.application.AuthenticationService;
import com.agentscope.brain.identity.internal.web.AuthController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@ConditionalOnProperty(name = "brain.copilot.enabled", havingValue = "true")
class CurrentExecutionSession implements ExecutionSession {
    private final SessionRepository<? extends Session> sessions;
    private final AuthenticationService authentication;
    CurrentExecutionSession(SessionRepository<? extends Session> sessions, AuthenticationService authentication) {
        this.sessions = sessions; this.authentication = authentication;
    }
    @Override public Lease capture() {
        var principal = AuthController.principal();
        authentication.current(principal);
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        var session = attributes.getRequest().getSession(false);
        if (session == null) throw new IllegalStateException("SESSION_UNAVAILABLE");
        String id = session.getId();
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception unavailable) { throw new IllegalStateException("SESSION_UNAVAILABLE"); }
        return new Lease() {
            @Override public byte[] sessionHash() { return hash.clone(); }
            @Override public boolean valid() {
                try {
                    var stored = sessions.findById(id);
                    if (stored == null || stored.isExpired()) return false;
                    Object value = stored.getAttribute("SPRING_SECURITY_CONTEXT");
                    if (!(value instanceof SecurityContext context) || context.getAuthentication() == null
                            || !principal.equals(context.getAuthentication().getPrincipal())) return false;
                    authentication.current(principal);
                    return true;
                } catch (RuntimeException unavailable) { return false; }
            }
            @Override public String toString() { return "ExecutionSession.Lease[redacted]"; }
        };
    }
}
