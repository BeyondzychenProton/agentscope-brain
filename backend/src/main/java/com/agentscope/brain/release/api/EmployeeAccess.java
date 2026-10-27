package com.agentscope.brain.release.api;

import java.util.List;
import java.util.Map;

/** userId must come from CurrentUser, never from request input or cached capabilities. */
public interface EmployeeAccess {
    List<Employee> employees(String userId);
    Capability capability(String userId, String employeeId);
    /** Mandatory fresh check for a future write boundary; this does not dispatch or create a Run. */
    void requireExecution(String employeeId);
    record Capability(String employeeId, String capabilityContext, boolean acceptsText, boolean acceptsImages,
            Map<String, Object> imageLimits, List<String> allowedActions, String unavailableReason, boolean textSubmissionAvailable) { }
    record Employee(String employeeId, String displayName, boolean canStartRun, boolean readOnly,
            String unavailableReason, Capability capabilities) { }
    class Denied extends RuntimeException {
        private final int status;
        public Denied(int status, String code) { super(code); this.status = status; }
        public int status() { return status; }
    }
}
