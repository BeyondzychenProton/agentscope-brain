package com.agentscope.brain.runtime.internal.domain;

/**
 * Stable non-http(s) locator for a formal media id in saved working context.
 * The string form is an implementation detail; it must round-trip with {@link #mediaIdFrom(String)}.
 */
public final class FormalMediaLocator {
    private static final String PREFIX = "brain-media:";

    private FormalMediaLocator() {
    }

    public static String forMediaId(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("mediaId");
        }
        return PREFIX + mediaId;
    }

    public static boolean matches(String locator, String mediaId) {
        return mediaId != null && mediaId.equals(mediaIdFrom(locator));
    }

    public static String mediaIdFrom(String locator) {
        if (locator == null || !locator.startsWith(PREFIX)) {
            return null;
        }
        String id = locator.substring(PREFIX.length());
        return id.isBlank() ? null : id;
    }

    public static boolean isHttpOrPresigned(String locator) {
        if (locator == null || locator.isBlank()) {
            return false;
        }
        String lower = locator.toLowerCase();
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.contains("x-amz-signature")
                || lower.contains("x-oss-signature")
                || lower.contains("sig=");
    }
}
