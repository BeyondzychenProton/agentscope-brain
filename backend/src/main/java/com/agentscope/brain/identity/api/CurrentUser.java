package com.agentscope.brain.identity.api;

import java.time.Instant;

/** Revalidates the trusted HTTP identity against current platform facts on every call. */
public interface CurrentUser {
    User require();
    /** Caller owns a write transaction; takes L1 before revalidating the trusted authentication. */
    User requireLocked();
    record User(String userId, String displayName, String maskedPhone, boolean passwordChangeRequired,
            Instant authenticatedAt, Instant absoluteExpiresAt) { }
}
