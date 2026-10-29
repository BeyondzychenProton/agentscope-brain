package com.agentscope.brain.runtime.internal.integration;

import com.agentscope.brain.runtime.internal.application.AgentToolCatalog;
import com.agentscope.brain.runtime.internal.application.WriteOperations;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Request-scoped SDK tools. Read-only tools run directly; mutating tools require a one-time
 * platform confirmation before the provider is contacted.
 */
@Component
public class AgentTools {
    private static final Logger LOG = LoggerFactory.getLogger(AgentTools.class);
    private final AgentToolCatalog catalog;
    private final MeetingMcpClient mcp;
    private final WriteOperations operations;
    private final PlatformFacts facts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AgentTools(AgentToolCatalog catalog, MeetingMcpClient mcp, WriteOperations operations,
            PlatformFacts facts, JdbcTemplate jdbc) {
        this.catalog = catalog; this.mcp = mcp; this.operations = operations; this.facts = facts; this.jdbc = jdbc;
    }

    public Toolkit forRun(String userId, String releaseId) {
        var toolkit = new Toolkit();
        // Platform facts come first: they must stay available even when a release configures no MCP tools.
        for (var local : catalog.localTools()) toolkit.registerAgentTool(local(local, userId));
        var tools = catalog.forRelease(releaseId);
        if (tools.isEmpty()) return toolkit;
        var phones = jdbc.queryForList("SELECT phone_normalized FROM phone_reservation WHERE owner_user_id=? AND reservation_status='CURRENT'", userId);
        if (phones.size() != 1 || phones.get(0).get("phone_normalized") == null)
            throw new IllegalStateException("MCP_PHONE_UNAVAILABLE");
        String phone = String.valueOf(phones.get(0).get("phone_normalized"));
        for (var tool : tools) {
            if (tool.readOnly()) toolkit.registerAgentTool(readOnly(tool, phone, userId));
            else toolkit.registerAgentTool(write(tool, phone, userId));
        }
        return toolkit;
    }

    /** Locally answered platform fact; never forwarded to the meeting MCP. */
    private AgentTool local(AgentToolCatalog.Tool tool, String userId) {
        return new AgentTool() {
            public String getName() { return tool.name().replace('.', '_'); }
            public String getDescription() { return tool.description(); }
            public Map<String, Object> getParameters() { return tool.parameters(); }
            public boolean isReadOnly() { return true; }
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> ToolResultBlock.text(switch (tool.name()) {
                            case "platform.current_time" -> facts.currentTime();
                            default -> facts.currentUser(userId);
                        }))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(failure -> {
                            LOG.warn("Platform fact tool failed tool={} userId={}", tool.name(), userId, failure);
                            return Mono.just(ToolResultBlock.error("PLATFORM_FACT_UNAVAILABLE"));
                        });
            }
        };
    }

    private AgentTool readOnly(AgentToolCatalog.Tool tool, String phone, String userId) {
        return new AgentTool() {
            public String getName() { return tool.name().replace('.', '_'); }
            public String getDescription() { return tool.description(); }
            public Map<String, Object> getParameters() { return tool.parameters(); }
            public boolean isReadOnly() { return true; }
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> ToolResultBlock.text(mcp.callTool(phone, tool.name(), param.getInput())))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(failure -> {
                            String code = failure.getMessage() == null || failure.getMessage().isBlank()
                                    ? "MCP_CALL_FAILED" : failure.getMessage();
                            LOG.warn("Copilot tool call failed tool={} userId={} code={}", tool.name(), userId, code, failure);
                            return Mono.just(ToolResultBlock.error(code));
                        });
            }
        };
    }

    /** Mutating tools are gated by the SDK permission gate: the user must answer the interrupt. */
    private ToolBase write(AgentToolCatalog.Tool tool, String phone, String userId) {
        return new ToolBase(ToolBase.builder()
                .name(tool.name().replace('.', '_'))
                .description(tool.description())
                .inputSchema(tool.parameters())
                .readOnly(false)
                .concurrencySafe(false)) {
            @Override
            public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
                return Mono.just(PermissionDecision.ask("写操作需要登录用户本人一次性确认"));
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> execute(param))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(failure -> {
                            String code = failure.getMessage() == null || failure.getMessage().isBlank()
                                    ? "MCP_CALL_FAILED" : failure.getMessage();
                            LOG.warn("Copilot write tool failed tool={} userId={} code={}", tool.name(), userId, code, failure);
                            return Mono.just(ToolResultBlock.error(code));
                        });
            }

            private ToolResultBlock execute(ToolCallParam param) throws Exception {
                RuntimeContext runtime = param.getRuntimeContext();
                String runId = runtime == null ? null : runtime.get(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY);
                if (runId == null || runId.isBlank()) throw new IllegalStateException("CONFIRMATION_REQUIRED");
                Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
                // Never trust a model-supplied idempotency key; the platform owns it.
                WriteOperations.Confirmed confirmed = operations.consume(runId, tool.name(), input);
                Map<String, Object> arguments = new LinkedHashMap<>(input);
                arguments.put("requestId", confirmed.requestId());
                try {
                    String result = mcp.callTool(phone, tool.name(), arguments);
                    operations.resolve(confirmed.operationId(), "SUCCEEDED", status(result), parse(result));
                    return ToolResultBlock.text(result);
                } catch (RuntimeException failure) {
                    operations.resolve(confirmed.operationId(), "FAILED", failure.getMessage(), null);
                    throw failure;
                }
            }
        };
    }

    private Map<String, Object> parse(String value) {
        try {
            return json.readValue(value, new TypeReference<Map<String, Object>>() { });
        } catch (Exception invalid) {
            return null;
        }
    }

    private static String status(String result) {
        try {
            var node = new ObjectMapper().readTree(result);
            String status = node.path("status").asText("");
            return status.isBlank() ? null : status;
        } catch (Exception invalid) {
            return null;
        }
    }
}
