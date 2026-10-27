package com.agentscope.brain.identity.internal.application;

import com.agentscope.brain.identity.internal.domain.AuthFailure;
import com.agentscope.brain.identity.internal.domain.PlatformPrincipal;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository.Identity;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Platform SQL commits before Session JDBC. A failed commit is reconciled once, never replayed. */
@Service
public class InitialPasswordService {
    private final AuthenticationService authentication;
    private final IdentityRepository identities;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public InitialPasswordService(AuthenticationService authentication, IdentityRepository identities,
            Clock clock, PlatformTransactionManager manager) {
        this.authentication = authentication; this.identities = identities; this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(10);
    }

    public PlatformPrincipal change(PlatformPrincipal principal, String currentPassword, String newPassword) {
        var before = authentication.current(principal);
        if (!before.changeRequired()) { throw new AuthFailure(409, "INITIAL_PASSWORD_CHANGE_NOT_REQUIRED"); }
        if (!AuthenticationService.validPasswordInput(currentPassword)
                || !authentication.passwordMatches(currentPassword, before.hash())) {
            throw new AuthFailure(422, "CURRENT_PASSWORD_INVALID");
        }
        if (!AuthenticationService.validPasswordInput(newPassword)
                || newPassword.codePointCount(0, newPassword.length()) < 12) {
            throw new AuthFailure(422, "NEW_PASSWORD_INVALID");
        }
        // bcrypt is deliberately outside the short write transaction.
        var encoded = authentication.encodePassword(newPassword);
        var requestId = UUID.randomUUID().toString();
        try {
            transactions.executeWithoutResult(status -> {
                var locked = identities.lockedById(principal.userId());
                if (locked.size() != 1 || !locked.get(0).equals(before)
                        || !clock.instant().isBefore(principal.absoluteExpiresAt())) {
                    throw new AuthFailure(401, "AUTHENTICATION_REQUIRED");
                }
                identities.changeInitialPassword(before, encoded, requestId, clock.instant());
            });
        } catch (AuthFailure rejected) {
            throw rejected;
        } catch (RuntimeException uncertain) {
            // New transaction and locked read wait out the original writer; neither cached facts
            // nor merely matching the new password can prove this request's commit.
            boolean committed = false;
            try {
                committed = Boolean.TRUE.equals(transactions.execute(status -> {
                    var actual = identities.lockedById(before.userId());
                    return actual.size() == 1 && isCommitted(before, actual.get(0), encoded)
                            && identities.hasPasswordAudit(before.userId(), requestId);
                }));
            } catch (RuntimeException unavailable) { /* Preserve uncertainty, without logging credentials/SQL. */ }
            if (!committed) { throw new AuthFailure(503, "AUTH_OUTCOME_UNCERTAIN"); }
        }
        return new PlatformPrincipal(before.userId(), before.securityVersion() + 1, before.credentialVersion() + 1,
                before.phoneRevision(), principal.authenticatedAt(), principal.absoluteExpiresAt());
    }

    private static boolean isCommitted(Identity before, Identity actual, String encoded) {
        return actual.hash().equals(encoded) && !actual.changeRequired()
                && actual.securityVersion() == before.securityVersion() + 1
                && actual.credentialVersion() == before.credentialVersion() + 1
                && actual.phoneRevision() == before.phoneRevision() && "ACTIVE".equals(actual.status());
    }
}
