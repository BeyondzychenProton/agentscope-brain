package com.agentscope.brain.identity.internal.security;

import com.agentscope.brain.identity.api.CurrentUser;
import com.agentscope.brain.identity.internal.application.AuthenticationService;
import com.agentscope.brain.identity.internal.web.AuthController;
import org.springframework.stereotype.Service;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
class CurrentPlatformUser implements CurrentUser {
    private final AuthenticationService authentication;
    private final IdentityRepository identities;
    CurrentPlatformUser(AuthenticationService authentication, IdentityRepository identities) { this.authentication = authentication; this.identities=identities; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public User requireLocked() {
        identities.lockedById(AuthController.principal().userId());
        return require();
    }
    @Override public User require() {
        var principal = AuthController.principal();
        var user = authentication.current(principal);
        var phone = user.phone();
        return new User(user.userId(), user.displayName(), "***" + phone.substring(Math.max(0, phone.length() - 4)),
                user.changeRequired(), principal.authenticatedAt(), principal.absoluteExpiresAt());
    }
}
