package com.agentscope.brain.runtime.internal.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Internal model compatibility: normalize tool markup before it reaches SDK text events. */
final class ModelToolStream {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, String> allowed = new HashMap<>();
    private final StringBuilder pending = new StringBuilder();
    private int calls;
    private boolean nativeCalls;
    private boolean done;
    private boolean finished;

    ModelToolStream(String request) {
        try {
            for (var tool : JSON.readTree(request).path("tools")) {
                String name = tool.path("function").path("name").asText();
                allowed.put(name, name);
                // Canonical dotted names are accepted only when their exact alias was advertised.
                allowed.put(name.replaceFirst("_", "."), name);
            }
        } catch (Exception invalid) { throw new IllegalStateException("MODEL_REQUEST_INVALID", invalid); }
    }

    boolean isDone() { return done; }

    List<String> accept(String data) {
        if (done) throw new IllegalStateException("MODEL_STREAM_INVALID");
        if ("[DONE]".equals(data)) {
            if (!finished) throw new IllegalStateException("MODEL_STREAM_INCOMPLETE");
            if (!pending.isEmpty()) throw new IllegalStateException("MODEL_TOOL_MARKUP_INCOMPLETE");
            done = true;
            return List.of(data);
        }
        try {
            var root = JSON.readTree(data);
            var choices = root.path("choices");
            if (!choices.isArray()) throw new IllegalStateException("MODEL_STREAM_INVALID");
            if (choices.isEmpty()) return List.of(data); // usage-only frame
            if (choices.size() != 1) throw new IllegalStateException("MODEL_STREAM_INVALID");
            var choice = (ObjectNode) choices.get(0);
            var delta = (ObjectNode) choice.path("delta");
            if (delta.hasNonNull("function_call")) throw new IllegalStateException("MODEL_LEGACY_TOOL_UNSUPPORTED");
            if (delta.hasNonNull("tool_calls")) {
                if (calls > 0) throw new IllegalStateException("MODEL_MIXED_TOOL_FORMAT");
                nativeCalls = true;
                if (allowed.isEmpty()) throw new IllegalStateException("MODEL_TOOL_NOT_AVAILABLE");
            }
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isEmpty()) finished = true;
            if ("length".equals(finish)) throw new IllegalStateException("MODEL_OUTPUT_TRUNCATED");
            if ("content_filter".equals(finish)) throw new IllegalStateException("MODEL_OUTPUT_FILTERED");
            List<String> out = new ArrayList<>();
            // A provider may put reasoning and answer text in the same chunk. Preserve their order.
            if (delta.hasNonNull("reasoning_content")) {
                emit(root, out, Map.of("reasoning_content", delta.get("reasoning_content").asText()));
                delta.remove("reasoning_content");
            }
            if (delta.path("content").isTextual()) {
                pending.append(delta.path("content").asText());
                delta.remove("content");
                drain(root, out, !finish.isEmpty());
            } else if (!finish.isEmpty()) drain(root, out, true);
            // Preserve role, native calls, metadata and the single finish marker.
            if (calls > 0 && !finish.isEmpty()) choice.put("finish_reason", "tool_calls");
            if (!delta.isEmpty() || !finish.isEmpty()) out.add(JSON.writeValueAsString(root));
            return out;
        } catch (IllegalStateException failure) { throw failure; }
        catch (Exception invalid) { throw new IllegalStateException("MODEL_STREAM_INVALID", invalid); }
    }

    private void drain(JsonNode template, List<String> out, boolean finishing) throws Exception {
        while (!pending.isEmpty()) {
            String text = pending.toString();
            int start = text.indexOf('<');
            if (start < 0) { emit(template, out, Map.of("content", text)); pending.setLength(0); return; }
            if (start > 0) {
                emit(template, out, Map.of("content", text.substring(0, start)));
                pending.delete(0, start); continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            String tag = lower.startsWith("<tool_call>") ? "tool_call" : lower.startsWith("<tool>") ? "tool" : null;
            if (tag == null) {
                boolean prefix = "<tool_call>".startsWith(lower) || "<tool>".startsWith(lower) || "<function=".startsWith(lower);
                if (prefix && !finishing) return;
                if (prefix || lower.startsWith("<tool") || lower.startsWith("<function") || lower.startsWith("</tool"))
                    throw new IllegalStateException("MODEL_TOOL_MARKUP_INVALID");
                emit(template, out, Map.of("content", "<")); pending.deleteCharAt(0); continue;
            }
            int end = lower.indexOf("</" + tag + ">");
            if (end < 0) {
                if (finishing || pending.length() > 65536) throw new IllegalStateException("MODEL_TOOL_MARKUP_INCOMPLETE");
                return;
            }
            if (nativeCalls) throw new IllegalStateException("MODEL_MIXED_TOOL_FORMAT");
            var call = JSON.readTree(text.substring(tag.length() + 2, end).strip());
            String name = call.path("name").asText(call.path("function").path("name").asText(""));
            String alias = allowed.get(name);
            if (alias == null) throw new IllegalStateException("MODEL_TOOL_NOT_AVAILABLE");
            JsonNode args = call.has("arguments") ? call.get("arguments") : call.get("parameters");
            if (args == null) args = JSON.createObjectNode();
            if (args != null && args.isTextual()) args = JSON.readTree(args.asText());
            if (args == null || !args.isObject()) throw new IllegalStateException("MODEL_TOOL_ARGUMENTS_INVALID");
            if (calls >= 16) throw new IllegalStateException("MODEL_TOO_MANY_TOOL_CALLS");
            emit(template, out, Map.of("tool_calls", List.of(Map.of("index", calls++, "id", UUID.randomUUID().toString(),
                    "type", "function", "function", Map.of("name", alias, "arguments", args.toString())))));
            pending.delete(0, end + tag.length() + 3);
        }
    }

    private static void emit(JsonNode template, List<String> out, Map<String, Object> delta) throws Exception {
        ObjectNode copy = template.deepCopy();
        ObjectNode choice = (ObjectNode) copy.path("choices").get(0);
        choice.set("delta", JSON.valueToTree(delta)); choice.putNull("finish_reason");
        out.add(JSON.writeValueAsString(copy));
    }
}
