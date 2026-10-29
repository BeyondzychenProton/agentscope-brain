package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.runtime.internal.integration.CompatibleChatModelCaller;
import com.agentscope.brain.runtime.internal.integration.MeetingMcpClient;
import com.agentscope.brain.runtime.internal.integration.PlatformFacts;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrdinaryToolLoopPlatformFactsTest {
    private static final AgentToolCatalog NO_RELEASE_TOOLS = new AgentToolCatalog(null, "unused") {
        @Override public List<Tool> forRelease(String releaseId) { return List.of(); }
    };

    @Test void answersPlatformFactsWithoutMcpOrPhone() {
        var model = mock(CompatibleChatModelCaller.class);
        var mcp = mock(MeetingMcpClient.class);
        var jdbc = mock(JdbcTemplate.class);
        when(model.complete(anyList(), anyList()))
                .thenReturn(new CompatibleChatModelCaller.ChatTurn("", List.of(
                        new CompatibleChatModelCaller.ToolCall("call-1", "platform.current_time", "{}")), List.of()))
                .thenReturn(new CompatibleChatModelCaller.ChatTurn("现在是 2026 年 10 月 29 日星期四。", List.of(), List.of()));
        var facts = new PlatformFacts(jdbc, Clock.systemUTC(), "Asia/Shanghai");
        var loop = new OrdinaryToolLoop(model, mcp, NO_RELEASE_TOOLS, facts, jdbc);
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "user", "content", "今天几号？"));

        String answer = loop.run("u-1", "release-1", messages);

        assertThat(answer).isEqualTo("现在是 2026 年 10 月 29 日星期四。");
        verifyNoInteractions(mcp);
        // No release MCP tool means the phone reservation is never read either.
        verifyNoInteractions(jdbc);
    }
}
