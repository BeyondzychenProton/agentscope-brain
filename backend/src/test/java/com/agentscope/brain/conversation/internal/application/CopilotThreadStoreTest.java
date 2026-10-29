package com.agentscope.brain.conversation.internal.application;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CopilotThreadStoreTest {
    @Test void keepsTheBrowsersOwnMessageId() {
        String id = UUID.randomUUID().toString();
        assertThat(CopilotThreadStore.userMessageId(id)).isEqualTo(id);
    }

    @Test void replacesIdsThatWouldNotMatchTheBrowserCopy() {
        String upper = UUID.randomUUID().toString().toUpperCase();
        assertThat(CopilotThreadStore.userMessageId(upper)).isNotEqualTo(upper).isNotBlank();
        assertThat(CopilotThreadStore.userMessageId("msg-123")).isNotEqualTo("msg-123").isNotBlank();
        assertThat(CopilotThreadStore.userMessageId(null)).isNotBlank();
        assertThat(CopilotThreadStore.userMessageId("  ")).isNotBlank();
    }
}
