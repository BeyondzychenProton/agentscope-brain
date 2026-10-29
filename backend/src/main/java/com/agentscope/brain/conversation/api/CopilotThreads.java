package com.agentscope.brain.conversation.api;

import java.util.List;

/** Text-chat thread facts, independent of business sessions and formal messages. */
public interface CopilotThreads {
    record Message(String id, String role, String content, String status, String displayJson) {
        public Message(String id, String role, String content, String status) { this(id, role, content, status, null); }
    }
    record Thread(String id, String employeeId, String releaseId, String title, String version,
                  String activeRunId, String unavailableReason, String lastRunState, String failureCode,
                  List<Message> messages) { }
    record Accepted(String threadId, String runId, String releaseId, List<Message> history, String userId) { }
    record Summary(String id, String title, String version, String activeRunId, String updatedAt) { }

    /** The caller is authenticated and supplies only the newly submitted text. */
    Accepted accept(String threadId, String runId, String employeeId, String text, byte[] sessionHash);
    Thread open(String threadId);
    List<Summary> list(String employeeId, String query, int limit, int offset);
    void rename(String threadId, String expectedVersion, String title);
    /** Execution-owner operations; never exposed directly as unauthenticated HTTP endpoints. */
    boolean append(String threadId, String runId, String delta);
    /** Atomically persists the text increment and its structured, recoverable display. */
    boolean recordOutput(String threadId, String runId, String delta, String displayJson);
    boolean heartbeat(String threadId, String runId);
    boolean finish(String threadId, String runId, String state, String failureCode);
}
