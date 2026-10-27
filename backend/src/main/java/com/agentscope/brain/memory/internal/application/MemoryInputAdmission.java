package com.agentscope.brain.memory.internal.application;

import com.agentscope.brain.memory.api.InputAdmission;
import java.math.BigInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryInputAdmission implements InputAdmission {
    private final JdbcTemplate jdbc;
    public MemoryInputAdmission(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot allocate(String userId, String employeeId, java.time.LocalDateTime now) {
        var rows = jdbc.queryForList("SELECT * FROM memory_profile WHERE user_id=? AND employee_id=? FOR UPDATE",userId,employeeId);
        if (rows.size()!=1 || !"READY".equals(rows.get(0).get("profile_status"))) {
            throw new IllegalStateException("MEMORY_ADMISSION_UNAVAILABLE");
        }
        var p = rows.get(0);
        String next = new BigInteger(String.valueOf(p.get("last_input_sequence"))).add(BigInteger.ONE).toString();
        if (jdbc.update("UPDATE memory_profile SET last_input_sequence=?,gmt_modified=? WHERE id=? AND last_input_sequence=?",
                next,now,p.get("id"),p.get("last_input_sequence"))!=1) { throw new IllegalStateException("MEMORY_ADMISSION_UNAVAILABLE"); }
        return new Snapshot(next,String.valueOf(p.get("generation")),String.valueOf(p.get("document_revision")),String.valueOf(p.get("policy_version")));
    }
}
