package com.agentscope.brain.runtime.internal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-owned proposals for Copilot write tools: one proposal, one single-use confirmation.
 *
 * <p>Copilot runs are identified by UUID, so rows bind through {@code copilot_run_id}; the
 * ordinary business path keeps using the numeric {@code run_id}.
 */
@Component
public class WriteOperations {
    private static final Logger LOG = LoggerFactory.getLogger(WriteOperations.class);
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROVIDER = "meeting-mcp";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;

    public WriteOperations(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc; this.clock = clock;
    }

    public record Proposal(long operationId, long confirmationId, String requestId, Instant expiresAt) { }
    public record Confirmed(long operationId, String requestId) { }

    /** Records the proposal shown to the user. The provider is not contacted until it is confirmed. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Proposal propose(String threadId, String runId, String userId, String toolName,
                            Map<String, Object> arguments) {
        String canonical = canonical(arguments);
        byte[] argumentsSha = sha256(canonical);
        String requestId = newRequestId(toolName);
        byte[] operationSha = sha256(toolName + "\n" + requestId + "\n" + canonical);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(TTL);
        Timestamp at = Timestamp.from(now);
        long operationNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(operation_no),0)+1 FROM tool_operation WHERE copilot_run_id=?", Long.class, runId);
        long identityVersion = identityVersion(userId);
        jdbc.update("""
                INSERT INTO tool_operation
                    (run_id,copilot_run_id,operation_no,actor_user_id,tool_provider_key,tool_name,
                     tool_binding_version,arguments_schema_version,effect_kind,is_confirmation_required,
                     identity_binding_kind,identity_binding_ref,identity_binding_version,request_id,
                     idempotency_mode,provider_request_id,reconciliation_mode,normalized_arguments,
                     arguments_sha256,operation_sha256,status,gmt_create,gmt_modified)
                VALUES (NULL,?,?,?,?,?,?,?,'MUTATING',1,'PLATFORM_USER',?,?,?,'PROVIDER_KEY',?,'REQUEST_ID',
                        CAST(? AS JSON),?,?,'PROPOSED',?,?)
                """, runId, operationNo, Long.parseLong(userId), PROVIDER, toolName,
                PROVIDER + "." + toolName + ".v1", "v1", userId, identityVersion, requestId, requestId,
                canonical, argumentsSha, operationSha, at, at);
        long operationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String token = UUID.randomUUID().toString();
        int confirmations = jdbc.update("""
                INSERT INTO operation_confirmation
                    (operation_id,run_id,copilot_run_id,actor_user_id,arguments_sha256,operation_sha256,
                     authorization_version,security_version,identity_binding_version,
                     confirmation_token_sha256,status,expires_at,gmt_create,gmt_modified)
                SELECT ?,NULL,?,?,?,?,u.authorization_version,u.security_version,?,?,'PENDING',?,?,?
                FROM platform_user u WHERE u.id=?
                """, operationId, runId, Long.parseLong(userId), argumentsSha, operationSha,
                identityVersion, sha256(token), Timestamp.from(expiresAt), at, at, Long.parseLong(userId));
        if (confirmations != 1) throw new IllegalStateException("MCP_PHONE_UNAVAILABLE");
        long confirmationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        LOG.info("Copilot write proposal recorded tool={} runId={} operationId={}", toolName, runId, operationId);
        return new Proposal(operationId, confirmationId, requestId, expiresAt);
    }

    /**
     * Consumes the single pending confirmation for this run/tool/arguments. Only the resume path
     * that follows an explicit user approval reaches tool execution, so this is the one-time gate.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Confirmed consume(String runId, String toolName, Map<String, Object> arguments) {
        byte[] argumentsSha = sha256(canonical(arguments));
        Timestamp at = Timestamp.from(clock.instant());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id confirmation_id,c.operation_id,o.request_id
                FROM operation_confirmation c JOIN tool_operation o ON o.id=c.operation_id
                WHERE c.copilot_run_id=? AND o.tool_name=? AND o.arguments_sha256=?
                  AND c.status='PENDING' AND o.status='PROPOSED' AND c.expires_at>?
                ORDER BY c.id DESC LIMIT 1 FOR UPDATE
                """, runId, toolName, argumentsSha, at);
        if (rows.isEmpty()) {
            jdbc.update("""
                    UPDATE operation_confirmation SET status='EXPIRED',gmt_modified=?
                    WHERE copilot_run_id=? AND status='PENDING' AND expires_at<=?
                    """, at, runId, at);
            throw new IllegalStateException("CONFIRMATION_REQUIRED");
        }
        long confirmationId = ((Number) rows.get(0).get("confirmation_id")).longValue();
        long operationId = ((Number) rows.get(0).get("operation_id")).longValue();
        if (jdbc.update("UPDATE operation_confirmation SET status='CONSUMED',consumed_at=?,gmt_modified=? "
                + "WHERE id=? AND status='PENDING'", at, at, confirmationId) != 1)
            throw new IllegalStateException("CONFIRMATION_REQUIRED");
        if (jdbc.update("UPDATE tool_operation SET status='SENT',sent_at=?,gmt_modified=? "
                + "WHERE id=? AND status='PROPOSED'", at, at, operationId) != 1)
            throw new IllegalStateException("CONFIRMATION_REQUIRED");
        return new Confirmed(operationId, String.valueOf(rows.get(0).get("request_id")));
    }

    public void resolve(long operationId, String status, String resultCode, Map<String, Object> result) {
        Timestamp at = Timestamp.from(clock.instant());
        String normalized = null;
        if (result != null) {
            try { normalized = json.writeValueAsString(result); } catch (Exception ignored) { }
        }
        jdbc.update("""
                UPDATE tool_operation SET status=?,provider_result_code=?,normalized_result=CAST(? AS JSON),
                    resolved_at=?,gmt_modified=?
                WHERE id=?
                """, status, resultCode, normalized, at, at, operationId);
    }

    /** Moves proposals created for the interrupted run onto the run that will execute them. */
    public void adopt(String interruptedRunId, String resumingRunId) {
        Timestamp at = Timestamp.from(clock.instant());
        jdbc.update("UPDATE tool_operation SET copilot_run_id=?,gmt_modified=? "
                + "WHERE copilot_run_id=? AND status='PROPOSED'", resumingRunId, at, interruptedRunId);
        jdbc.update("UPDATE operation_confirmation SET copilot_run_id=?,gmt_modified=? "
                + "WHERE copilot_run_id=? AND status='PENDING'", resumingRunId, at, interruptedRunId);
    }

