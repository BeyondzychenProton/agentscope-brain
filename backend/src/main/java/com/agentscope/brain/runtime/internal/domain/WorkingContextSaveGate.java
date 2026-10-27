package com.agentscope.brain.runtime.internal.domain;

import com.agentscope.brain.media.api.FormalMediaPort;
import com.agentscope.brain.media.api.MediaUnavailableException;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.state.AgentState;
import java.util.List;

/** Rejects working-context writes that still carry binaries, http(s), or unbound media. */
public final class WorkingContextSaveGate {
    private final FormalMediaPort media;

    public WorkingContextSaveGate(FormalMediaPort media) {
        this.media = media;
    }

    public boolean accepts(String userId, String sessionId, AgentState candidate) {
        if (candidate == null || candidate.getContext() == null) {
            return false;
        }
        for (Msg msg : candidate.getContext()) {
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                if (!acceptsBlock(userId, sessionId, block)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean acceptsBlock(String userId, String sessionId, ContentBlock block) {
        if (block instanceof DataBlock data) {
            return acceptsImageSource(userId, sessionId, data.getId(), data.getSource());
        }
        if (block instanceof ImageBlock image) {
            return acceptsImageSource(userId, sessionId, null, image.getSource());
        }
        return true;
    }

    private boolean acceptsImageSource(String userId, String sessionId, String mediaId, Source source) {
        if (source == null) {
            return false;
        }
        if (source instanceof Base64Source) {
            return false;
        }
        if (!(source instanceof URLSource urlSource)) {
            return false;
        }
        String url = urlSource.getUrl();
        if (url == null || url.isBlank() || FormalMediaLocator.isHttpOrPresigned(url)) {
            return false;
        }
        String fromLocator = FormalMediaLocator.mediaIdFrom(url);
        if (fromLocator == null) {
            return false;
        }
        if (mediaId != null && !mediaId.isBlank() && !fromLocator.equals(mediaId)) {
            return false;
        }
        String effectiveId = mediaId == null || mediaId.isBlank() ? fromLocator : mediaId;
        try {
            media.requireSessionOwned(userId, sessionId, effectiveId);
            return true;
        } catch (MediaUnavailableException ex) {
            return false;
        }
    }

    public static List<Msg> contextOf(AgentState state) {
        return state == null || state.getContext() == null ? List.of() : List.copyOf(state.getContext());
    }
}
