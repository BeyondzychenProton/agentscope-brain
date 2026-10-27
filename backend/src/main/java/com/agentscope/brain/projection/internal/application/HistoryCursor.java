package com.agentscope.brain.projection.internal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Signed positions never grant access. Every read independently checks current identity and ownership. */
@Component
public class HistoryCursor {
    public record Position(String purpose, String userId, String sessionId, String streamId,
            String watermark, String upper, String before) { }
    private final byte[] key;
    private final ObjectMapper json = new ObjectMapper();
    public HistoryCursor(@Value("${brain.projection.cursor-key:}") String configured) {
        key = configured.isBlank() ? new byte[32] : Base64.getDecoder().decode(configured);
        if (configured.isBlank()) { new SecureRandom().nextBytes(key); }
        if (key.length < 32) { throw new IllegalArgumentException("Projection cursor key must contain at least 32 bytes"); }
    }
    public String encode(Position value) {
        try {
            var payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(value));
            return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        } catch (Exception invalid) { throw new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE"); }
    }
    public Position decode(String cursor) {
        try {
            if (cursor == null || cursor.length() > 2048) { throw new IllegalArgumentException(); }
            var parts = cursor.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            return json.readValue(Base64.getUrlDecoder().decode(parts[0]), Position.class);
        } catch (Exception invalid) { throw new ProjectionReadFailure(409, "CURSOR_INVALID"); }
    }
    private byte[] sign(String value) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    }
}
