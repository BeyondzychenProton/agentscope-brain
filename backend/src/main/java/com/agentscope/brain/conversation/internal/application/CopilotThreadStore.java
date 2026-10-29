package com.agentscope.brain.conversation.internal.application;

import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.conversation.api.CopilotThreadFailure;
import com.agentscope.brain.identity.api.CurrentUser;
import com.agentscope.brain.release.api.SubmissionRelease;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for the text-only Copilot thread. No business session or formal message is created. */
@Service
public class CopilotThreadStore implements CopilotThreads {
    private final JdbcTemplate jdbc;
    private final CurrentUser users;
    private final SubmissionRelease releases;
    private final Clock clock;

    public CopilotThreadStore(JdbcTemplate jdbc, CurrentUser users, SubmissionRelease releases, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.releases = releases;
        this.clock = clock;
    }

    /** Accepts only the new text or a resume answer; callers cannot supply or replace stored history. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Accepted accept(String threadId, String runId, String employeeId, String text, byte[] sessionHash,
            List<Resume> resume) {
        var user = users.requireLocked();
        if (user.passwordChangeRequired()) throw failure(403, "PASSWORD_CHANGE_REQUIRED");
        threadId = id(threadId);
        runId = id(runId);
        List<Resume> answers = resume == null ? List.of() : List.copyOf(resume);
        boolean resuming = !answers.isEmpty();
        if (resuming) {
            if (text != null && !text.isBlank()) throw failure(400, "REQUEST_INVALID");
        } else {
            validateText(text);
        }
        if (employeeId == null || !employeeId.matches("[1-9][0-9]{0,19}") || sessionHash == null || sessionHash.length != 32)
            throw failure(400, "REQUEST_INVALID");

        var existing = jdbc.queryForList("SELECT employee_id,employee_release_id FROM copilot_thread WHERE thread_id=? AND user_id=?",
                threadId, user.userId());
        boolean created = existing.isEmpty();
        if (!created && !employeeId.equals(String.valueOf(existing.get(0).get("employee_id"))))
            throw failure(404, "NOT_FOUND");
        var release = releases.lock(user.userId(), employeeId,
                created ? null : String.valueOf(existing.get(0).get("employee_release_id")), created);
        var now = now();
        if (created) {
            try {
                jdbc.update("""
                        INSERT INTO copilot_thread(thread_id,user_id,employee_id,employee_release_id,title,last_activity_at,gmt_create,gmt_modified)
                        VALUES(?,?,?,?,?,?,?,?)
                        """, threadId, user.userId(), employeeId, release.releaseId(), title(text == null || text.isBlank() ? "会议助手" : text), now, now, now);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                // A foreign or concurrent thread ID never reveals its owner and is never reused.
                throw failure(409, "THREAD_UNAVAILABLE");
            }
        }
        var row = owned(threadId, user.userId(), true);
        if (!employeeId.equals(String.valueOf(row.get("employee_id")))
                || !release.releaseId().equals(String.valueOf(row.get("employee_release_id"))))
            throw failure(409, "THREAD_UNAVAILABLE");
        if (row.get("active_run_id") != null) {
            if (!recoverExpired(threadId, row, now)) throw failure(409, "THREAD_ALREADY_RUNNING");
        }
        var history = messages(threadId).stream()
                .filter(message -> message.status().equals("COMPLETE")
                        || (resuming && message.displayJson() != null))
                .toList();
        long last = ((Number) row.get("last_message_no")).longValue();
        if (last > Long.MAX_VALUE - 2) throw failure(503, "THREAD_UNAVAILABLE");
        jdbc.update("INSERT INTO copilot_thread_run(thread_id,run_id,session_id_hash,state,started_at,lease_until) VALUES(?,?,?,'RUNNING',?,?)",
                threadId, runId, sessionHash, now, now.plusSeconds(30));
        if (!resuming) {
            jdbc.update("""
                    INSERT INTO copilot_thread_message(thread_id,message_no,message_id,run_id,role,content,status,gmt_create,gmt_modified)
                    VALUES(?,?,?,?,'USER',?,'COMPLETE',?,?)
                    """, threadId, last + 1, UUID.randomUUID().toString(), runId, text, now, now);
            last = last + 1;
        }
        jdbc.update("""
                INSERT INTO copilot_thread_message(thread_id,message_no,message_id,run_id,role,content,status,gmt_create,gmt_modified)
                VALUES(?,?,?,?,'ASSISTANT','','STREAMING',?,?)
                """, threadId, last + 1, UUID.randomUUID().toString(), runId, now, now);
        jdbc.update("""
                UPDATE copilot_thread SET active_run_id=?,last_message_no=?,last_activity_at=?,gmt_modified=?,row_version=row_version+1
                WHERE thread_id=? AND user_id=?
                """, runId, last + 1, now, now, threadId, user.userId());
        return new Accepted(threadId, runId, release.releaseId(), history, user.userId(), answers);
    }

    @Override
    public String interruptedRun(String threadId) {
        var rows = jdbc.queryForList("""
                SELECT run_id FROM copilot_thread_run
                WHERE thread_id=? AND state='INTERRUPTED' AND failure_code='CONFIRMATION_REQUIRED'
                ORDER BY started_at DESC LIMIT 1
                """, String.class, id(threadId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Thread open(String threadId) {
        var user = users.require();
        if (user.passwordChangeRequired()) throw failure(403, "PASSWORD_CHANGE_REQUIRED");
        var row = owned(id(threadId), user.userId(), true);
        if (row.get("active_run_id") != null && recoverExpired(threadId, row, now()))
            row = owned(threadId, user.userId(), false);
        var policy = jdbc.queryForList("""
                SELECT r.availability_status,g.grant_status FROM employee_release r
                LEFT JOIN employee_use_grant g ON g.employee_id=r.employee_id AND g.user_id=?
                WHERE r.id=? AND r.employee_id=?
                """, user.userId(), row.get("employee_release_id"), row.get("employee_id"));
        if (policy.size() != 1) throw failure(503, "THREAD_UNAVAILABLE");
        String reason = !"ACTIVE".equals(policy.get(0).get("grant_status")) ? "EMPLOYEE_USE_REVOKED"
                : !"AVAILABLE".equals(policy.get(0).get("availability_status")) ? "RELEASE_DISABLED" : null;
        var lastRun = jdbc.queryForList("""
                SELECT r.state,r.failure_code FROM copilot_thread_run r JOIN copilot_thread_message m
                ON m.thread_id=r.thread_id AND m.run_id=r.run_id AND m.role='USER'
                WHERE r.thread_id=? ORDER BY m.message_no DESC LIMIT 1
                """, threadId);
        String state = lastRun.isEmpty() ? null : String.valueOf(lastRun.get(0).get("state"));
        String code = lastRun.isEmpty() ? null : (String) lastRun.get(0).get("failure_code");
        return new Thread(threadId, String.valueOf(row.get("employee_id")), String.valueOf(row.get("employee_release_id")),
                String.valueOf(row.get("title")), String.valueOf(row.get("row_version")),
                (String) row.get("active_run_id"), reason, state, code, messages(threadId));
    }

    @Transactional(readOnly = true)
    public List<Summary> list(String employeeId, String query, int limit, int offset) {
        var user = users.require();
        if (user.passwordChangeRequired()) throw failure(403, "PASSWORD_CHANGE_REQUIRED");
        if (employeeId == null || !employeeId.matches("[1-9][0-9]{0,19}") || limit < 1 || limit > 100 || offset < 0)
            throw failure(400, "REQUEST_INVALID");
        String search = query == null ? "" : query.strip();
        if (search.codePointCount(0, search.length()) > 200
                || search.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
            throw failure(400, "REQUEST_INVALID");
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.query("""
                SELECT thread_id,title,row_version,active_run_id,last_activity_at FROM copilot_thread
                WHERE user_id=? AND employee_id=? AND title LIKE ? ESCAPE '!'
                ORDER BY last_activity_at DESC,thread_id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> new Summary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toLocalDateTime().toInstant(ZoneOffset.UTC).toString()),
                user.userId(), employeeId, pattern, limit, offset);
    }

    @Transactional
    public void rename(String threadId, String expectedVersion, String rawTitle) {
        var user = users.require();
        if (user.passwordChangeRequired()) throw failure(403, "PASSWORD_CHANGE_REQUIRED");
        threadId = id(threadId);
        String title;
        try { title = SessionTitleRename.normalizeTitle(rawTitle); }
        catch (SessionReadFailure invalid) { throw failure(invalid.status(), invalid.getMessage()); }
        var row = owned(threadId, user.userId(), true);
        if (!String.valueOf(row.get("row_version")).equals(expectedVersion)) throw failure(409, "VERSION_CONFLICT");
        var now = now();
        jdbc.update("UPDATE copilot_thread SET title=?,row_version=row_version+1,last_activity_at=?,gmt_modified=? WHERE thread_id=? AND user_id=?",
                title, now, now, threadId, user.userId());
    }

    /** Called by the execution owner after a model increment, never by a browser. */
    @Transactional
    public boolean append(String threadId, String runId, String delta) {
        if (delta == null || delta.isEmpty()) return true;
        return recordOutput(threadId, runId, delta, null);
    }

