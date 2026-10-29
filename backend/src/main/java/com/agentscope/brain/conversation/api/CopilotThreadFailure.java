package com.agentscope.brain.conversation.api;

/** Stable failure returned by the text-thread boundary. */
public final class CopilotThreadFailure extends RuntimeException {
    private final int status;
    public CopilotThreadFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
