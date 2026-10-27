package com.agentscope.brain.media.api;

/** Formal media created after object verify and message binding. */
public record BoundFormalMedia(String mediaId, String messageId, MediaKind kind, String mimeType) {
    public BoundFormalMedia {
        if (mediaId == null || mediaId.isBlank()
                || messageId == null || messageId.isBlank()
                || kind == null
                || mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("bound formal media");
        }
    }
}
