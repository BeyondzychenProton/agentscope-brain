package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.projection.api.AcceptedTextProjection;
import com.agentscope.brain.projection.internal.application.ProcessStepSecurity;
import com.agentscope.brain.projection.internal.application.ProcessStepWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits one ASSISTANT reply and releases the ordinary execution permit. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrdinaryRunFinalize {
    private final JdbcTemplate jdbc;
    private final AcceptedTextProjection projection;
    private final ProcessStepWriter processWriter;

    public OrdinaryRunFinalize(JdbcTemplate jdbc, AcceptedTextProjection projection, ProcessStepWriter processWriter) {
        this.jdbc = jdbc;
        this.projection = projection;
        this.processWriter = processWriter;
    }

    public void succeed(OrdinaryRunClaim.Lease lease, String text, byte[] stateDigest, LocalDateTime now) {
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > 262144) {
            throw new IllegalArgumentException("ASSISTANT_TEXT_INVALID");
        }
        byte[] token = uuidBytes(lease.claimToken());
        var existing = jdbc.queryForList("SELECT id FROM business_message WHERE produced_by_run_id=? FOR UPDATE", lease.runId());
        if (!existing.isEmpty()) return;
        var dispatch = lockOne("""
                SELECT * FROM runtime_dispatch WHERE id=? AND run_id=? AND session_id=? AND status='CLAIMED'
                  AND permit_state='HELD' AND claim_token=? AND claim_fence=? FOR UPDATE
                """, lease.dispatchId(), lease.runId(), lease.sessionId(), token, lease.claimFence());
        var run = lockOne("SELECT * FROM business_run WHERE id=? AND session_id=? AND status='RUNNING' FOR UPDATE",
                lease.runId(), lease.sessionId());
        var call = lockOne("""
                SELECT * FROM runtime_sdk_call WHERE id=? AND dispatch_id=? AND claim_fence=? AND status='STARTED' FOR UPDATE
                """, lease.sdkCallId(), lease.dispatchId(), lease.claimFence());
        var session = lockOne("SELECT * FROM business_session WHERE id=? AND user_id=? FOR UPDATE",
                lease.sessionId(), lease.userId());
        String no = plus(value(session, "last_message_no"));
        String seq = plus(value(session, "last_projection_seq"));
        String version = plus(value(session, "row_version"));
        String mid = insert("""
                INSERT INTO business_message(session_id,user_id,employee_id,message_no,input_sequence,sender_kind,submission_key,
                  submission_sha256,initial_run_id,produced_by_run_id,accepted_at,gmt_create,gmt_modified)
                VALUES(?,?,NULL,?,NULL,'ASSISTANT',NULL,NULL,NULL,?,?,?,?)
                """, lease.sessionId(), lease.userId(), no, lease.runId(), now, now, now);
        String block = insert("""
                INSERT INTO message_content_block(message_id,block_no,block_kind,text_content,gmt_create,gmt_modified)
                VALUES(?,1,'TEXT',?,?,?)
                """, mid, text, now, now);
        if (jdbc.update("""
                UPDATE business_run SET status='SUCCEEDED',ended_at=?,row_version=row_version+1,gmt_modified=?
                WHERE id=? AND status='RUNNING' AND row_version=?
                """, now, now, lease.runId(), run.get("row_version")) != 1) {
            throw new IllegalStateException("RUN_FINALIZE_LOST");
        }
        if (jdbc.update("""
                UPDATE runtime_sdk_call SET status='VERIFIED',save_status='SAVED',readback_status='MATCHED',
                  saved_state_sha256=?,readback_state_sha256=?,verified_at=?,ended_at=?,outcome_kind='COMPLETE',
                  row_version=row_version+1,gmt_modified=?
                WHERE id=? AND status='STARTED'
                """, stateDigest, stateDigest, now, now, now, lease.sdkCallId()) != 1) {
            throw new IllegalStateException("SDK_CALL_FINALIZE_LOST");
        }
        if (jdbc.update("""
                UPDATE runtime_dispatch SET status='COMPLETED',permit_state='RELEASED',finished_at=?,
                  row_version=row_version+1,gmt_modified=?
                WHERE id=? AND status='CLAIMED' AND permit_state='HELD' AND claim_token=?
                """, now, now, lease.dispatchId(), token) != 1) {
            throw new IllegalStateException("DISPATCH_FINALIZE_LOST");
        }
        if (jdbc.update("""
                UPDATE runtime_capacity SET occupied_count=occupied_count-1,row_version=row_version+1,gmt_modified=?
                WHERE singleton_key=1 AND occupied_count>0 AND boot_epoch=?
                """, now, lease.bootEpoch()) != 1) {
            throw new IllegalStateException("CAPACITY_RELEASE_LOST");
        }
        jdbc.update("""
                UPDATE business_session SET last_message_no=?,last_projection_seq=?,row_version=?,last_activity_at=?,gmt_modified=?
                WHERE id=?
                """, no, seq, version, now, now, lease.sessionId());
        projection.append(new AcceptedTextProjection.Fact(lease.userId(), lease.employeeId(), lease.sessionId(), lease.releaseId(),
                (String) session.get("title"), version, uuid((byte[]) session.get("projection_stream_id")), seq,
                value(session, "last_projection_seq"), mid, no, block, lease.runId(), text, now, "ASSISTANT", "SUCCEEDED"));
        processWriter.upsertStage(new ProcessStepWriter.Fact(
                lease.userId(), lease.sessionId(), lease.runId(), stageStepId(lease.runId()),
                "执行完成", null, "DONE", false, null, null, now));
    }

    public void fail(OrdinaryRunClaim.Lease lease, String reason, LocalDateTime now) {
        byte[] token = uuidBytes(lease.claimToken());
        var existing = jdbc.queryForList("SELECT id FROM business_message WHERE produced_by_run_id=?", lease.runId());
        if (!existing.isEmpty()) return;
        var dispatch = jdbc.queryForList("""
                SELECT * FROM runtime_dispatch WHERE id=? AND status='CLAIMED' AND permit_state='HELD' AND claim_token=? FOR UPDATE
                """, lease.dispatchId(), token);
        if (dispatch.isEmpty()) return;
        jdbc.update("""
                UPDATE business_run SET status='FAILED',ended_at=?,reconcile_reason=?,row_version=row_version+1,gmt_modified=?
                WHERE id=? AND status='RUNNING'
                """, now, reason, now, lease.runId());
        jdbc.update("""
                UPDATE runtime_sdk_call SET status='RECONCILING',ended_at=?,outcome_kind=?,row_version=row_version+1,gmt_modified=?
                WHERE id=? AND status='STARTED'
                """, now, reason, now, lease.sdkCallId());
        jdbc.update("""
                UPDATE runtime_dispatch SET status='COMPLETED',permit_state='RELEASED',finished_at=?,reason_code=?,
                  row_version=row_version+1,gmt_modified=?
                WHERE id=? AND status='CLAIMED' AND claim_token=?
                """, now, reason, now, lease.dispatchId(), token);
        jdbc.update("""
                UPDATE runtime_capacity SET occupied_count=occupied_count-1,row_version=row_version+1,gmt_modified=?
                WHERE singleton_key=1 AND occupied_count>0 AND boot_epoch=?
                """, now, lease.bootEpoch());
        processWriter.upsertStage(new ProcessStepWriter.Fact(
                lease.userId(), lease.sessionId(), lease.runId(), stageStepId(lease.runId()),
                "执行失败", ProcessStepSecurity.redactDisplayText(reason), "FAILED", false, null, null, now));
    }

    private static String stageStepId(String runId) {
        return UUID.nameUUIDFromBytes(("stage-" + runId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Map<String, Object> lockOne(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        if (rows.size() != 1) throw new IllegalStateException("FINALIZE_STATE_LOST");
        return rows.get(0);
    }

    private String insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new IllegalStateException("INSERT_RECEIPT_UNAVAILABLE");
        return holder.getKey().toString();
    }

    private static String value(Map<String, Object> row, String key) { return String.valueOf(row.get(key)); }
    private static String plus(String value) { return new BigInteger(value).add(BigInteger.ONE).toString(); }
    private static byte[] uuidBytes(String value) {
        var id = UUID.fromString(value);
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static String uuid(byte[] value) {
        var b = ByteBuffer.wrap(value);
        return new UUID(b.getLong(), b.getLong()).toString();
    }
}
