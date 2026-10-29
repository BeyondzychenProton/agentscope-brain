package com.agentscope.brain.runtime.internal.integration;

import com.agentscope.brain.runtime.internal.application.AgentToolCatalog;
import io.agentscope.core.message.ToolResultBlock;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentToolsTest {
    /** A release without MCP tools still hands the model the platform facts. */
    private static AgentToolCatalog noReleaseTools() {
        return new AgentToolCatalog(null, "unused") {
            @Override public List<AgentToolCatalog.Tool> forRelease(String releaseId) { return List.of(); }
        };
    }

    @Test void registersPlatformFactsWithoutReleaseTools() {
        var jdbc = mock(JdbcTemplate.class);
        var tools = new AgentTools(noReleaseTools(), null, null,
                new PlatformFacts(jdbc, Clock.systemUTC(), "Asia/Shanghai"), jdbc);

        var toolkit = tools.forRun("u-1", "release-1");

        assertThat(toolkit.getToolNames())
                .containsExactlyInAnyOrder("platform_current_time", "platform_current_user");
        // Platform facts must not require the MCP phone reservation.
        verifyNoInteractions(jdbc);
    }

    @Test void answersTheTimeFromThePlatformClock() {
        var jdbc = mock(JdbcTemplate.class);
        var tools = new AgentTools(noReleaseTools(), null, null,
                new PlatformFacts(jdbc, Clock.systemUTC(), "Asia/Shanghai"), jdbc);

        ToolResultBlock result = tools.forRun("u-1", "release-1")
                .getTool("platform_current_time").callAsync(null).block();

        assertThat(result).isNotNull();
        assertThat(String.valueOf(result.getOutput())).contains("Asia/Shanghai");
    }
}
