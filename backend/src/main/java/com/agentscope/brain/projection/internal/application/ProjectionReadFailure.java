package com.agentscope.brain.projection.internal.application;

public class ProjectionReadFailure extends RuntimeException {
    private final int status;
    public ProjectionReadFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
