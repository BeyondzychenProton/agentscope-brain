package com.agentscope.brain.projection.internal.application;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProcessStepSecurityTest {
    @Test void rejectsInlineDetailOver8KiB() {
        byte[] body = new byte[ProcessStepSecurity.MAX_INLINE_UTF8_BYTES + 1];
        Arrays.fill(body, (byte) 'a');
        assertThatThrownBy(() -> ProcessStepSecurity.validateInlineDetail(body))
                .isInstanceOf(ProcessStepSecurity.Rejected.class);
    }

    @Test void acceptsInlineDetailAt8KiB() {
        byte[] body = new byte[ProcessStepSecurity.MAX_INLINE_UTF8_BYTES];
        Arrays.fill(body, (byte) 'a');
        assertThatCode(() -> ProcessStepSecurity.validateInlineDetail(body)).doesNotThrowAnyException();
    }

    @Test void rejectsSensitiveKeys() {
        assertThatThrownBy(() -> ProcessStepSecurity.rejectSensitiveKeys(Map.of("confirmationToken", "x")))
                .isInstanceOf(ProcessStepSecurity.Rejected.class);
    }

    @Test void acceptsCleanMap() {
        assertThatCode(() -> ProcessStepSecurity.rejectSensitiveKeys(Map.of("toolName", "search", "status", "ok")))
                .doesNotThrowAnyException();
    }

    @Test void redactDisplayTextMasksBearerTokenStyleSubstring() {
        String redacted = ProcessStepSecurity.redactDisplayText("Authorization: Bearer abc.def.ghi continue");
        assertThat(redacted).doesNotContain("abc.def.ghi");
        assertThat(redacted).contains("Bearer ");
        assertThat(redacted).contains("[REDACTED]");
    }
}
