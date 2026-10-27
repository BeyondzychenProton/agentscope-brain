package com.agentscope.brain.runtime.internal.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MeetingMcpClientTest {
    @Test void signsTimestampAndPhoneWithLf() {
        String signature = MeetingMcpClient.sign(1_700_000_000_000L, "13800000000", "secret");
        assertThat(signature).isEqualTo(MeetingMcpClient.sign(1_700_000_000_000L, "13800000000", "secret"));
        assertThat(signature).hasSize(64);
        assertThat(signature).matches("[0-9a-f]{64}");
    }

    @Test void parsesSseDataPayload() throws Exception {
        var node = MeetingMcpClient.parsePayload(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n",
                "text/event-stream",
                new ObjectMapper());
        assertThat(node.path("result").path("ok").asBoolean()).isTrue();
    }
}
