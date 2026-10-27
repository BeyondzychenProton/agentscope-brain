package com.agentscope.brain.runtime.internal.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** OpenAI-compatible chat completions against brain.resources.model. */
@Component
public class CompatibleChatModelCaller implements ModelCaller {
    public record ToolCall(String id, String name, String argumentsJson) { }
    public record ChatTurn(String content, List<ToolCall> toolCalls, List<Map<String, Object>> rawToolCalls) {
        public ChatTurn {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            rawToolCalls = rawToolCalls == null ? List.of() : List.copyOf(rawToolCalls);
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();

    public CompatibleChatModelCaller(
            @Value("${brain.resources.model.base-url:}") String baseUrl,
            @Value("${brain.resources.model.api-key:}") String apiKey,
            @Value("${brain.resources.model.model:}") String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
    }

    @Override
    public ModelResponse call(ModelRequestCopy request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (var msg : request.messages()) {
            String role = msg.getRole() == null ? "user" : switch (msg.getRole().name()) {
                case "SYSTEM" -> "system";
                case "ASSISTANT" -> "assistant";
                default -> "user";
            };
            String text = msg.getTextContent() == null ? "" : msg.getTextContent();
            messages.add(Map.of("role", role, "content", text));
        }
        ChatTurn turn = complete(messages, List.of());
        if (!turn.toolCalls().isEmpty()) throw new IllegalStateException("MODEL_UNEXPECTED_TOOL_CALL");
        return ModelResponse.textOnly(turn.content());
    }

    public ChatTurn complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw new IllegalStateException("MODEL_CONFIG_UNAVAILABLE");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", false);
            if (tools != null && !tools.isEmpty()) body.put("tools", tools);
            byte[] payload = json.writeValueAsBytes(body);
            var httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(modelFailureCode(response.statusCode(), response.body()));
            }
            JsonNode message = json.readTree(response.body()).path("choices").path(0).path("message");
            List<ToolCall> calls = new ArrayList<>();
            List<Map<String, Object>> rawCalls = new ArrayList<>();
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    String id = call.path("id").asText("");
                    String name = call.path("function").path("name").asText("");
                    String args = call.path("function").path("arguments").asText("{}");
                    if (id.isBlank() || name.isBlank()) continue;
                    calls.add(new ToolCall(id, name, args));
                    Map<String, Object> raw = new LinkedHashMap<>();
                    raw.put("id", id);
                    raw.put("type", "function");
                    raw.put("function", Map.of("name", name, "arguments", args));
                    rawCalls.add(raw);
                }
            }
            String content = message.path("content").isTextual() ? message.path("content").asText() : "";
            if (calls.isEmpty() && content.isBlank()) throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
            return new ChatTurn(content, calls, rawCalls);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("MODEL_CALL_FAILED", failure);
        }
    }

    static String modelFailureCode(int status, String body) {
        String code = null;
        try {
            JsonNode node = new ObjectMapper().readTree(body == null ? "" : body);
            JsonNode error = node.path("error");
            if (error.path("code").isTextual()) code = error.path("code").asText();
            else if (error.path("type").isTextual()) code = error.path("type").asText();
        } catch (Exception ignored) {
            // Keep HTTP status only when the provider body is not JSON.
        }
        if (code != null && !code.isBlank()) {
            String normalized = code.replaceAll("[^A-Za-z0-9_.-]", "");
            if (!normalized.isBlank()) {
                String labeled = "MODEL_" + normalized;
                return labeled.length() <= 64 ? labeled : labeled.substring(0, 64);
            }
        }
        return "MODEL_HTTP_" + status;
    }
}
