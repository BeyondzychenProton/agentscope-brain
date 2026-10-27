package com.agentscope.brain.runtime.api;

import java.time.LocalDateTime;

/** Intake participation, not an execution/claim API. The caller acquires C0 before all domain locks. */
public interface OrdinaryDispatch {
    Capacity lockCapacity();
    void enqueue(Capacity capacity, Work work);
    record Capacity(String id, String bootEpoch, String rowVersion) { }
    record Work(String userId,String employeeId,String releaseId,String sessionId,String runId,String submissionId,
            byte[] requestKey,byte[] requestSha256,LocalDateTime now) { }
    class Denied extends RuntimeException {
        private final int status;
        public Denied(int status,String code) { super(code);this.status=status; }
        public int status() { return status; }
    }
}
