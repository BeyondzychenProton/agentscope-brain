package com.agentscope.brain.conversation.api;

import java.util.List;

/**
 * Narrow conversation facts for working-context Runs: recovery status and formal assistant binding.
 * Callers already supply accepted formal messages into the runtime use case.
 */
public interface FormalConversationPort {
    SessionRecoveryStatus recoveryStatus(String sessionId);

    boolean acceptsNewRun(String sessionId);

    void markReconciling(String sessionId, String reason);

    void markReady(String sessionId);

    FormalMessage acceptAssistantMessage(String sessionId, String ownerUserId, List<FormalContentBlock> blocks);
}