    @Transactional
    public boolean recordOutput(String threadId, String runId, String delta, String displayJson) {
        var rows = jdbc.queryForList("SELECT active_run_id FROM copilot_thread WHERE thread_id=? FOR UPDATE", id(threadId));
        if (rows.size() != 1 || !id(runId).equals(rows.get(0).get("active_run_id"))) return false;
        int changed = jdbc.update("""
                UPDATE copilot_thread_message SET content=CONCAT(content,?),display_json=COALESCE(?,display_json),gmt_modified=?
                WHERE thread_id=? AND run_id=? AND role='ASSISTANT' AND status='STREAMING'
                """, delta == null ? "" : delta, displayJson, now(), threadId, runId);
        if (changed != 1) throw new IllegalStateException("COPILOT_RUN_STATE_INVALID");
        return true;
    }

    @Transactional
    public boolean heartbeat(String threadId, String runId) {
        var rows = jdbc.queryForList("SELECT active_run_id FROM copilot_thread WHERE thread_id=? FOR UPDATE", id(threadId));
        if (rows.size() != 1 || !id(runId).equals(rows.get(0).get("active_run_id"))) return false;
        var now = now();
        return jdbc.update("""
                UPDATE copilot_thread_run SET lease_until=? WHERE thread_id=? AND run_id=? AND state='RUNNING' AND lease_until>?
                """, now.plusSeconds(30), threadId, runId, now) == 1;
    }

