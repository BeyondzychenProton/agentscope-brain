package com.agentscope.brain.runtime.internal.domain;

import com.agentscope.brain.conversation.api.FormalMessage;
import com.agentscope.brain.media.api.FormalMediaPort;
import com.agentscope.brain.media.api.MediaUnavailableException;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Aligns prior working context with formal dialogue before the next Run. */
public final class WorkingContextAligner {
    private final FormalDialogueAssembler assembler = new FormalDialogueAssembler();
    private final FormalMediaPort media;

    public WorkingContextAligner(FormalMediaPort media) {
        this.media = media;
    }

    public AgentState alignOrRebuild(
            String userId,
            String sessionId,
            String stateSlot,
            AgentState priorOrNull,
            List<FormalMessage> formalMessages) {
        requireOwnedMedia(userId, sessionId, formalMessages);
        if (priorOrNull == null || priorOrNull.getContext() == null) {
            return rebuild(userId, stateSlot, formalMessages);
        }
        return appendMissing(userId, stateSlot, priorOrNull, formalMessages);
    }

    public AgentState rebuild(String userId, String stateSlot, List<FormalMessage> formalMessages) {
        return AgentState.builder()
                .userId(userId)
                .sessionId(stateSlot)
                .context(assembler.assemble(formalMessages))
                .build();
    }

    private AgentState appendMissing(
            String userId, String stateSlot, AgentState prior, List<FormalMessage> formalMessages) {
        Set<String> present = new HashSet<>();
        List<Msg> merged = new ArrayList<>();
        for (Msg msg : prior.getContext()) {
            merged.add(msg);
            if (msg.getId() != null) {
                present.add(msg.getId());
            }
        }
        for (FormalMessage formal : formalMessages) {
            if (!present.contains(formal.messageId())) {
                merged.add(assembler.toMsg(formal));
            }
        }
        return AgentState.builder()
                .userId(userId)
                .sessionId(stateSlot)
                .summary(prior.getSummary())
                .context(merged)
                .replyId(prior.getReplyId())
                .curIter(prior.getCurIter())
                .build();
    }

    private void requireOwnedMedia(String userId, String sessionId, List<FormalMessage> formalMessages) {
        for (FormalMessage message : formalMessages) {
            for (var block : message.blocks()) {
                if (block instanceof com.agentscope.brain.conversation.api.FormalContentBlock.Media mediaBlock
                        && mediaBlock.kind() == com.agentscope.brain.media.api.MediaKind.IMAGE) {
                    try {
                        media.requireSessionOwned(userId, sessionId, mediaBlock.mediaId());
                    } catch (MediaUnavailableException ex) {
                        throw ex;
                    }
                }
            }
        }
        for (FormalMessage message : formalMessages) {
            Msg assembled = assembler.toMsg(message);
            for (ContentBlock block : assembled.getContent()) {
                if (block instanceof DataBlock data
                        && data.getSource() instanceof URLSource url
                        && !FormalMediaLocator.matches(url.getUrl(), data.getId())) {
                    throw new MediaUnavailableException("MEDIA_UNAVAILABLE");
                }
            }
        }
    }
}
