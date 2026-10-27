package com.agentscope.brain.identity.internal.application;

import com.agentscope.brain.identity.internal.domain.AuthFailure;
import com.agentscope.brain.identity.internal.domain.PlatformPrincipal;
import com.agentscope.brain.identity.internal.persistence.IdentityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import static org.assertj.core.api.Assertions.*;

/** Real local transactions, with a lost commit acknowledgement at the transaction-manager boundary. */
class InitialPasswordServiceTest {
    private final Instant now = Instant.parse("2026-10-27T02:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final PlatformPrincipal original = new PlatformPrincipal("1", 1, 1, 1, now.minusSeconds(3600), now.plusSeconds(82800));
    private JdbcTemplate jdbc;
    private IdentityRepository identities;
    private AuthenticationService authentication;
    private PlatformTransactionManager manager;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        identities = new IdentityRepository(jdbc);
        authentication = new AuthenticationService(identities, clock);
        jdbc.execute("CREATE TABLE platform_user(id BIGINT PRIMARY KEY,display_name VARCHAR(100),account_status VARCHAR(8),security_version BIGINT,phone_revision BIGINT,modified_by_user_id BIGINT,modification_source_kind VARCHAR(32),modification_bootstrap_batch_id BIGINT,gmt_modified TIMESTAMP)");
        jdbc.execute("CREATE TABLE user_credential(user_id BIGINT PRIMARY KEY,credential_version BIGINT,password_hash VARCHAR(255),is_password_change_required INT,password_changed_at TIMESTAMP,gmt_modified TIMESTAMP)");
        jdbc.execute("CREATE TABLE phone_reservation(owner_user_id BIGINT,phone_normalized VARCHAR(32),ownership_source_ref VARCHAR(128),reservation_status VARCHAR(16))");
        jdbc.execute("CREATE TABLE identity_audit_event(actor_user_id BIGINT,target_user_id BIGINT,action_code VARCHAR(64),outcome VARCHAR(7),request_id VARCHAR(128),occurred_at TIMESTAMP,gmt_create TIMESTAMP,gmt_modified TIMESTAMP)");
        jdbc.update("INSERT INTO platform_user(id,display_name,account_status,security_version,phone_revision) VALUES(1,'synthetic','ACTIVE',1,1)");
        jdbc.update("INSERT INTO user_credential(user_id,credential_version,password_hash,is_password_change_required) VALUES(1,1,?,1)", authentication.encodePassword("synthetic-current-password"));
        jdbc.update("INSERT INTO phone_reservation VALUES(1,'19900000035','synthetic-roster','CURRENT')");
    }

