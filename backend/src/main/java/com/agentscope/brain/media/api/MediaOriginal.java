package com.agentscope.brain.media.api;

import java.util.Arrays;

/** Verified original bytes for one formal media id. */
public record MediaOriginal(String mediaId, String mimeType, byte[] bytes, String contentSha256Hex) {
    public MediaOriginal {
        if (mediaId == null || mediaId.isBlank()
                || mimeType == null || mimeType.isBlank()
                || bytes == null
                || contentSha256Hex == null || contentSha256Hex.isBlank()) {
            throw new IllegalArgumentException("media original");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