    /** A terminal transition retains partial text and releases only the matching run. */
    @Transactional
    public boolean finish(String threadId, String runId, String state, String failureCode) {
        if (!List.of("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED").contains(state))
            throw new IllegalArgumentException("Invalid terminal state");
        if (failureCode != null && !failureCode.matches("[A-Z0-9_]{1,64}"))
            throw new IllegalArgumentException("Invalid failure code");
        var rows = jdbc.queryForList("SELECT active_run_id FROM copilot_thread WHERE thread_id=? FOR UPDATE", id(threadId));
        if (rows.size() != 1 || !id(runId).equals(rows.get(0).get("active_run_id"))) return false;
        var now = now();
        int message = jdbc.update("""
                UPDATE copilot_thread_message SET status=?,gmt_modified=?
                WHERE thread_id=? AND run_id=? AND role='ASSISTANT' AND status='STREAMING'
                """, state.equals("SUCCEEDED") ? "COMPLETE" : "UNFINISHED", now, threadId, runId);
        int run = jdbc.update("""
                UPDATE copilot_thread_run SET state=?,failure_code=?,ended_at=?
                WHERE thread_id=? AND run_id=? AND state='RUNNING'
                """, state, failureCode, now, threadId, runId);
        int thread = jdbc.update("""
                UPDATE copilot_thread SET active_run_id=NULL,last_activity_at=?,gmt_modified=?,row_version=row_version+1
                WHERE thread_id=? AND active_run_id=?
                """, now, now, threadId, runId);
        if (message != 1 || run != 1 || thread != 1) throw new IllegalStateException("COPILOT_RUN_STATE_INVALID");
        return true;
    }

    private Map<String, Object> owned(String threadId, String userId, boolean lock) {
        var rows = jdbc.queryForList("SELECT employee_id,employee_release_id,title,row_version,active_run_id,last_message_no "
                + "FROM copilot_thread WHERE thread_id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""), threadId, userId);
        if (rows.size() != 1) throw failure(404, "NOT_FOUND");
        return rows.get(0);
    }

    private boolean recoverExpired(String threadId, Map<String, Object> row, LocalDateTime now) {
        String active = String.valueOf(row.get("active_run_id"));
        var runs = jdbc.queryForList("SELECT lease_until FROM copilot_thread_run WHERE thread_id=? AND run_id=? AND state='RUNNING'",
                threadId, active);
        if (runs.size() != 1) throw failure(503, "THREAD_UNAVAILABLE");
        Object value = runs.get(0).get("lease_until");
        LocalDateTime until = value instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
        if (until.isAfter(now)) return false;
        if (!finish(threadId, active, "INTERRUPTED", "EXECUTION_INTERRUPTED")) throw failure(503, "THREAD_UNAVAILABLE");
        return true;
    }

    private List<Message> messages(String threadId) {
        return jdbc.query("SELECT message_id,role,content,status,display_json FROM copilot_thread_message WHERE thread_id=? ORDER BY message_no",
                (rs, n) -> new Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), threadId);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC); }
    private static String id(String value) {
        try {
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equals(value)) throw new IllegalArgumentException();
            return normalized;
        } catch (Exception invalid) { throw failure(400, "REQUEST_INVALID"); }
    }
    private static void validateText(String text) {
        if (text == null || text.isBlank() || text.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)
                || text.getBytes(StandardCharsets.UTF_8).length > 262144) throw failure(400, "TEXT_INVALID");
    }
    private static String title(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.substring(0, line.offsetByCodePoints(0, Math.min(60, line.codePointCount(0, line.length()))));
    }
    private static CopilotThreadFailure failure(int status, String code) { return new CopilotThreadFailure(status, code); }
}
