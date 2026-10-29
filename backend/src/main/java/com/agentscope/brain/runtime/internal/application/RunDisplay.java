package com.agentscope.brain.runtime.internal.application;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One fold of SDK events for both durable recovery and live display. Private to run execution. */
public final class RunDisplay {
    private final Map<String, Part> parts = new LinkedHashMap<>();
    private Part current;

    boolean apply(AguiEvent event) {
        if (event instanceof AguiEvent.ReasoningMessageStart e) { reasoning(e.messageId()); return true; }
        if (event instanceof AguiEvent.ReasoningMessageContent e) { reasoning(e.messageId()).text.append(e.delta()); return true; }
        if (event instanceof AguiEvent.ReasoningMessageChunk e) { if (e.delta() != null) reasoning(e.messageId()).text.append(e.delta()); return true; }
        if (event instanceof AguiEvent.TextMessageStart e) { current = assistant(e.messageId()); return true; }
        if (event instanceof AguiEvent.TextMessageContent e) { current = assistant(e.messageId()); current.text.append(e.delta()); return true; }
        if (event instanceof AguiEvent.TextMessageChunk e) { current = assistant(e.messageId()); if (e.delta() != null) current.text.append(e.delta()); return true; }
        if (event instanceof AguiEvent.ToolCallStart e) {
            if (current == null) current = assistant("tool-message-" + e.toolCallId());
            current.tools.putIfAbsent(e.toolCallId(), new Call(e.toolCallName())); return true;
        }
        if (event instanceof AguiEvent.ToolCallArgs e) { call(e.toolCallId()).args.append(e.delta()); return true; }
        if (event instanceof AguiEvent.ToolCallChunk e) {
            if (e.parentMessageId() != null) current = assistant(e.parentMessageId());
            if (current == null) current = assistant("tool-message-" + e.toolCallId());
            Call call = current.tools.computeIfAbsent(e.toolCallId(), id -> new Call(e.toolCallName()));
            if (e.delta() != null) call.args.append(e.delta()); return true;
        }
        if (event instanceof AguiEvent.ToolCallResult e) {
            Part result = new Part(e.messageId(), "tool", e.toolCallId());
            result.text.append(e.content()); parts.put(result.id, result); return true;
        }
        return false;
    }

    List<AguiMessage> messages() {
        List<AguiMessage> out = new ArrayList<>();
        for (Part part : parts.values()) {
            var calls = part.tools.entrySet().stream().map(e -> new AguiToolCall(e.getKey(),
                    new AguiFunctionCall(e.getValue().name, e.getValue().args.toString()))).toList();
            out.add(AguiMessage.textMessage(part.id, part.role, part.text.toString(), calls, part.toolCallId));
        }
        return List.copyOf(out);
    }
    String json() { return JsonUtils.getJsonCodec().toJson(messages()); }
    public static List<AguiMessage> restore(String json) {
        return JsonUtils.getJsonCodec().fromJson(json, new TypeReference<List<AguiMessage>>() { });
    }
    private Part assistant(String id) {
        if (id == null) throw new IllegalStateException("MODEL_MESSAGE_ID_MISSING");
        return parts.computeIfAbsent(id, key -> new Part(key, "assistant", null));
    }
    private Part reasoning(String id) {
        if (id == null) throw new IllegalStateException("MODEL_MESSAGE_ID_MISSING");
        return parts.computeIfAbsent(id, key -> new Part(key, "reasoning", null));
    }
    private Call call(String id) {
        return parts.values().stream().map(p -> p.tools.get(id)).filter(java.util.Objects::nonNull).findFirst()
                .orElseThrow(() -> new IllegalStateException("MODEL_TOOL_EVENT_INVALID"));
    }
    private static final class Part {
        final String id, role, toolCallId;
        final StringBuilder text = new StringBuilder();
        final Map<String, Call> tools = new LinkedHashMap<>();
        Part(String id, String role, String toolCallId) { this.id = id; this.role = role; this.toolCallId = toolCallId; }
    }
    private static final class Call {
        final String name; final StringBuilder args = new StringBuilder();
        Call(String name) { this.name = name; }
    }
}

