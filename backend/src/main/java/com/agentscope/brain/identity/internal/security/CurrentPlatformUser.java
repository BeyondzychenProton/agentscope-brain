package com.agentscope.brain.identity.internal.security;

import com.agentscope.brain.identity.api.CurrentUser;
import com.agentscope.brain.identity.internal.application.AuthenticationService;
import com.agentscope.brain.identity.internal.web.AuthController;
import org.springframework.stereotype.Service;

@Service
class CurrentPlatformUser implements CurrentUser {
    private final AuthenticationService authentication;
    CurrentPlatformUser(AuthenticationService authentication) { this.authentication = authentication; }
    @Override public User require() {
        var principal = AuthController.principal();
        var user = authentication.current(principal);
        var phone = user.phone();
        return new User(user.userId(), user.displayName(), "***" + phone.substring(Math.max(0, phone.length() - 4)),
                user.changeRequired(), principal.authenticatedAt(), principal.absoluteExpiresAt());
    }
}
