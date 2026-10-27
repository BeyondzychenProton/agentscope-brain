package com.agentscope.brain.media.api;

/** Formal media cannot be used for this Run (ownership, locator, digest, or missing object). */
public final class MediaUnavailableException extends RuntimeException {
    public MediaUnavailableException(String message) {
        super(message);
    }
}
