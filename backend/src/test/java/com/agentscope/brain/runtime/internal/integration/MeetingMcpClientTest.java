package com.agentscope.brain.runtime.internal.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingMcpClientTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test void signsTimestampAndPhoneWithLf() {
        String signature = MeetingMcpClient.sign(1_700_000_000_000L, "13800000000", "secret");
        assertThat(signature).isEqualTo(MeetingMcpClient.sign(1_700_000_000_000L, "13800000000", "secret"));
        assertThat(signature).hasSize(64);
        assertThat(signature).matches("[0-9a-f]{64}");
    }

    @Test void parsesSseDataPayload() throws Exception {
        var node = MeetingMcpClient.parsePayload(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n",
                "text/event-stream",
                new ObjectMapper());
        assertThat(node.path("result").path("ok").asBoolean()).isTrue();
    }

    @Test void prefersTheResponseEventOverNotificationsAndJoinsMultiLineData() throws Exception {
        var node = MeetingMcpClient.parsePayload(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":7,\ndata: \"result\":{\"ok\":true}}\n\n",
                "text/event-stream",
                new ObjectMapper());
        assertThat(node.path("id").asInt()).isEqualTo(7);
        assertThat(node.path("result").path("ok").asBoolean()).isTrue();
    }

    @Test void unwrapsStructuredContentThenTextBlocksThenRawEnvelope() throws Exception {
        var client = new MeetingMcpClient("", "", "", "2025-03-26");
        var structured = JSON.readTree("{\"structuredContent\":{\"status\":\"SUCCESS\"},\"content\":[]}");
        assertThat(client.business(structured).path("status").asText()).isEqualTo("SUCCESS");
        var text = JSON.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"status\\\":\\\"CREATED\\\"}\"}]}");
        assertThat(client.business(text).path("status").asText()).isEqualTo("CREATED");
        var plain = JSON.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"plain prose\"}]}");
        assertThat(client.business(plain).path("content").isArray()).isTrue();
    }

    @Test void reusesTheNegotiatedSessionAndSendsTheProtocolHeader() throws Exception {
        try (var fake = new FakeMcp(exchange -> initializeResult("2025-03-26"))) {
            var client = fake.client();
            String first = client.callTool("13636528157", "identity.resolve", Map.of("pageNum", 1));
            String second = client.callTool("13636528157", "identity.resolve", Map.of("pageNum", 2));
            assertThat(first).isEqualTo("{\"resolutionStatus\":\"RESOLVED\"}");
            assertThat(second).isEqualTo("{\"resolutionStatus\":\"RESOLVED\"}");
            assertThat(fake.methods).containsExactly("initialize", "notifications/initialized", "tools/call", "tools/call");
            // MCP 2025-03-26 requires the header on every request after initialize.
            assertThat(fake.protocolHeaders.get(0)).isNull();
            assertThat(fake.protocolHeaders.subList(1, fake.protocolHeaders.size()))
                    .containsOnly("2025-03-26");
            assertThat(fake.sessionHeaders.subList(2, fake.sessionHeaders.size())).containsOnly("s-1");
        }
    }

    @Test void reinitializesOnceWhenTheServerForgetsTheSession() throws Exception {
        var fake = new FakeMcp(exchange -> initializeResult("2025-03-26"));
        fake.noSessionIsExpiredOnce();
        try (fake) {
            assertThat(fake.client().callTool("13636528157", "meeting.list_mine", Map.of()))
                    .isEqualTo("{\"resolutionStatus\":\"RESOLVED\"}");
            assertThat(fake.methods).containsExactly("initialize", "notifications/initialized",
                    "tools/call", "initialize", "notifications/initialized", "tools/call");
        }
    }

    @Test void surfacesTheServerRejectionDetail() throws Exception {
        try (var fake = new FakeMcp(exchange -> new Reply(401, "application/json", "{\"error\":\"Invalid MCP user\"}"))) {
            assertThatThrownBy(() -> fake.client().callTool("13636528157", "identity.resolve", Map.of()))
                    .hasMessage("MCP_HTTP_401: Invalid MCP user");
        }
    }

    @Test void rejectsAProtocolVersionTheServerDidNotNegotiate() throws Exception {
        try (var fake = new FakeMcp(exchange -> initializeResult("2025-06-18"))) {
            assertThatThrownBy(() -> fake.client().callTool("13636528157", "identity.resolve", Map.of()))
                    .hasMessageContaining("MCP_PROTOCOL_MISMATCH");
        }
    }

    private static Reply initializeResult(String version) {
        return new Reply(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"" + version
                        + "\",\"serverInfo\":{\"name\":\"fake\"}}}");
    }

    record Reply(int status, String contentType, String body) { }

    /** Minimal Streamable HTTP MCP server: one session, SSE tools/call, optional 404 on first call. */
    static final class FakeMcp implements AutoCloseable {
        final List<String> methods = new CopyOnWriteArrayList<>();
        final List<String> protocolHeaders = new CopyOnWriteArrayList<>();
        final List<String> sessionHeaders = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final String endpoint;
        private final Function<HttpExchange, Reply> initialize;
        private final AtomicInteger calls = new AtomicInteger();
        private boolean expireFirstCall;

        FakeMcp(Function<HttpExchange, Reply> initialize) throws IOException {
            this.initialize = initialize;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
            endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        void noSessionIsExpiredOnce() { expireFirstCall = true; }

        MeetingMcpClient client() {
            return new MeetingMcpClient(endpoint, "agent", "secret", "2025-03-26");
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String method = JSON.readTree(body).path("method").asText("");
            methods.add(method);
            protocolHeaders.add(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            if ("initialize".equals(method)) {
                Reply reply = initialize.apply(exchange);
                exchange.getResponseHeaders().set("Mcp-Session-Id", "s-1");
                respond(exchange, reply);
                return;
            }
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if (expireFirstCall && calls.getAndIncrement() == 0) {
                respond(exchange, new Reply(404, "application/json", "{\"error\":\"Session not found\"}"));
                return;
            }
            respond(exchange, new Reply(200, "text/event-stream",
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                            + "data: {\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"content\":[{\"type\":\"text\","
                            + "\"text\":\"{\\\"resolutionStatus\\\":\\\"RESOLVED\\\"}\"}]}}\n\n"));
        }

        private static void respond(HttpExchange exchange, Reply reply) throws IOException {
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
