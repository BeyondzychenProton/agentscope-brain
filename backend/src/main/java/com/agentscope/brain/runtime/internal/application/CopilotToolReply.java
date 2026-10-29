package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.release.api.TextRelease;
import com.agentscope.brain.runtime.internal.integration.CompatibleChatModelCaller;
import com.agentscope.brain.runtime.internal.integration.MeetingMcpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns a leaked tool-call tag into an executed read-only call and a user-facing reply.
 * The reply is plain text; the tag itself stays in the transcript for the chat to render as a card.
 */
@Service
public class CopilotToolReply {
    private static final int MAX_ROUNDS = 4;
    private final CompatibleChatModelCaller model;
    private final MeetingMcpClient mcp;
    private final OrdinaryToolLoop tools;
    private final TextRelease releases;
    private final JdbcTemplate jdbc;

    public CopilotToolReply(CompatibleChatModelCaller model, MeetingMcpClient mcp, OrdinaryToolLoop tools,
            TextRelease releases, JdbcTemplate jdbc) {
        this.model = model;
        this.mcp = mcp;
        this.tools = tools;
        this.releases = releases;
        this.jdbc = jdbc;
    }

    /** Reply to append when the assistant text ends on a tool tag and has no following prose. */
    public String answer(String threadId, String assistantText) {
        LeakedToolCalls.Split split = LeakedToolCalls.split(assistantText);
        if (!split.needsContinuation()) return "";
        Context context = context(threadId);
        List<Map<String, Object>> messages = transcript(context, split.before());
        messages.add(userResult(executeAll(context.phone(), split.calls())));
        for (int round = 0; round < MAX_ROUNDS; round++) {
            CompatibleChatModelCaller.ChatTurn turn = model.complete(messages, List.of());
            if (!turn.toolCalls().isEmpty()) {
                messages.add(assistantTurn(turn));
                messages.add(userResult(executeNative(context.phone(), turn.toolCalls())));
                continue;
            }
            LeakedToolCalls.Split again = LeakedToolCalls.split(turn.content());
            String prose = (again.before() + "\n" + again.after()).trim();
            if (!again.calls().isEmpty() && prose.isBlank()) {
                messages.add(Map.of("role", "assistant", "content", turn.content()));
                messages.add(userResult(executeAll(context.phone(), again.calls())));
                continue;
            }
            if (prose.isBlank()) throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
            return prose;
        }
        throw new IllegalStateException("TOOL_LOOP_EXCEEDED");
    }

    /**
     * The model emitted a native tool call that the text stream rejected.
     * Run the read-only tool loop and return only its final prose.
     */
    public String answerWithTools(String threadId) {
        Context context = context(threadId);
        List<Map<String, Object>> seed = new ArrayList<>();
        seed.add(Map.of("role", "system", "content", context.instructions()));
        seed.addAll(context.history());
        try {
            return tools.run(context.userId(), context.releaseId(), seed);
        } catch (IllegalStateException failure) {
            if ("MODEL_TOOL_MARKUP_LEAK".equals(failure.getMessage())) return "";
            throw failure;
        }
    }

