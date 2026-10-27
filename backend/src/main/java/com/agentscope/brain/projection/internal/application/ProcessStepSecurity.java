package com.agentscope.brain.projection.internal.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ProcessStepSecurity {
    public static final int MAX_INLINE_UTF8_BYTES = 8192;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "confirmationToken", "systemPrompt", "rawThinking", "accessToken",
            "password", "authorization", "apiKey", "secretKey");

    private static final Set<String> SENSITIVE_KEY_VARIANTS = Set.of(
            "confirmationtoken", "confirmation_token", "systemprompt", "system_prompt",
            "rawthinking", "raw_thinking", "accesstoken", "access_token",
            "authorization", "apikey", "api_key", "secretkey", "secret_key", "password");

    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)\\S+");
    private static final Pattern SK = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]+");
    private static final Pattern CONFIRMATION_TOKEN =
            Pattern.compile("(?i)(confirmationToken\\s*[:=]\\s*)\\S+");

    private ProcessStepSecurity() {}

    public static final class Rejected extends RuntimeException {
        public Rejected(String message) { super(message); }
    }

    /** Reject if UTF-8 byte length > 8192 */
    public static void validateInlineDetail(byte[] utf8Body) {
        if (utf8Body == null || utf8Body.length > MAX_INLINE_UTF8_BYTES) {
            throw new Rejected("INLINE_DETAIL_TOO_LARGE");
        }
    }

    /** Reject if map contains sensitive keys (case-sensitive exact + common variants) */
    public static void rejectSensitiveKeys(Map<String, ?> payload) {
        if (payload == null) {
            return;
        }
        for (String key : payload.keySet()) {
            if (key == null) {
                continue;
            }
            if (SENSITIVE_KEYS.contains(key)) {
                throw new Rejected("SENSITIVE_KEY:" + key);
            }
            String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
            if (SENSITIVE_KEY_VARIANTS.contains(normalized)
                    || SENSITIVE_KEY_VARIANTS.contains(normalized.replace("_", ""))) {
                throw new Rejected("SENSITIVE_KEY:" + key);
            }
        }
    }

    /**
     * Return a display-safe string: replace obvious secret-looking substrings with placeholders.
     */
    public static String redactDisplayText(String input) {
        if (input == null) {
            return null;
        }
        String out = BEARER.matcher(input).replaceAll("$1[REDACTED]");
        out = SK.matcher(out).replaceAll("[REDACTED]");
        out = CONFIRMATION_TOKEN.matcher(out).replaceAll("$1[REDACTED]");
        return out;
    }
}
