package com.agentscope.brain.runtime.internal.integration;

import com.agentscope.brain.runtime.internal.application.ReadonlyToolCatalog;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Request-scoped SDK tools. Operator identity comes only from the accepted server-side thread. */
@Component
public class ReadonlyAgentTools {
    private static final Logger LOG = LoggerFactory.getLogger(ReadonlyAgentTools.class);
    private final ReadonlyToolCatalog catalog;
    private final MeetingMcpClient mcp;
    private final JdbcTemplate jdbc;

    public ReadonlyAgentTools(ReadonlyToolCatalog catalog, MeetingMcpClient mcp, JdbcTemplate jdbc) {
        this.catalog = catalog; this.mcp = mcp; this.jdbc = jdbc;
    }

    public Toolkit forRun(String userId, String releaseId) {
        var toolkit = new Toolkit();
        var tools = catalog.forRelease(releaseId);
        if (tools.isEmpty()) return toolkit;
        var phones = jdbc.queryForList("SELECT phone_normalized FROM phone_reservation WHERE owner_user_id=? AND reservation_status='CURRENT'", userId);
        if (phones.size() != 1 || phones.get(0).get("phone_normalized") == null)
            throw new IllegalStateException("MCP_PHONE_UNAVAILABLE");
        String phone = String.valueOf(phones.get(0).get("phone_normalized"));
        for (var tool : tools) toolkit.registerAgentTool(new AgentTool() {
            public String getName() { return tool.name().replace('.', '_'); }
            public String getDescription() { return tool.description(); }
            public Map<String, Object> getParameters() { return tool.parameters(); }
            public boolean isReadOnly() { return true; }
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> ToolResultBlock.text(mcp.callTool(phone, tool.name(), param.getInput())))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(failure -> {
                            // Keep the concrete MCP_* cause: "tool failed" alone hides 401, 503 and RPC errors.
                            String code = failure.getMessage() == null || failure.getMessage().isBlank()
                                    ? "MCP_CALL_FAILED" : failure.getMessage();
                            LOG.warn("Copilot tool call failed tool={} userId={} code={}", tool.name(), userId, code, failure);
                            return Mono.just(ToolResultBlock.error(code));
                        });
            }
        });
        return toolkit;
    }
}