    private List<Map<String, Object>> transcript(Context context, String spokenBeforeTool) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", context.instructions()
                + "\n\n工具已经由平台执行。根据后续工具结果用中文直接回复用户，禁止输出 tool_call、function 标签或 JSON 工具调用。"));
        messages.addAll(context.history());
        if (!spokenBeforeTool.isBlank()) messages.add(Map.of("role", "assistant", "content", spokenBeforeTool.trim()));
        return messages;
    }

    private String executeAll(String phone, List<LeakedToolCalls.Call> calls) {
        StringBuilder results = new StringBuilder();
        for (LeakedToolCalls.Call call : calls) {
            if (results.length() > 0) results.append('\n');
            results.append(execute(phone, call));
        }
        return results.toString();
    }

    private String execute(String phone, LeakedToolCalls.Call call) {
        if (call.name() == null) return call.rawName() + ": {\"error\":\"TOOL_NOT_ALLOWED\"}";
        try {
            return call.name() + ": " + limit(mcp.callTool(phone, call.name(), call.arguments()));
        } catch (RuntimeException failure) {
            String code = failure.getMessage() == null || failure.getMessage().isBlank() ? "MCP_CALL_FAILED" : failure.getMessage();
            return call.name() + ": {\"error\":\"" + code.replace("\\", "\\\\").replace("\"", "'").replaceAll("\\s+", " ") + "\"}";
        }
    }

    private String executeNative(String phone, List<CompatibleChatModelCaller.ToolCall> calls) {
        StringBuilder results = new StringBuilder();
        for (CompatibleChatModelCaller.ToolCall call : calls) {
            if (results.length() > 0) results.append('\n');
            String name = LeakedToolCalls.canonicalName(call.name());
            Map<String, Object> arguments = LeakedToolCalls.arguments(call.argumentsJson());
            if (name == null) {
                results.append(call.name()).append(": {\"error\":\"TOOL_NOT_ALLOWED\"}");
                continue;
            }
            if (arguments == null) {
                results.append(name).append(": {\"error\":\"TOOL_ARGUMENTS_INVALID\"}");
                continue;
            }
            results.append(execute(phone, new LeakedToolCalls.Call(call.name(), name, arguments)));
        }
        return results.toString();
    }

    private static Map<String, Object> userResult(String results) {
        return Map.of("role", "user", "content", """
                下面是已经执行的只读工具结果。请直接用中文回复用户，不要输出工具标签。

                %s
                """.formatted(results));
    }

    private static Map<String, Object> assistantTurn(CompatibleChatModelCaller.ChatTurn turn) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", turn.content() == null ? "" : turn.content());
        assistant.put("tool_calls", turn.rawToolCalls());
        return assistant;
    }

    private Context context(String threadId) {
        var rows = jdbc.queryForList(
                "SELECT user_id,employee_id,employee_release_id FROM copilot_thread WHERE thread_id=?", threadId);
        if (rows.size() != 1) throw new IllegalStateException("THREAD_UNAVAILABLE");
        String userId = String.valueOf(rows.get(0).get("user_id"));
        String employeeId = String.valueOf(rows.get(0).get("employee_id"));
        String releaseId = String.valueOf(rows.get(0).get("employee_release_id"));
        String instructions = releases.textConfiguration(employeeId, releaseId).instructions();
        List<Map<String, Object>> history = new ArrayList<>();
        for (var message : jdbc.queryForList(
                "SELECT role,content,status FROM copilot_thread_message WHERE thread_id=? ORDER BY message_no", threadId)) {
            if (!"COMPLETE".equals(String.valueOf(message.get("status")))) continue;
            String content = message.get("content") == null ? "" : String.valueOf(message.get("content")).trim();
            if (content.isEmpty()) continue;
            String role = "USER".equals(String.valueOf(message.get("role"))) ? "user" : "assistant";
            history.add(Map.of("role", role, "content", content));
        }
        return new Context(userId, releaseId, instructions, phone(userId), List.copyOf(history));
    }

    private String phone(String userId) {
        var rows = jdbc.queryForList("""
                SELECT phone_normalized FROM phone_reservation
                WHERE owner_user_id=? AND reservation_status='CURRENT'
                """, userId);
        if (rows.size() != 1 || rows.get(0).get("phone_normalized") == null)
            throw new IllegalStateException("MCP_PHONE_UNAVAILABLE");
        return String.valueOf(rows.get(0).get("phone_normalized"));
    }

    private static String limit(String value) {
        if (value == null) return "";
        return value.length() <= 8000 ? value : value.substring(0, 8000);
    }

    private record Context(String userId, String releaseId, String instructions, String phone,
            List<Map<String, Object>> history) { }
}
