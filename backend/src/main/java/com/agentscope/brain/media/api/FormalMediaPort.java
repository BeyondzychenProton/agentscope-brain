package com.agentscope.brain.media.api;

/**
 * Formal media ownership checks and original reads by media id.
 * No remote URL download surface — model/tool http(s) addresses are never fetched here.
 */
public interface FormalMediaPort {
    /** Confirm the media row belongs to this session and owner without downloading bytes. */
    void requireSessionOwned(String userId, String sessionId, String mediaId);

    /** Ownership, object, and digest verification; returns originals for a model request copy. */
    MediaOriginal readVerified(String userId, String sessionId, String mediaId);

    /**
     * Persist assistant image bytes into private object storage, verify, and bind as formal media
     * under an already-accepted assistant message id.
     */
    BoundFormalMedia persistAssistantImage(
            String userId, String sessionId, String messageId, byte[] bytes, String mimeType);
}
