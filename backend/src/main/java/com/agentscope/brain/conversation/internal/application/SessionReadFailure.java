package com.agentscope.brain.conversation.internal.application;

public class SessionReadFailure extends RuntimeException {
    private final int status;
    public SessionReadFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
