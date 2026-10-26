package com.agentscope.brain.identity.internal.application;

import com.agentscope.brain.identity.internal.domain.AuthFailure;
import com.agentscope.brain.identity.internal.domain.PlatformPrincipal;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository.Identity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationServiceTest {
    private final IdentityRepository identities = mock(IdentityRepository.class);
    private final Instant now = Instant.parse("2026-10-26T10:00:00Z");
    private final AuthenticationService service = new AuthenticationService(identities, Clock.fixed(now, ZoneOffset.UTC));
    private Identity user(String hash, String status) {
        return new Identity("9007199254740993", "测试用户", status, 3, 4, 5, hash, true, "19900000034", "synthetic-roster");
    }
    private String hash() { return "{bcrypt}" + new BCryptPasswordEncoder(12).encode("synthetic-password-34"); }

    @Test void unknownPhoneAndWrongPasswordReturnIdenticalControlledFailure() {
        when(identities.byPhone("19900000000")).thenReturn(List.of());
        when(identities.byPhone("19900000034")).thenReturn(List.of(user(hash(), "ACTIVE")));
        for (var phone : List.of("19900000000", "19900000034")) {
            assertThatThrownBy(() -> service.login(phone, "incorrect-password"))
                    .isInstanceOfSatisfying(AuthFailure.class, e -> {
                        assertThat(e.status()).isEqualTo(401); assertThat(e.getMessage()).isEqualTo("AUTHENTICATION_FAILED");
                    });
        }
    }
    @Test void loginCapturesStableUserIdAndOriginalDeadline() {
        when(identities.byPhone("19900000034")).thenReturn(List.of(user(hash(), "ACTIVE")));
        var principal = service.login("19900000034", "synthetic-password-34");
        assertThat(principal.userId()).isEqualTo("9007199254740993");
        assertThat(principal.absoluteExpiresAt()).isEqualTo(now.plusSeconds(86400));
    }
    @Test void missingCredentialIsUnavailableAndCannotBecomeAPasswordError() {
        when(identities.byPhone("19900000034")).thenReturn(List.of(user(null, "ACTIVE")));
        assertThatThrownBy(() -> service.login("19900000034", "synthetic-password-34"))
                .isInstanceOfSatisfying(AuthFailure.class, e -> assertThat(e.status()).isEqualTo(503));
    }
    @Test void expiryAndStaleVersionsRejectExistingContexts() {
        var hash = hash();
        when(identities.byId("9007199254740993")).thenReturn(List.of(user(hash, "ACTIVE")));
        for (var principal : List.of(
                new PlatformPrincipal("9007199254740993", 3, 4, 5, now.minusSeconds(86400), now),
                new PlatformPrincipal("9007199254740993", 2, 4, 5, now, now.plusSeconds(86400)))) {
            assertThatThrownBy(() -> service.current(principal)).isInstanceOfSatisfying(AuthFailure.class,
                    e -> assertThat(e.status()).isEqualTo(401));
        }
    }
    @Test void utf8LimitAndMalformedSurrogatesAreRejectedWithoutTruncatingPasswords() {
        assertThat(AuthenticationService.validPasswordInput("中".repeat(24))).isTrue();
        assertThat(AuthenticationService.validPasswordInput("中".repeat(25))).isFalse();
        assertThat(AuthenticationService.validPasswordInput("\uD800")).isFalse();
    }
}
