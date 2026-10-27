package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.runtime.api.OrdinaryDispatch;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrdinaryQueueAdmission implements OrdinaryDispatch {
    private final JdbcTemplate jdbc;
    public OrdinaryQueueAdmission(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Capacity lockCapacity() {
        var rows=jdbc.queryForList("SELECT * FROM runtime_capacity WHERE singleton_key=1 FOR UPDATE");
        if(rows.size()!=1) { throw new Denied(503,"EXECUTION_ADMISSION_UNAVAILABLE"); }
        var p=rows.get(0);
        if (((Number)p.get("foreground_limit")).intValue()!=2 || ((Number)p.get("normal_waiting_limit")).intValue()!=16) {
            throw new Denied(503,"EXECUTION_ADMISSION_UNAVAILABLE");
        }
        long counted=jdbc.queryForObject("SELECT COUNT(*) FROM runtime_dispatch WHERE counts_toward_waiting=1",Long.class);
        long held=jdbc.queryForObject("SELECT COUNT(*) FROM runtime_dispatch WHERE permit_state='HELD'",Long.class);
        if(counted!=((Number)p.get("normal_waiting_count")).longValue() || held!=((Number)p.get("occupied_count")).longValue()) {
            throw new Denied(503,"EXECUTION_ADMISSION_UNAVAILABLE");
        }
        return new Capacity(String.valueOf(p.get("id")),String.valueOf(p.get("boot_epoch")),String.valueOf(p.get("row_version")));
    }
    @Override public void enqueue(Capacity c,Work w) {
        if(jdbc.update("UPDATE runtime_capacity SET normal_waiting_count=normal_waiting_count+1,row_version=row_version+1,gmt_modified=? WHERE id=? AND row_version=? AND boot_epoch=? AND normal_waiting_count<normal_waiting_limit",
                w.now(),c.id(),c.rowVersion(),c.bootEpoch())!=1) { throw new Denied(503,"EXECUTION_CAPACITY_EXCEEDED"); }
        var uuid=UUID.randomUUID();
        var key=ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
        jdbc.update("""
                INSERT INTO runtime_dispatch(dispatch_key,user_id,employee_id,session_id,run_id,employee_release_id,
                    dispatch_kind,work_kind,source_kind,source_id,source_version,request_key,request_sha256,status,
                    permit_state,counts_toward_waiting,boot_epoch,gmt_create,gmt_modified)
                VALUES(?,?,?,?,?,?,'ORDINARY','ORIGINAL_RUN','ORDINARY_SUBMISSION',?,1,?,?,'WAITING','NONE',1,?,?,?)
                """,key,w.userId(),w.employeeId(),w.sessionId(),w.runId(),w.releaseId(),w.submissionId(),w.requestKey(),w.requestSha256(),c.bootEpoch(),w.now(),w.now());
    }
}
