package com.agentscope.brain.conversation.api;

import java.util.List;

/** Already-accepted formal user or assistant message supplied to a Run. */
public record FormalMessage(
        String messageId,
        String sessionId,
        String ownerUserId,
        FormalSender sender,
        List<FormalContentBlock> blocks) {
    public FormalMessage {
        if (messageId == null || messageId.isBlank()
                || sessionId == null || sessionId.isBlank()
                || ownerUserId == null || ownerUserId.isBlank()
                || sender == null
                || blocks == null) {
            throw new IllegalArgumentException("formal message");
        }
        blocks = List.copyOf(blocks);
    }
}
