package com.agentscope.brain.conversation.api;

import com.agentscope.brain.media.api.MediaKind;

/** Ordered formal message content: text or a formal media reference. */
public sealed interface FormalContentBlock permits FormalContentBlock.Text, FormalContentBlock.Media {
    record Text(String text) implements FormalContentBlock {
        public Text {
            if (text == null) {
                throw new IllegalArgumentException("text");
            }
        }
    }

    record Media(String mediaId, MediaKind kind) implements FormalContentBlock {
        public Media {
            if (mediaId == null || mediaId.isBlank() || kind == null) {
                throw new IllegalArgumentException("media");
            }
        }
    }
}
