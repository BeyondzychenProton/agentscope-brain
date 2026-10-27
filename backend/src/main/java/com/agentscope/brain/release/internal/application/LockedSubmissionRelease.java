package com.agentscope.brain.release.internal.application;

import com.agentscope.brain.release.api.EmployeeAccess;
import com.agentscope.brain.release.api.SubmissionRelease;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LockedSubmissionRelease implements SubmissionRelease {
    @org.springframework.beans.factory.annotation.Value("${brain.conversation.text-submission-enabled:false}")
    private boolean enabled;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    public LockedSubmissionRelease(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean canSubmit(String userId,String employeeId,String fixedReleaseId) {
        return enabled && jdbc.queryForObject("""
                SELECT COUNT(*) FROM employee_release r JOIN employee_use_grant g ON g.employee_id=r.employee_id
                WHERE r.id=? AND r.employee_id=? AND r.availability_status='AVAILABLE' AND g.user_id=? AND g.grant_status='ACTIVE'
                """, Long.class,fixedReleaseId,employeeId,userId)==1;
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot lock(String userId, String employeeId, String fixedReleaseId, boolean requireCurrent) {
        var employees = jdbc.queryForList("SELECT current_release_id FROM digital_employee WHERE id=? FOR UPDATE", employeeId);
        if (employees.size()!=1) { throw new EmployeeAccess.Denied(404,"NOT_FOUND"); }
        var current = String.valueOf(employees.get(0).get("current_release_id"));
        String releaseId = fixedReleaseId == null ? current : fixedReleaseId;
        if (requireCurrent && !releaseId.equals(current)) { throw new EmployeeAccess.Denied(409,"RELEASE_CHANGED"); }
        var releases = jdbc.queryForList("SELECT config_json,availability_status FROM employee_release WHERE id=? AND employee_id=? FOR UPDATE",
                releaseId,employeeId);
        var grants = jdbc.queryForList("SELECT grant_status FROM employee_use_grant WHERE user_id=? AND employee_id=? FOR UPDATE",userId,employeeId);
        if (grants.size()!=1 || !"ACTIVE".equals(grants.get(0).get("grant_status"))) {
            throw new EmployeeAccess.Denied(403,"CONTINUATION_FORBIDDEN");
        }
        if (releases.size()!=1 || !"AVAILABLE".equals(releases.get(0).get("availability_status"))) {
            throw new EmployeeAccess.Denied(403,"CONTINUATION_FORBIDDEN");
        }
        try {
            var config = json.readTree(String.valueOf(releases.get(0).get("config_json")));
            var images = config.get("images");
            String policy = json.writeValueAsString(images != null && images.isObject() ? images : json.createObjectNode());
            return new Snapshot(employeeId,releaseId,policy,MessageDigest.getInstance("SHA-256").digest(policy.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalid) { throw new EmployeeAccess.Denied(503,"CAPABILITY_UNAVAILABLE"); }
    }
}
