package com.agentscope.brain.release.api;

/** Caller holds the current identity lock; participates in the same short platform transaction. */
public interface SubmissionRelease {
    boolean canSubmit(String userId, String employeeId, String fixedReleaseId);
    Snapshot lock(String userId, String employeeId, String fixedReleaseId, boolean requireCurrent);
    record Snapshot(String employeeId, String releaseId, String imagePolicyJson, byte[] imagePolicySha256) { }
}
