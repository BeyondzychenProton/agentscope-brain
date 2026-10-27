package com.agentscope.brain.release.internal.application;

import com.agentscope.brain.release.api.EmployeeAccess;
import com.agentscope.brain.identity.api.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CurrentEmployeeAccess implements EmployeeAccess {
    private final JdbcTemplate jdbc;
    private final CurrentUser users;
    private final ObjectMapper json = new ObjectMapper();
    public CurrentEmployeeAccess(JdbcTemplate jdbc, CurrentUser users) { this.jdbc = jdbc; this.users = users; }
    @Override public List<Employee> employees(String userId) {
        return jdbc.queryForList("""
                SELECT CAST(e.id AS CHAR) employee_id,e.display_name FROM digital_employee e
                WHERE EXISTS (SELECT 1 FROM employee_use_grant g WHERE g.employee_id=e.id AND g.user_id=?)
                   OR EXISTS (SELECT 1 FROM business_session s WHERE s.employee_id=e.id AND s.user_id=?)
                ORDER BY e.id
                """, userId, userId).stream().map(row -> {
            var cap = capability(userId, String.valueOf(row.get("employee_id")));
            return new Employee(cap.employeeId(), String.valueOf(row.get("display_name")),
                    cap.allowedActions().contains("START_RUN"), cap.unavailableReason() != null, cap.unavailableReason(), cap);
        }).toList();
    }
    @Override public Capability capability(String userId, String employeeId) {
        var rows = jdbc.queryForList("""
                SELECT CAST(e.id AS CHAR) employee_id,CAST(r.id AS CHAR) release_id,r.availability_revision,
                       r.availability_status,r.config_json,g.grant_status
                FROM digital_employee e LEFT JOIN employee_release r ON r.id=e.current_release_id AND r.employee_id=e.id
                LEFT JOIN employee_use_grant g ON g.employee_id=e.id AND g.user_id=?
                WHERE e.id=? AND (g.id IS NOT NULL OR EXISTS
                    (SELECT 1 FROM business_session s WHERE s.employee_id=e.id AND s.user_id=?))
                """, userId, employeeId, userId);
        if (rows.size() != 1) { throw new Denied(404, "NOT_FOUND"); }
        var row = rows.get(0);
        if (row.get("release_id") == null) { throw new Denied(503, "CAPABILITY_UNAVAILABLE"); }
        String reason = !"ACTIVE".equals(row.get("grant_status")) ? "EMPLOYEE_USE_REVOKED"
                : !"AVAILABLE".equals(row.get("availability_status")) ? "RELEASE_DISABLED" : null;
        try {
            var config = json.readValue(String.valueOf(row.get("config_json")), new TypeReference<Map<String, Object>>() { });
            var limits = Map.<String, Object>of();
            boolean images = false;
            if (config.get("images") instanceof Map<?, ?> policy) {
                limits = Map.of("allowedMimeTypes", policy.get("mimeTypes"), "maxImageCount", policy.get("maxCount"),
                        "maxBytesPerImage", policy.get("maxBytesPerImage"), "maxPixelsPerImage", policy.get("maxPixelsPerImage"));
                images = true;
            }
            return new Capability(employeeId, "release:" + row.get("release_id") + ":" + row.get("availability_revision"),
                    true, images, limits, reason == null ? List.of("START_RUN") : List.of(), reason);
        } catch (Exception invalid) { throw new Denied(503, "CAPABILITY_UNAVAILABLE"); }
    }
    @Override public void requireExecution(String employeeId) {
        var user = users.require();
        if (user.passwordChangeRequired()) { throw new Denied(403, "PASSWORD_CHANGE_REQUIRED"); }
        if (!capability(user.userId(), employeeId).allowedActions().contains("START_RUN")) {
            throw new Denied(403, "CONTINUATION_FORBIDDEN");
        }
    }
}
