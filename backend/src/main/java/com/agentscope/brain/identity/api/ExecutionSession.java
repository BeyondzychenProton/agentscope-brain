package com.agentscope.brain.identity.api;

/** Captures the authenticated server session for periodic validation during an asynchronous run. */
public interface ExecutionSession {
    Lease capture();
    interface Lease {
        byte[] sessionHash();
        boolean valid();
    }
}
