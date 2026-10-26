package com.agentscope.brain.identity.internal.application;

import com.agentscope.brain.identity.internal.domain.AuthFailure;
import com.agentscope.brain.identity.internal.domain.PlatformPrincipal;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository.Identity;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
    private final IdentityRepository identities;
    private final Clock clock;
    private final PasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    private final String dummyHash = encoder.encode("unmatchable-" + java.util.UUID.randomUUID());

    public AuthenticationService(IdentityRepository identities, Clock clock) { this.identities = identities; this.clock = clock; }

    @Transactional(readOnly = true)
    public PlatformPrincipal login(String phone, String password) {
        if (phone == null || !phone.matches("\\+?[0-9]{6,31}") || !validPasswordInput(password)) {
            encoder.matches("invalid-input", dummyHash);
            throw new AuthFailure(401, "AUTHENTICATION_FAILED");
        }
        var found = identities.byPhone(phone);
        if (found.isEmpty()) {
            encoder.matches(password, dummyHash);
            throw new AuthFailure(401, "AUTHENTICATION_FAILED");
        }
        if (found.size() != 1) { throw new AuthFailure(503, "AUTH_DATA_UNAVAILABLE"); }
        var user = requireComplete(found.get(0));
        if (!encoder.matches(password, user.hash()) || !"ACTIVE".equals(user.status())) {
            throw new AuthFailure(401, "AUTHENTICATION_FAILED");
        }
        var now = clock.instant();
        return new PlatformPrincipal(user.userId(), user.securityVersion(), user.credentialVersion(),
                user.phoneRevision(), now, now.plus(Duration.ofHours(24)));
    }

    @Transactional(readOnly = true)
    public Identity current(PlatformPrincipal principal) {
        if (principal == null || !clock.instant().isBefore(principal.absoluteExpiresAt())) {
            throw new AuthFailure(401, "AUTHENTICATION_REQUIRED");
        }
        var found = identities.byId(principal.userId());
        if (found.size() != 1) { throw new AuthFailure(503, "AUTH_DATA_UNAVAILABLE"); }
        var user = requireComplete(found.get(0));
        if (!"ACTIVE".equals(user.status()) || user.securityVersion() != principal.securityVersion()
                || user.credentialVersion() != principal.credentialVersion() || user.phoneRevision() != principal.phoneRevision()) {
            throw new AuthFailure(401, "AUTHENTICATION_REQUIRED");
        }
        return user;
    }

    public static Identity requireComplete(Identity user) {
        if (user.hash() == null || !user.hash().matches("\\{bcrypt}\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}")
                || user.phone() == null || user.ownershipSource() == null || user.ownershipSource().isBlank()
                || user.securityVersion() < 1 || user.credentialVersion() < 1 || user.phoneRevision() < 1) {
            throw new AuthFailure(503, "AUTH_DATA_UNAVAILABLE");
        }
        return user;
    }

    public static boolean validPasswordInput(String value) {
        return value != null && !value.isEmpty() && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
