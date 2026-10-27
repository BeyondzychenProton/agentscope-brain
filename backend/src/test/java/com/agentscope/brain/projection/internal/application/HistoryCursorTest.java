package com.agentscope.brain.projection.internal.application;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoryCursorTest {
    @Test void signedPositionsBindAllReadBoundariesAndSurviveRestartWithStableKey() {
        var key = Base64.getEncoder().encodeToString(new byte[32]);
        var cursor = new HistoryCursor(key);
        var p = new HistoryCursor.Position("messages", "1", "9007199254740993", "stream", "5", "8", "3");
        var encoded = cursor.encode(p);
        assertThat(new HistoryCursor(key).decode(encoded)).isEqualTo(p);
        var parts = encoded.split("\\.");
        var forged = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"userId\":\"2\"}".getBytes()) + "." + parts[1];
        assertThatThrownBy(() -> cursor.decode(forged)).isInstanceOf(ProjectionReadFailure.class);
        assertThatThrownBy(() -> cursor.decode("x".repeat(2049))).isInstanceOf(ProjectionReadFailure.class);
    }
}
