package com.agentscope.brain.projection.api;

import java.time.LocalDateTime;

/** Short-transaction streaming preview writes for formal assistant replies (#43). */
public interface StreamingReplyProjection {
    Handle publishCumulative(Context context, String cumulativeText, LocalDateTime now);

    void close(Context context, String contentId, String closeReason, String replacementMessageId, LocalDateTime now);

    record Context(String userId, String sessionId, String runId, String sdkCallId) { }

    record Handle(String contentId, String contentVersion) { }
}
