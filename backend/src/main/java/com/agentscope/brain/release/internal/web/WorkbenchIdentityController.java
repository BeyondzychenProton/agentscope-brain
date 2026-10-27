package com.agentscope.brain.release.internal.web;

import com.agentscope.brain.identity.api.CurrentUser;
import com.agentscope.brain.release.api.EmployeeAccess;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
class WorkbenchIdentityController {
    private final CurrentUser users;
    private final EmployeeAccess employees;
    WorkbenchIdentityController(CurrentUser users, EmployeeAccess employees) { this.users = users; this.employees = employees; }
    record Me(String authStage, String userId, String displayName, Instant authenticatedAt, Instant absoluteExpiresAt,
            String maskedPhone, List<EmployeeAccess.Employee> employees) { }
    @GetMapping("/api/me")
    Me me() {
        var user = users.require();
        return new Me(user.passwordChangeRequired() ? "PASSWORD_CHANGE_REQUIRED" : "READY", user.userId(), user.displayName(),
                user.authenticatedAt(), user.absoluteExpiresAt(), user.maskedPhone(),
                user.passwordChangeRequired() ? List.of() : employees.employees(user.userId()));
    }
    @GetMapping("/api/employees/{employeeId}/capabilities")
    EmployeeAccess.Capability capability(@PathVariable String employeeId) {
        var user = users.require();
        if (user.passwordChangeRequired()) { throw new EmployeeAccess.Denied(403, "PASSWORD_CHANGE_REQUIRED"); }
        return employees.capability(user.userId(), employeeId);
    }
}
