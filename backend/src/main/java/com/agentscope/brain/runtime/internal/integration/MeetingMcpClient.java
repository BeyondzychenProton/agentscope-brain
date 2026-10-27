package com.agentscope.brain.runtime.internal.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Meeting MCP Streamable HTTP: signed initialize + tools/call for read-only tools. */
@Component
public class MeetingMcpClient {
    private final String endpoint;
    private final String appKey;
    private final String secret;
    private final String protocolVersion;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);

    public MeetingMcpClient(
            @Value("${brain.resources.meeting-mcp.endpoint:}") String endpoint,
            @Value("${brain.resources.meeting-mcp.app-key:}") String appKey,
            @Value("${brain.resources.meeting-mcp.secret:}") String secret,
            @Value("${brain.resources.meeting-mcp.protocol-version:2025-03-26}") String protocolVersion) {
        this.endpoint = endpoint == null ? "" : endpoint;
        this.appKey = appKey == null ? "" : appKey;
        this.secret = secret == null ? "" : secret;
        this.protocolVersion = protocolVersion == null ? "2025-03-26" : protocolVersion;
    }

    public String callTool(String userPhone, String toolName, Map<String, Object> arguments) {
        requireConfig(userPhone);
        try {
            String sessionId = initialize(userPhone);
            notifyInitialized(userPhone, sessionId);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? Map.of() : arguments);
            JsonNode result = rpc(userPhone, sessionId, "tools/call", params, true);
            return json.writeValueAsString(result);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("MCP_CALL_FAILED", failure);
        }
    }

    private String initialize(String userPhone) throws Exception {
        Map<String, Object> params = Map.of(
                "protocolVersion", protocolVersion,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agentscope-brain", "version", "0.1.0"));
        HttpResponse<String> response = send(userPhone, null, rpcBody("initialize", params, true));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MCP_HTTP_" + response.statusCode());
        }
        JsonNode root = parsePayload(response.body(), response.headers().firstValue("Content-Type").orElse(""));
        if (root.has("error")) throw new IllegalStateException("MCP_RPC_ERROR");
        return response.headers().firstValue("mcp-session-id")
                .or(() -> response.headers().firstValue("Mcp-Session-Id"))
                .orElse(null);
    }

    private void notifyInitialized(String userPhone, String sessionId) throws Exception {
        HttpResponse<String> response = send(userPhone, sessionId, notifyBody("notifications/initialized", Map.of()));
        if (response.statusCode() != 202 && (response.statusCode() < 200 || response.statusCode() >= 300)) {
            throw new IllegalStateException("MCP_NOTIFY_HTTP_" + response.statusCode());
        }
    }

    private JsonNode rpc(String userPhone, String sessionId, String method, Map<String, Object> params, boolean withId)
            throws Exception {
        HttpResponse<String> response = send(userPhone, sessionId, rpcBody(method, params, withId));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MCP_HTTP_" + response.statusCode());
        }
        JsonNode root = parsePayload(response.body(), response.headers().firstValue("Content-Type").orElse(""));
        if (root.has("error")) throw new IllegalStateException("MCP_RPC_ERROR");
        JsonNode result = root.path("result");
        if (result.isMissingNode() || result.isNull()) throw new IllegalStateException("MCP_RESULT_MISSING");
        return result;
    }

    private byte[] rpcBody(String method, Map<String, Object> params, boolean withId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (withId) body.put("id", nextId.getAndIncrement());
        body.put("method", method);
        body.put("params", params);
        return json.writeValueAsBytes(body);
    }

    private byte[] notifyBody(String method, Map<String, Object> params) throws Exception {
        return rpcBody(method, params, false);
    }

    private HttpResponse<String> send(String userPhone, String sessionId, byte[] body) throws Exception {
        long timestamp = System.currentTimeMillis();
        var builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("X-MCP-App-Key", appKey)
                .header("X-MCP-Timestamp", Long.toString(timestamp))
                .header("X-MCP-User-Phone", userPhone)
                .header("X-MCP-Signature", sign(timestamp, userPhone, secret))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static JsonNode parsePayload(String body, String contentType, ObjectMapper mapper) throws Exception {
        String text = body == null ? "" : body.trim();
        if ((contentType != null && contentType.contains("text/event-stream"))
                || text.startsWith("event:") || text.contains("\ndata:")) {
            String data = null;
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) data = trimmed.substring(5).trim();
            }
            if (data == null || data.isBlank()) throw new IllegalStateException("MCP_SSE_EMPTY");
            return mapper.readTree(data);
        }
        return mapper.readTree(text);
    }

    private JsonNode parsePayload(String body, String contentType) throws Exception {
        return parsePayload(body, contentType, json);
    }

    static String sign(long timestampMs, String userPhone, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestampMs + "\n" + userPhone).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("MCP_SIGN_FAILED", failure);
        }
    }

    private void requireConfig(String userPhone) {
        if (endpoint.isBlank() || appKey.isBlank() || secret.isBlank()) {
            throw new IllegalStateException("MCP_CONFIG_UNAVAILABLE");
        }
        if (userPhone == null || !userPhone.matches("\\+?[0-9]{6,31}")) {
            throw new IllegalStateException("MCP_PHONE_UNAVAILABLE");
        }
    }
}
