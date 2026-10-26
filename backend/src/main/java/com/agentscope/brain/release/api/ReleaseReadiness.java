package com.agentscope.brain.release.api;

/** Startup-only read validation, with no model, tool or object storage requests. */
public interface ReleaseReadiness {
    void verify(String userId);
    final class InvalidRelease extends RuntimeException {
        public InvalidRelease(String code) { super(code); }
    }
}
