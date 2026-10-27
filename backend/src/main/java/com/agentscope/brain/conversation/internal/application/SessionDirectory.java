package com.agentscope.brain.conversation.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Ordinary, unarchived session navigation. Reads do not dispatch or repair business facts. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SessionDirectory {
    public record ActiveRun(String runId, String status, String processingStatus) { }
    public record Summary(String sessionId, String employeeId, String title, String sessionVersion,
            String updatedAt, ActiveRun activeRun, List<String> allowedActions) { }
    public record Page(List<Summary> items, String nextPageCursor, boolean hasMore) { }
    private final JdbcTemplate jdbc;
    private final CurrentUser users;
    private final SessionListCursor cursors;
    public SessionDirectory(JdbcTemplate jdbc, CurrentUser users, SessionListCursor cursors) {
        this.jdbc = jdbc; this.users = users; this.cursors = cursors;
    }
    public Page list(String employeeId, String q, String pageCursor, int limit) {
        var user = users.require();
        if (user.passwordChangeRequired()) { throw new SessionReadFailure(403, "PASSWORD_CHANGE_REQUIRED"); }
        if (!positiveId(employeeId)) { throw new SessionReadFailure(404, "NOT_FOUND"); }
        if (limit < 1 || limit > 100) { throw new SessionReadFailure(400, "REQUEST_INVALID"); }
        String query = q == null ? "" : q.strip();
        if (query.codePointCount(0, query.length()) > 200 || query.codePoints().anyMatch(c ->
                Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) {
            throw new SessionReadFailure(400, "REQUEST_INVALID");
        }
        // A revoked grant or an owned history still permits navigation; release availability is not a read prerequisite.
        var visible = jdbc.queryForObject("""
                SELECT COUNT(*) FROM digital_employee e WHERE e.id=? AND (
                    EXISTS (SELECT 1 FROM employee_use_grant g WHERE g.employee_id=e.id AND g.user_id=?) OR
                    EXISTS (SELECT 1 FROM business_session s WHERE s.employee_id=e.id AND s.user_id=?))
                """, Long.class, employeeId, user.userId(), user.userId());
        if (visible == null || visible != 1) { throw new SessionReadFailure(404, "NOT_FOUND"); }
        var sql = new StringBuilder("""
                SELECT id,employee_id,title,row_version,last_activity_at FROM business_session
                WHERE user_id=? AND employee_id=? AND is_archived=0
                """);
        var args = new ArrayList<Object>(List.of(user.userId(), employeeId));
        if (!query.isEmpty()) {
            sql.append(" AND title LIKE ? ESCAPE '!' ");
            args.add("%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (pageCursor != null) {
            var p = cursors.decode(pageCursor);
            if (!user.userId().equals(p.userId()) || !employeeId.equals(p.employeeId()) || !query.equals(p.query())) {
                throw new SessionReadFailure(409, "CURSOR_INVALID");
            }
            try {
                if (!positiveId(p.sessionId())) { throw new IllegalArgumentException(); }
                var before = LocalDateTime.ofInstant(Instant.parse(p.updatedAt()), ZoneOffset.UTC);
                sql.append(" AND (last_activity_at<? OR (last_activity_at=? AND id<?)) ");
                args.add(before); args.add(before); args.add(p.sessionId());
            } catch (Exception invalid) { throw new SessionReadFailure(409, "CURSOR_INVALID"); }
        }
        sql.append(" ORDER BY last_activity_at DESC,id DESC LIMIT ?"); args.add(limit + 1);
        var rows = jdbc.query(sql.toString(), (rs, row) -> new Summary(rs.getString("id"), rs.getString("employee_id"),
                rs.getString("title"), rs.getString("row_version"), rs.getObject("last_activity_at", LocalDateTime.class).toInstant(ZoneOffset.UTC).toString(),
                null, List.of()), args.toArray());
        boolean more = rows.size() > limit;
        var items = new ArrayList<Summary>();
        for (var s : rows.subList(0, Math.min(limit, rows.size()))) {
            var active = jdbc.query("""
                    SELECT id,status FROM business_run WHERE session_id=? AND actor_user_id=?
                    AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED') LIMIT 2
                    """, (rs, row) -> new ActiveRun(rs.getString("id"), rs.getString("status"), null), s.sessionId(), user.userId());
            if (active.size() > 1) { throw new SessionReadFailure(503, "SESSION_READ_UNAVAILABLE"); }
            // Dispatch interpretation and execution actions are delivered with the runtime slice.
            items.add(new Summary(s.sessionId(), s.employeeId(), s.title(), s.sessionVersion(), s.updatedAt(),
                    active.isEmpty() ? null : active.get(0), List.of()));
        }
        String next = null;
        if (more) {
            var last = items.get(items.size() - 1);
            next = cursors.encode(new SessionListCursor.Position("session-list-v1", user.userId(), employeeId,
                    query, last.updatedAt(), last.sessionId()));
        }
        return new Page(List.copyOf(items), next, more);
    }
    private static boolean positiveId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,19}") && new BigInteger(value).bitLength() <= 64;
    }
}
