package com.agentscope.brain.memory.api;

/** Snapshot and input sequence allocation only; does not initialize or read/write a memory document. */
public interface InputAdmission {
    Snapshot allocate(String userId, String employeeId, java.time.LocalDateTime now);
    record Snapshot(String inputSequence, String generation, String documentRevision, String policyVersion) { }
}
