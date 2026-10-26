package com.agentscope.brain.identity.internal.domain;

import java.io.Serializable;
import java.time.Instant;

/** Only server-observed identity and the original authentication deadline enter the JDBC session. */
public record PlatformPrincipal(String userId, long securityVersion, long credentialVersion,
        long phoneRevision, Instant authenticatedAt, Instant absoluteExpiresAt) implements Serializable, java.security.Principal {
    private static final long serialVersionUID = 1L;
    @Override public String getName() { return userId; }
}
