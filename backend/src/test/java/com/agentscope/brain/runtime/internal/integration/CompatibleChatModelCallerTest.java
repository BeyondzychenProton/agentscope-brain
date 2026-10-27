package com.agentscope.brain.runtime.internal.integration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CompatibleChatModelCallerTest {
    @Test void mapsProviderArrearageCode() {
        assertThat(CompatibleChatModelCaller.modelFailureCode(400,
                "{\"error\":{\"message\":\"Access denied\",\"type\":\"Arrearage\",\"code\":\"Arrearage\"}}"))
                .isEqualTo("MODEL_Arrearage");
    }

    @Test void fallsBackToHttpStatus() {
        assertThat(CompatibleChatModelCaller.modelFailureCode(502, "bad gateway")).isEqualTo("MODEL_HTTP_502");
    }
}
