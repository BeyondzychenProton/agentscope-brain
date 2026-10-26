package com.agentscope.brain.identity.internal.domain;

public final class AuthFailure extends RuntimeException {
    private final int status;
    public AuthFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
