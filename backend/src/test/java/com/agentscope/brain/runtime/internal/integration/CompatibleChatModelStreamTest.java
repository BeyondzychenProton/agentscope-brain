package com.agentscope.brain.runtime.internal.integration;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CompatibleChatModelStreamTest {
    @Test void parsesTokenDeltasAndToolCalls() throws Exception {
        String sse = ""
                + "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"room.search\",\"arguments\":\"{\\\"floor\\\"\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"18\\\"}\"}}]}}]}\n\n"
                + "data: [DONE]\n\n";
        List<String> seen = new ArrayList<>();
        CompatibleChatModelCaller.ChatTurn turn = CompatibleChatModelCaller.parseStreamLines(sse, seen::add);
        assertThat(seen).containsExactly("你", "你好");
        assertThat(turn.content()).isEqualTo("你好");
        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().get(0).name()).isEqualTo("room.search");
        assertThat(turn.toolCalls().get(0).argumentsJson()).isEqualTo("{\"floor\":\"18\"}");
    }
}
