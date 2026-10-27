package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.runtime.internal.integration.CompatibleChatModelCaller;
import com.agentscope.brain.runtime.internal.integration.MeetingMcpClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Model ↔ read-only MCP tool loop. Final assistant text only; never commits raw tool XML. */
@Service
public class OrdinaryToolLoop {
    private static final int MAX_ROUNDS = 6;
    private final CompatibleChatModelCaller model;
    private final MeetingMcpClient mcp;
    private final ReadonlyToolCatalog tools;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public OrdinaryToolLoop(CompatibleChatModelCaller model, MeetingMcpClient mcp, ReadonlyToolCatalog tools, JdbcTemplate jdbc) {
        this.model = model;
        this.mcp = mcp;
        this.tools = tools;
        this.jdbc = jdbc;
    }

    public String run(String userId, String releaseId, List<Map<String, Object>> seedMessages) {
        String phone = currentPhone(userId);
        List<ReadonlyToolCatalog.Tool> catalog = tools.forRelease(releaseId);
        List<Map<String, Object>> openaiTools = tools.openaiTools(catalog);
        List<Map<String, Object>> messages = new ArrayList<>(seedMessages);
        messages.add(0, Map.of(
                "role", "system",
                "content", "只能通过 API 提供的 tools/function calling 调用工具。禁止输出 <tool_call> 或任何工具 XML/标签。没有可用工具时用中文直接回答。"));
        for (int round = 0; round < MAX_ROUNDS; round++) {
            CompatibleChatModelCaller.ChatTurn turn = model.complete(messages, openaiTools);
            if (turn.toolCalls().isEmpty()) {
                String text = turn.content() == null ? "" : turn.content().trim();
                if (text.isBlank()) throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
                if (looksLikeToolMarkup(text)) throw new IllegalStateException("MODEL_TOOL_MARKUP_LEAK");
                return text;
            }
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", turn.content() == null ? "" : turn.content());
            assistant.put("tool_calls", turn.rawToolCalls());
            messages.add(assistant);
            for (CompatibleChatModelCaller.ToolCall call : turn.toolCalls()) {
                if (!ReadonlyToolCatalog.READ_ONLY.contains(call.name())) {
                    messages.add(toolMessage(call.id(), "{\"error\":\"TOOL_NOT_ALLOWED\"}"));
                    continue;
                }
                Map<String, Object> args;
                try {
                    args = json.readValue(call.argumentsJson(), new TypeReference<>() { });
                } catch (Exception invalid) {
                    messages.add(toolMessage(call.id(), "{\"error\":\"TOOL_ARGUMENTS_INVALID\"}"));
                    continue;
                }
                try {
                    String result = mcp.callTool(phone, call.name(), args);
                    messages.add(toolMessage(call.id(), result));
                } catch (RuntimeException failure) {
                    String code = failure.getMessage() == null ? "MCP_CALL_FAILED" : failure.getMessage();
                    messages.add(toolMessage(call.id(), "{\"error\":\"" + jsonEscape(code) + "\"}"));
                }
            }
        }
        throw new IllegalStateException("TOOL_LOOP_EXCEEDED");
    }

    private String currentPhone(String userId) {
        var rows = jdbc.queryForList("""
                SELECT phone_normalized FROM phone_reservation
                WHERE owner_user_id=? AND reservation_status='CURRENT'
                """, userId);
        if (rows.size() != 1 || rows.get(0).get("phone_normalized") == null) {
            throw new IllegalStateException("MCP_PHONE_UNAVAILABLE");
        }
        return String.valueOf(rows.get(0).get("phone_normalized"));
    }

    private static Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", "tool");
        row.put("tool_call_id", toolCallId);
        row.put("content", content);
        return row;
    }

    static boolean looksLikeToolMarkup(String text) {
        String lower = text.toLowerCase();
        return lower.contains("<tool_call") || lower.contains("</tool_call>") || lower.contains("<function=");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
