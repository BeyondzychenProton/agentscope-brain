package com.agentscope.brain.conversation.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Renames an owned ordinary session title. No message, Run, or model side effects. */
@Service
public class SessionTitleRename {
    public record Input(String requestKey, String expectedSessionVersion, String title) { }

    private final JdbcTemplate jdbc;
    private final CurrentUser users;

    public SessionTitleRename(JdbcTemplate jdbc, CurrentUser users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SessionDirectory.Summary rename(String sessionId, Input input) {
        var user = users.require();
        if (user.passwordChangeRequired()) {
            throw new SessionReadFailure(403, "PASSWORD_CHANGE_REQUIRED");
        }
        if (!positiveId(sessionId)) {
            throw new SessionReadFailure(404, "NOT_FOUND");
        }
        byte[] requestKey = uuid(input.requestKey());
        String title = normalizeTitle(input.title());
        if (!positiveId(input.expectedSessionVersion())) {
            throw new SessionReadFailure(400, "REQUEST_INVALID");
        }
        byte[] digest = sha256((sessionId + "|" + input.expectedSessionVersion() + "|" + title)
                .getBytes(StandardCharsets.UTF_8));

        var prior = jdbc.queryForList("""
                SELECT session_id,title,result_session_version,request_sha256 FROM session_title_request
                WHERE user_id=? AND request_key=?
                """, user.userId(), requestKey);
        if (!prior.isEmpty()) {
            var row = prior.get(0);
            if (!MessageDigest.isEqual(digest, (byte[]) row.get("request_sha256"))) {
                throw new SessionReadFailure(409, "IDEMPOTENCY_CONFLICT");
            }
            return summary(user.userId(), String.valueOf(row.get("session_id")),
                    (String) row.get("title"), String.valueOf(row.get("result_session_version")));
        }

        var locked = jdbc.queryForList("""
                SELECT id,employee_id,title,row_version,last_activity_at,is_archived,lifecycle_status
                FROM business_session WHERE id=? AND user_id=? FOR UPDATE
                """, sessionId, user.userId());
        if (locked.isEmpty()) {
            throw new SessionReadFailure(404, "NOT_FOUND");
        }
        var session = locked.get(0);
        if (((Number) session.get("is_archived")).intValue() != 0) {
            throw new SessionReadFailure(404, "NOT_FOUND");
        }
        String currentVersion = String.valueOf(session.get("row_version"));
        if (!currentVersion.equals(input.expectedSessionVersion())) {
            throw new SessionReadFailure(409, "VERSION_CONFLICT");
        }

        var now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = jdbc.update("""
                UPDATE business_session SET title=?,row_version=row_version+1,last_activity_at=?,gmt_modified=?
                WHERE id=? AND user_id=? AND row_version=? AND is_archived=0
                """, title, now, now, sessionId, user.userId(), input.expectedSessionVersion());
        if (updated != 1) {
            throw new SessionReadFailure(409, "VERSION_CONFLICT");
        }
        String nextVersion = new BigInteger(currentVersion).add(BigInteger.ONE).toString();
        try {
            jdbc.update("""
                    INSERT INTO session_title_request(user_id,request_key,request_sha256,session_id,title,result_session_version,gmt_create)
                    VALUES(?,?,?,?,?,?,?)
                    """, user.userId(), requestKey, digest, sessionId, title, nextVersion, now);
        } catch (org.springframework.dao.DuplicateKeyException race) {
            var again = jdbc.queryForList("""
                    SELECT session_id,title,result_session_version,request_sha256 FROM session_title_request
                    WHERE user_id=? AND request_key=?
                    """, user.userId(), requestKey);
            if (again.isEmpty() || !MessageDigest.isEqual(digest, (byte[]) again.get(0).get("request_sha256"))) {
                throw new SessionReadFailure(409, "IDEMPOTENCY_CONFLICT");
            }
            return summary(user.userId(), String.valueOf(again.get(0).get("session_id")),
                    (String) again.get(0).get("title"), String.valueOf(again.get(0).get("result_session_version")));
        }
        return summary(user.userId(), sessionId, title, nextVersion);
    }

    private SessionDirectory.Summary summary(String userId, String sessionId, String title, String version) {
        var rows = jdbc.queryForList("""
                SELECT employee_id,last_activity_at FROM business_session WHERE id=? AND user_id=?
                """, sessionId, userId);
        if (rows.isEmpty()) {
            throw new SessionReadFailure(404, "NOT_FOUND");
        }
        Map<String, Object> session = rows.get(0);
        var active = jdbc.query("""
                SELECT id,status FROM business_run WHERE session_id=? AND actor_user_id=?
                AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED') LIMIT 2
                """, (rs, row) -> new SessionDirectory.ActiveRun(rs.getString("id"), rs.getString("status"), null),
                sessionId, userId);
        if (active.size() > 1) {
            throw new SessionReadFailure(503, "SESSION_READ_UNAVAILABLE");
        }
        var at = ((LocalDateTime) session.get("last_activity_at")).toInstant(ZoneOffset.UTC).toString();
        return new SessionDirectory.Summary(sessionId, String.valueOf(session.get("employee_id")), title, version, at,
                active.isEmpty() ? null : active.get(0), List.of());
    }

    static String normalizeTitle(String raw) {
        if (raw == null) {
            throw new SessionReadFailure(400, "TITLE_INVALID");
        }
        String title = raw.strip();
        if (title.isEmpty() || title.codePointCount(0, title.length()) > 200
                || title.codePoints().anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF))) {
            throw new SessionReadFailure(400, "TITLE_INVALID");
        }
        return title;
    }

    private static boolean positiveId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,19}") && new BigInteger(value).bitLength() <= 64;
    }

    private static byte[] uuid(String value) {
        try {
            var id = UUID.fromString(value);
            return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        } catch (Exception invalid) {
            throw new SessionReadFailure(400, "REQUEST_INVALID");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception unavailable) {
            throw new IllegalStateException();
        }
    }
}
