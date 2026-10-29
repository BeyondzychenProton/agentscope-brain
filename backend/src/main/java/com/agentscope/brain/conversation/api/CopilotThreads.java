package com.agentscope.brain.conversation.api;

import java.util.List;
import java.util.Map;

/** Text-chat thread facts, independent of business sessions and formal messages. */
public interface CopilotThreads {
    record Message(String id, String role, String content, String status, String displayJson) {
        public Message(String id, String role, String content, String status) { this(id, role, content, status, null); }
    }
    record Thread(String id, String employeeId, String releaseId, String title, String version,
                  String activeRunId, String unavailableReason, String lastRunState, String failureCode,
                  List<Message> messages) { }
    /** One user answer to a pending interrupt. Payload stays SDK-agnostic here. */
    record Resume(String interruptId, String status, Map<String, Object> payload) { }
    record Accepted(String threadId, String runId, String releaseId, List<Message> history, String userId,
                    List<Resume> resume) { }
    record Summary(String id, String title, String version, String activeRunId, String updatedAt) { }

    /** The caller is authenticated and supplies only the newly submitted text and resume entries. */
    Accepted accept(String threadId, String runId, String employeeId, String text, String userMessageId,
                    byte[] sessionHash, List<Resume> resume);
    Thread open(String threadId);
    /** The most recent run that paused for a user answer, or null. */
    String interruptedRun(String threadId);
    List<Summary> list(String employeeId, String query, int limit, int offset);
    void rename(String threadId, String expectedVersion, String title);
    /** Execution-owner operations; never exposed directly as unauthenticated HTTP endpoints. */
    boolean append(String threadId, String runId, String delta);
    /** Atomically persists the text increment and its structured, recoverable display. */
    boolean recordOutput(String threadId, String runId, String delta, String displayJson);
    boolean heartbeat(String threadId, String runId);
    boolean finish(String threadId, String runId, String state, String failureCode);
}
