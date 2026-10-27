package com.agentscope.brain.runtime.internal.application;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Claims one ordinary WAITING dispatch under C0. Model I/O must happen outside this transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrdinaryRunClaim {
    public record Lease(String dispatchId, String runId, String sessionId, String userId, String employeeId,
            String releaseId, String stateSlot, String sdkCallId, String claimToken, String claimFence, String bootEpoch) { }

    private final JdbcTemplate jdbc;

    public OrdinaryRunClaim(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Lease claimNext(LocalDateTime now, String executorRef) {
        var capacity = jdbc.queryForList("SELECT * FROM runtime_capacity WHERE singleton_key=1 FOR UPDATE");
        if (capacity.size() != 1) return null;
        var c = capacity.get(0);
        int occupied = ((Number) c.get("occupied_count")).intValue();
        int limit = ((Number) c.get("foreground_limit")).intValue();
        if (occupied >= limit) return null;
        long epoch = ((Number) c.get("boot_epoch")).longValue();
        long rowVersion = ((Number) c.get("row_version")).longValue();
        var waiting = jdbc.queryForList("""
                SELECT d.*,s.state_session_key FROM runtime_dispatch d
                JOIN business_session s ON s.id=d.session_id AND s.user_id=d.user_id
                JOIN business_run r ON r.id=d.run_id AND r.session_id=d.session_id AND r.status='ACCEPTED'
                WHERE d.status='WAITING' AND d.permit_state='NONE' AND d.counts_toward_waiting=1
                  AND d.dispatch_kind='ORDINARY' AND d.work_kind='ORIGINAL_RUN' AND d.boot_epoch=?
                ORDER BY d.id ASC LIMIT 1 FOR UPDATE
                """, epoch);
        if (waiting.isEmpty()) return null;
        var d = waiting.get(0);
        if (jdbc.update("""
                UPDATE runtime_capacity SET occupied_count=occupied_count+1,normal_waiting_count=normal_waiting_count-1,
                  row_version=row_version+1,gmt_modified=?
                WHERE id=? AND row_version=? AND boot_epoch=? AND occupied_count<? AND normal_waiting_count>0
                """, now, c.get("id"), rowVersion, epoch, limit) != 1) {
            return null;
        }
        byte[] token = uuidBytes();
        long fence = ((Number) d.get("claim_fence")).longValue() + 1;
        if (jdbc.update("""
                UPDATE runtime_dispatch SET status='CLAIMED',permit_state='HELD',counts_toward_waiting=0,claim_token=?,
                  claimed_at=?,claim_fence=?,row_version=row_version+1,gmt_modified=?
                WHERE id=? AND status='WAITING' AND permit_state='NONE' AND counts_toward_waiting=1 AND boot_epoch=?
                """, token, now, fence, now, d.get("id"), epoch) != 1) {
            throw new IllegalStateException("DISPATCH_CLAIM_LOST");
        }
        if (jdbc.update("""
                UPDATE business_run SET status='RUNNING',started_at=?,row_version=row_version+1,gmt_modified=?
                WHERE id=? AND session_id=? AND status='ACCEPTED'
                """, now, now, d.get("run_id"), d.get("session_id")) != 1) {
            throw new IllegalStateException("RUN_CLAIM_LOST");
        }
        byte[] callKey = uuidBytes();
        byte[] inputHash = sha(callKey);
        String callId = insert("""
                INSERT INTO runtime_sdk_call(sdk_call_key,run_id,session_id,dispatch_id,call_no,user_id,employee_id,employee_release_id,
                  state_session_key,state_generation,sdk_version,boot_epoch,claim_fence,executor_ref,started_at,call_kind,input_sha256,
                  status,store_version_capability,before_exists,save_status,readback_status,gmt_create,gmt_modified)
                VALUES(?,?,?,?,1,?,?,?,?,1,'2.0.4',?,?,?,?,'ORDINARY',?,'STARTED','UNAVAILABLE',0,'NONE','NONE',?,?)
                """, callKey, d.get("run_id"), d.get("session_id"), d.get("id"), d.get("user_id"), d.get("employee_id"),
                d.get("employee_release_id"), d.get("state_session_key"), epoch, fence, executorRef, now, inputHash, now, now);
        jdbc.update("UPDATE runtime_dispatch SET sdk_call_id=?,gmt_modified=? WHERE id=?", callId, now, d.get("id"));
        return new Lease(String.valueOf(d.get("id")), String.valueOf(d.get("run_id")), String.valueOf(d.get("session_id")),
                String.valueOf(d.get("user_id")), String.valueOf(d.get("employee_id")), String.valueOf(d.get("employee_release_id")),
                String.valueOf(d.get("state_session_key")), callId, uuid(token), String.valueOf(fence), String.valueOf(epoch));
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

    private static byte[] uuidBytes() {
        var id = UUID.randomUUID();
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static String uuid(byte[] value) {
        var b = ByteBuffer.wrap(value);
        return new UUID(b.getLong(), b.getLong()).toString();
    }

    private static byte[] sha(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
}
