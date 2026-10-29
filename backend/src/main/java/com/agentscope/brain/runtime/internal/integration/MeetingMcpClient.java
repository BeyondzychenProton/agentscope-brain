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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Meeting MCP Streamable HTTP: signed initialize + tools/call for read-only tools. */
@Component
public class MeetingMcpClient {
    private static final Logger LOG = LoggerFactory.getLogger(MeetingMcpClient.class);
    private final String endpoint;
    private final String appKey;
    private final String secret;
    private final String protocolVersion;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    /** One negotiated session per signed user; a rejected session is dropped and re-initialized once. */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

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
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? Map.of() : arguments);
            JsonNode result = callWithSession(userPhone, params);
            if (result.path("isError").asBoolean(false)) {
                LOG.warn("MCP tool reported a business error tool={} detail={}", toolName, brief(result.toString()));
                return json.writeValueAsString(result);
            }
            return json.writeValueAsString(business(result));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("MCP_CALL_FAILED", failure);
        }
    }

    private JsonNode callWithSession(String userPhone, Map<String, Object> params) throws Exception {
        boolean retried = false;
        while (true) {
            String sessionId = sessions.get(userPhone);
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = initialize(userPhone);
            }
            HttpResponse<String> response = send(userPhone, sessionId, rpcBody("tools/call", params, true), true);
            if (response.statusCode() == 404 && !retried) {
                // The server forgot this session (restart or TTL): start over exactly once.
                sessions.remove(userPhone);
                retried = true;
                LOG.info("MCP session was rejected with 404; re-initializing once");
                continue;
            }
            return result(response);
        }
    }

    private String initialize(String userPhone) throws Exception {
        Map<String, Object> params = Map.of(
                "protocolVersion", protocolVersion,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agentscope-brain", "version", "0.1.0"));
        HttpResponse<String> response = send(userPhone, null, rpcBody("initialize", params, true), false);
        checkStatus(response, "initialize");
        JsonNode root = parsePayload(response.body(), contentType(response), json);
        if (root.has("error")) throw rpcError(root);
        JsonNode payload = root.path("result");
        String negotiated = payload.path("protocolVersion").asText("");
        if (!protocolVersion.equals(negotiated)) {
            throw new IllegalStateException("MCP_PROTOCOL_MISMATCH: expected " + protocolVersion
                    + " but server negotiated " + brief(negotiated));
        }
        String sessionId = response.headers().firstValue("mcp-session-id").orElse(null);
        notifyInitialized(userPhone, sessionId);
        if (sessionId != null && !sessionId.isBlank()) sessions.put(userPhone, sessionId);
        LOG.info("MCP session initialized protocol={} server={}", negotiated,
                brief(payload.path("serverInfo").path("name").asText("")));
        return sessionId;
    }

    private void notifyInitialized(String userPhone, String sessionId) throws Exception {
        HttpResponse<String> response = send(userPhone, sessionId,
                notifyBody("notifications/initialized", Map.of()), true);
        checkStatus(response, "notifications/initialized");
    }

    private JsonNode result(HttpResponse<String> response) throws Exception {
        checkStatus(response, "tools/call");
        JsonNode root = parsePayload(response.body(), contentType(response), json);
        if (root.has("error")) throw rpcError(root);
        JsonNode result = root.path("result");
        if (result.isMissingNode() || result.isNull()) throw new IllegalStateException("MCP_RESULT_MISSING");
        return result;
    }

    /** Prefer structuredContent, then the first JSON text block; the raw MCP envelope is the fallback. */
    JsonNode business(JsonNode result) {
        JsonNode structured = result.get("structuredContent");
        if (structured != null && structured.isObject()) return structured;
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                if (!"text".equals(block.path("type").asText(""))) continue;
                String text = block.path("text").asText("");
                try {
                    JsonNode parsed = json.readTree(text);
                    if (parsed != null && parsed.isObject()) return parsed;
                } catch (Exception notJson) {
                    // A plain-text block is not a business payload; keep looking.
                }
            }
        }
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

    private HttpResponse<String> send(String userPhone, String sessionId, byte[] body, boolean withProtocolHeader)
            throws Exception {
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
        if (withProtocolHeader) builder.header("MCP-Protocol-Version", protocolVersion);
        if (sessionId != null && !sessionId.isBlank()) builder.header("Mcp-Session-Id", sessionId);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void checkStatus(HttpResponse<String> response, String method) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        String detail = brief(serverError(response.body()));
        LOG.warn("MCP {} rejected status={} detail={}", method, status, detail);
        throw new IllegalStateException(detail.isBlank() ? "MCP_HTTP_" + status
                : "MCP_HTTP_" + status + ": " + detail);
    }

    private IllegalStateException rpcError(JsonNode root) {
        JsonNode error = root.path("error");
        String message = error.isTextual() ? error.asText("") : error.path("message").asText("");
        String detail = brief(message);
        LOG.warn("MCP JSON-RPC error detail={}", detail);
        return new IllegalStateException(detail.isBlank() ? "MCP_RPC_ERROR" : "MCP_RPC_ERROR: " + detail);
    }

    private String serverError(String body) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) return "";
        try {
            JsonNode error = json.readTree(text).path("error");
            if (error.isTextual()) return error.asText("");
            return error.path("message").asText("");
        } catch (Exception notJson) {
            return text;
        }
    }

    private static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    /**
     * Parses either a plain JSON body or one or more SSE events. Notifications have no id, so a
     * JSON-RPC response carrying an id always wins over a progress/keepalive event.
     */
    static JsonNode parsePayload(String body, String contentType, ObjectMapper mapper) throws Exception {
        String text = body == null ? "" : body.trim();
        boolean sse = (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream"))
                || text.startsWith("event:") || text.startsWith("data:") || text.contains("\ndata:");
        if (!sse) return mapper.readTree(text);
        JsonNode last = null;
        JsonNode lastWithId = null;
        for (String event : text.split("\\r?\\n\\r?\\n|\\r\\r")) {
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\\r?\\n")) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("data:")) continue;
                if (data.length() > 0) data.append('\n');
                data.append(trimmed.substring(5).stripLeading());
            }
            if (data.length() == 0) continue;
            JsonNode node;
            try {
                node = mapper.readTree(data.toString());
            } catch (Exception invalidEvent) {
                continue;
            }
            if (node == null || node.isMissingNode()) continue;
            last = node;
            if (node.has("id")) lastWithId = node;
        }
        if (lastWithId != null) return lastWithId;
        if (last == null) throw new IllegalStateException("MCP_SSE_EMPTY");
        return last;
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

    private static String brief(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 200 ? compact : compact.substring(0, 200) + "...";
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