    /** A denied or cancelled confirmation never becomes executable again. */
    public void invalidateRun(String runId) {
        Timestamp at = Timestamp.from(clock.instant());
        List<Long> operations = jdbc.queryForList(
                "SELECT id FROM tool_operation WHERE copilot_run_id=? AND status='PROPOSED'", Long.class, runId);
        for (Long id : new ArrayList<>(operations)) {
            jdbc.update("UPDATE tool_operation SET status='CANCELLED',resolved_at=?,gmt_modified=? "
                    + "WHERE id=? AND status='PROPOSED'", at, at, id);
            jdbc.update("UPDATE operation_confirmation SET status='INVALIDATED',gmt_modified=? "
                    + "WHERE operation_id=? AND status='PENDING'", at, id);
        }
    }

    public void expireRun(String runId) {
        invalidateRun(runId);
    }

    private long identityVersion(String userId) {
        Long revision = jdbc.queryForObject("SELECT phone_revision FROM platform_user WHERE id=?",
                Long.class, Long.parseLong(userId));
        if (revision == null || revision <= 0) throw new IllegalStateException("MCP_PHONE_UNAVAILABLE");
        return revision;
    }

    private static String newRequestId(String toolName) {
        String prefix = toolName.startsWith("room.") ? "reserve-" : "cancel-";
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /**
     * Canonical form of the operation arguments. {@code requestId} is excluded because the
     * platform owns it: a model that adds, drops or changes it must still match the proposal the
     * user approved, otherwise the same intent produces a new confirmation card forever.
     */
    static String canonical(Map<String, Object> arguments) {
        try {
            var sorted = new TreeMap<String, Object>();
            if (arguments != null) {
                arguments.forEach((key, value) -> {
                    if (!"requestId".equals(key)) sorted.put(key, value);
                });
            }
            return JSON.writeValueAsString(sorted);
        } catch (Exception failure) {
            throw new IllegalStateException("TOOL_ARGUMENTS_INVALID", failure);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("HASH_FAILED", failure);
        }
    }
}
