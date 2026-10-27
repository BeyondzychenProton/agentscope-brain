package com.agentscope.brain.projection.api;

import java.time.LocalDateTime;

/** Same transaction, after the caller allocated seq while holding the Session lock. */
public interface AcceptedTextProjection {
    void append(Fact fact);
    record Fact(String userId,String employeeId,String sessionId,String releaseId,String title,String sessionVersion,
        String streamId,String seq,String previousSeq,String messageId,String messageNo,String blockId,
        String runId,String text,LocalDateTime now) { }
}
