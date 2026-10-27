package com.agentscope.brain.conversation.internal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A list continuation is a signed position, never an authorization credential. */
@Component
public class SessionListCursor {
    public record Position(String purpose, String userId, String employeeId, String query,
            String updatedAt, String sessionId) { }
    private final byte[] key;
    private final ObjectMapper json = new ObjectMapper();
    public SessionListCursor(@Value("${brain.conversation.cursor-key:${brain.projection.cursor-key:}}") String configured) {
        key = configured.isBlank() ? new byte[32] : Base64.getDecoder().decode(configured);
        if (configured.isBlank()) { new SecureRandom().nextBytes(key); }
        if (key.length < 32) { throw new IllegalArgumentException("Session cursor key must contain at least 32 bytes"); }
    }
    public String encode(Position position) {
        try {
            var payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(position));
            return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        } catch (Exception unavailable) { throw new SessionReadFailure(503, "SESSION_READ_UNAVAILABLE"); }
    }
    public Position decode(String cursor) {
        try {
            if (cursor == null || cursor.length() > 4096) { throw new IllegalArgumentException(); }
            var parts = cursor.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            var position = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Position.class);
            if (!"session-list-v1".equals(position.purpose())) { throw new IllegalArgumentException(); }
            return position;
        } catch (Exception invalid) { throw new SessionReadFailure(409, "CURSOR_INVALID"); }
    }
    private byte[] sign(String payload) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    }
}