    private InitialPasswordService service(PlatformTransactionManager tx) { return new InitialPasswordService(authentication, identities, clock, tx); }
    private void changeRejected(String current, String next, String expected) {
        assertThatThrownBy(() -> service(manager).change(original, current, next)).isInstanceOfSatisfying(AuthFailure.class,
                error -> assertThat(error.getMessage()).isEqualTo(expected));
        assertThat(identities.byId("1").get(0).securityVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit_event", Integer.class)).isZero();
    }

    @Test void successCommitsCredentialSecurityAndAuditAndPreservesOriginalDeadline() {
        var updated = service(manager).change(original, "synthetic-current-password", "中".repeat(24));
        assertThat(updated.authenticatedAt()).isEqualTo(original.authenticatedAt());
        assertThat(updated.absoluteExpiresAt()).isEqualTo(original.absoluteExpiresAt());
        assertThat(updated.securityVersion()).isEqualTo(2);
        assertThat(updated.credentialVersion()).isEqualTo(2);
        var actual = authentication.current(updated);
        assertThat(actual.changeRequired()).isFalse();
        assertThat(actual.hash()).startsWith("{bcrypt}$2a$12$");
        assertThat(authentication.passwordMatches("中".repeat(24), actual.hash())).isTrue();
        assertThatThrownBy(() -> authentication.current(original)).isInstanceOf(AuthFailure.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit_event WHERE action_code='INITIAL_PASSWORD_CHANGE' AND outcome='SUCCESS'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> service(manager).change(updated, "中".repeat(24), "another-password"))
                .isInstanceOfSatisfying(AuthFailure.class, e -> assertThat(e.getMessage()).isEqualTo("INITIAL_PASSWORD_CHANGE_NOT_REQUIRED"));
    }

    @Test void invalidCurrentPasswordAndUnicodeBoundariesCannotMutateFacts() {
        changeRejected("wrong-password", "new-valid-password", "CURRENT_PASSWORD_INVALID");
        for (var next : new String[]{"a".repeat(11), "a".repeat(73), "中".repeat(25), "😀".repeat(11), "\uD800".repeat(12)}) {
            changeRejected("synthetic-current-password", next, "NEW_PASSWORD_INVALID");
        }
        assertThat(AuthenticationService.validPasswordInput("a".repeat(72))).isTrue();
        assertThat(AuthenticationService.validPasswordInput("😀".repeat(18))).isTrue();
    }

    @Test void codePointMinimumCountsSupplementaryCharactersOnce() {
        var updated = service(manager).change(original, "synthetic-current-password", "😀".repeat(12));
        assertThat(authentication.current(updated).changeRequired()).isFalse();
    }

    @Test void auditFailureRollsBackBothVersionsAndCannotBeReportedAsUnchanged() {
        jdbc.execute("DROP TABLE identity_audit_event");
        assertThatThrownBy(() -> service(manager).change(original, "synthetic-current-password", "new-valid-password"))
                .isInstanceOfSatisfying(AuthFailure.class, e -> assertThat(e.getMessage()).isEqualTo("AUTH_OUTCOME_UNCERTAIN"));
        assertThat(identities.byId("1").get(0).securityVersion()).isEqualTo(1);
        assertThat(identities.byId("1").get(0).credentialVersion()).isEqualTo(1);
        assertThat(identities.byId("1").get(0).changeRequired()).isTrue();
    }

    private PlatformTransactionManager lostAcknowledgement(boolean commit) {
        return new PlatformTransactionManager() {
            private boolean first = true;
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            @Override public void rollback(TransactionStatus status) { manager.rollback(status); }
            @Override public void commit(TransactionStatus status) {
                if (!first) { manager.commit(status); return; }
                first = false;
                if (commit) { manager.commit(status); } else { manager.rollback(status); }
                throw new TransactionSystemException("synthetic lost commit acknowledgement");
            }
        };
    }

    @Test void committedButAcknowledgementLostIsProvenByExactHashVersionsAndThisRequestsAudit() {
        var updated = service(lostAcknowledgement(true)).change(original, "synthetic-current-password", "new-valid-password");
        assertThat(authentication.current(updated).securityVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit_event", Integer.class)).isEqualTo(1);
    }

    @Test void commitNotProvenReturnsUncertaintyAndNeverReplaysTheWrite() {
        assertThatThrownBy(() -> service(lostAcknowledgement(false)).change(original, "synthetic-current-password", "new-valid-password"))
                .isInstanceOfSatisfying(AuthFailure.class, e -> assertThat(e.getMessage()).isEqualTo("AUTH_OUTCOME_UNCERTAIN"));
        assertThat(identities.byId("1").get(0).credentialVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit_event", Integer.class)).isZero();
    }

    @Test void committedCredentialWithoutReadableProofRemainsUncertainAndOldContextStaysInvalid() {
        var lost = lostAcknowledgement(true);
        PlatformTransactionManager unavailableReadback = new PlatformTransactionManager() {
            private int reads;
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                if (++reads > 1) { throw new TransactionSystemException("synthetic reconciliation unavailable"); }
                return lost.getTransaction(definition);
            }
            @Override public void rollback(TransactionStatus status) { lost.rollback(status); }
            @Override public void commit(TransactionStatus status) { lost.commit(status); }
        };
        assertThatThrownBy(() -> service(unavailableReadback).change(original, "synthetic-current-password", "new-valid-password"))
                .isInstanceOfSatisfying(AuthFailure.class, e -> assertThat(e.getMessage()).isEqualTo("AUTH_OUTCOME_UNCERTAIN"));
        assertThat(identities.byId("1").get(0).credentialVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit_event", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> authentication.current(original)).isInstanceOf(AuthFailure.class);
    }

    @Test void concurrentInitialChangesCommitOnlyOneCredentialAndAudit() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                try { service(manager).change(original, "synthetic-current-password", "new-valid-password-" + i); return true; }
                catch (AuthFailure stale) { return false; }
            })).toList();
            int successes = 0;
            for (var task : tasks) { if (task.get(15, TimeUnit.SECONDS)) { successes++; } }
            assertThat(successes).isEqualTo(1);
            assertThat(identities.byId("1").get(0).securityVersion()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit_event", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
