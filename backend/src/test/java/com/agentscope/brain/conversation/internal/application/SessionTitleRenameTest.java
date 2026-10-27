package com.agentscope.brain.conversation.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionTitleRenameTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:title-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    private final CurrentUser users = mock(CurrentUser.class);
    private final SessionTitleRename renames = new SessionTitleRename(jdbc, users);
    private final Instant at = Instant.parse("2026-10-27T08:00:00Z");

    SessionTitleRenameTest() {
        jdbc.execute("""
                CREATE TABLE business_session(
                  id DECIMAL(20,0) PRIMARY KEY,user_id BIGINT,employee_id BIGINT,title VARCHAR(200),
                  row_version BIGINT,is_archived INT,lifecycle_status VARCHAR(24),
                  last_activity_at TIMESTAMP(6),gmt_create TIMESTAMP(6),gmt_modified TIMESTAMP(6))
                """);
        jdbc.execute("""
                CREATE TABLE business_run(
                  id BIGINT PRIMARY KEY,session_id DECIMAL(20,0),actor_user_id BIGINT,status VARCHAR(24))
                """);
        jdbc.execute("""
                CREATE TABLE session_title_request(
                  user_id BIGINT,request_key BINARY(16),request_sha256 BINARY(32),session_id DECIMAL(20,0),
                  title VARCHAR(200),result_session_version BIGINT,gmt_create TIMESTAMP(6),
                  PRIMARY KEY(user_id,request_key))
                """);
        when(users.require()).thenReturn(new CurrentUser.User("1", "用户", "***", false, at, at.plusSeconds(86400)));
        session("10", 1, 1, "旧标题", 1, false);
        session("11", 2, 1, "别人的", 1, false);
        session("12", 1, 1, "已归档", 1, true);
    }

    private void session(String id, int user, int employee, String title, long version, boolean archived) {
        var time = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO business_session VALUES(?,?,?,?,?,?, 'ACTIVE',?,?,?)
                """, id, user, employee, title, version, archived ? 1 : 0, time, time, time);
    }

    @Test void renamesOwnedSessionAndIncrementsVersion() {
        String key = UUID.randomUUID().toString();
        var summary = renames.rename("10", new SessionTitleRename.Input(key, "1", " 新标题 "));
        assertThat(summary.title()).isEqualTo("新标题");
        assertThat(summary.sessionVersion()).isEqualTo("2");
        assertThat(summary.sessionId()).isEqualTo("10");
        assertThat(jdbc.queryForObject("SELECT title FROM business_session WHERE id=10", String.class)).isEqualTo("新标题");
    }

    @Test void sameRequestKeyIsIdempotent() {
        String key = UUID.randomUUID().toString();
        var first = renames.rename("10", new SessionTitleRename.Input(key, "1", "一次"));
        var second = renames.rename("10", new SessionTitleRename.Input(key, "1", "一次"));
        assertThat(second.sessionVersion()).isEqualTo(first.sessionVersion());
        assertThat(jdbc.queryForObject("SELECT row_version FROM business_session WHERE id=10", Long.class)).isEqualTo(2L);
    }

    @Test void versionConflictDoesNotWrite() {
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> renames.rename("10", new SessionTitleRename.Input(key, "9", "x")))
                .isInstanceOfSatisfying(SessionReadFailure.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("VERSION_CONFLICT");
                });
        assertThat(jdbc.queryForObject("SELECT title FROM business_session WHERE id=10", String.class)).isEqualTo("旧标题");
    }

    @Test void emptyOrOversizedTitleRejected() {
        assertThatThrownBy(() -> renames.rename("10", new SessionTitleRename.Input(UUID.randomUUID().toString(), "1", "   ")))
                .isInstanceOfSatisfying(SessionReadFailure.class, e -> assertThat(e.getMessage()).isEqualTo("TITLE_INVALID"));
        assertThatThrownBy(() -> renames.rename("10", new SessionTitleRename.Input(UUID.randomUUID().toString(), "1", "字".repeat(201))))
                .isInstanceOfSatisfying(SessionReadFailure.class, e -> assertThat(e.getMessage()).isEqualTo("TITLE_INVALID"));
    }

    @Test void foreignOrArchivedSessionNotFound() {
        assertThatThrownBy(() -> renames.rename("11", new SessionTitleRename.Input(UUID.randomUUID().toString(), "1", "盗改")))
                .isInstanceOfSatisfying(SessionReadFailure.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> renames.rename("12", new SessionTitleRename.Input(UUID.randomUUID().toString(), "1", "归档")))
                .isInstanceOfSatisfying(SessionReadFailure.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test void reusedKeyWithDifferentPayloadConflicts() {
        String key = UUID.randomUUID().toString();
        renames.rename("10", new SessionTitleRename.Input(key, "1", "一次"));
        assertThatThrownBy(() -> renames.rename("10", new SessionTitleRename.Input(key, "1", "另一次")))
                .isInstanceOfSatisfying(SessionReadFailure.class, e -> assertThat(e.getMessage()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
}
