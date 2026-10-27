package com.agentscope.brain.projection.api;

import java.time.LocalDateTime;
import java.util.List;

/** Same transaction, after the caller allocated seq while holding the Session lock. */
public interface AcceptedTextProjection {
    void append(Fact fact);
    record Fact(String userId,String employeeId,String sessionId,String releaseId,String title,String sessionVersion,
        String streamId,String seq,String previousSeq,String messageId,String messageNo,String blockId,
        String runId,String text,LocalDateTime now,String senderKind,String runStatus,
        List<String> replacesContentIds) {
        public Fact {
            replacesContentIds = replacesContentIds == null ? List.of() : List.copyOf(replacesContentIds);
        }
        public Fact(String userId,String employeeId,String sessionId,String releaseId,String title,String sessionVersion,
                String streamId,String seq,String previousSeq,String messageId,String messageNo,String blockId,
                String runId,String text,LocalDateTime now,String senderKind,String runStatus) {
            this(userId, employeeId, sessionId, releaseId, title, sessionVersion, streamId, seq, previousSeq,
                    messageId, messageNo, blockId, runId, text, now, senderKind, runStatus, List.of());
        }
    }
}
