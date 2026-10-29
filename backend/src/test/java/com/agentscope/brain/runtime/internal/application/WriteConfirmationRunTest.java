package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.identity.api.ExecutionSession;
import com.agentscope.brain.release.api.TextRelease;
import com.agentscope.brain.runtime.internal.integration.AgentTools;
import com.agentscope.brain.runtime.internal.integration.TextAgentExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mutating tool must pause for one user confirmation and execute only on the resumed run.
 * Synthetic model and tool only; no provider call.
 */
class WriteConfirmationRunTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test void writeToolPausesForConfirmationAndExecutesOnlyAfterResume() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.store.messages.add(new CopilotThreads.Message("prior", "ASSISTANT", "", "COMPLETE",
                    "[{\"id\":\"t1\",\"role\":\"tool\",\"content\":\"{\\\"employees\\\":[{\\\"phone\\\":\\\"15738515168\\\","
                            + "\\\"name\\\":\\\"测试用户\\\"}]}\",\"toolCallId\":\"call-0\",\"toolMessage\":true,"
                            + "\"assistantMessage\":false,\"userMessage\":false,\"systemMessage\":false,"
                            + "\"textContent\":\"{\\\"employees\\\":[{\\\"phone\\\":\\\"15738515168\\\","
                            + "\\\"name\\\":\\\"测试用户\\\"}]}\"}]"));
            var first = fixture.runs.start(fixture.store.id, fixture.store.run, "1", "帮我订个会议室", List.of())
                    .collectList().block(Duration.ofSeconds(15));
            var finished = first.stream().filter(e -> e instanceof AguiEvent.RunFinished)
                    .map(e -> (AguiEvent.RunFinished) e).findFirst().orElseThrow();
            assertThat(finished.outcome()).isInstanceOf(AguiEvent.RunFinishedInterruptOutcome.class);            var interrupts = ((AguiEvent.RunFinishedInterruptOutcome) finished.outcome()).interrupts();
            assertThat(interrupts).hasSize(1);
            assertThat(fixture.executions.get()).isZero();
            assertThat(fixture.store.state).isEqualTo("INTERRUPTED");
            assertThat(fixture.proposed).containsExactly("room.reserve");
            @SuppressWarnings("unchecked")
            var view = (Map<String, Object>) interrupts.get(0).metadata().get("toolInput");
            assertThat(view.get("attendeeNames")).isEqualTo(List.of("测试用户"));

            String resumeRun = UUID.randomUUID().toString();
            var resumed = fixture.runs.start(fixture.store.id, resumeRun, "1", "",
                    List.of(new CopilotThreads.Resume(interrupts.get(0).id(), "resolved", Map.of("approved", true))))
                    .collectList().block(Duration.ofSeconds(15));
            assertThat(fixture.executions.get()).isEqualTo(1);
            assertThat(fixture.store.state).isEqualTo("SUCCEEDED");
            assertThat(resumed).anyMatch(e -> e instanceof AguiEvent.ToolCallResult);
        }
    }

    static final class Fixture implements AutoCloseable {
        final Store store = new Store();
        final AtomicInteger executions = new AtomicInteger();
        final List<String> proposed = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final CopilotTextRuns runs;

        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                boolean executed = false;
                for (var message : request.path("messages")) {
                    if (!message.path("role").asText().equals("tool")) continue;
                    if (message.path("content").asText().contains("meetingId")) executed = true;
                }
                List<String> frames = new ArrayList<>();
                frames.add(chunk(Map.of("role", "assistant"), null));
                if (executed) {
                    frames.add(chunk(Map.of("content", "FINAL_ANSWER"), null));
                    frames.add(chunk(Map.of(), "stop"));
                } else {
                    frames.add(chunk(Map.of("tool_calls", List.of(Map.of("index", 0, "id", "call-1", "type", "function",
                            "function", Map.of("name", "room_reserve", "arguments",
                            "{\"roomId\":9,\"attendeePhones\":[\"15738515168\"],\"startTime\":\"2026-10-30 10:00:00\","
                                    + "\"durationMinutes\":30,\"meetingTheme\":\"probe\"}")))), null));
                    frames.add(chunk(Map.of(), "tool_calls"));
                }
                frames.add("data: [DONE]\n\n");
                byte[] bytes = String.join("", frames).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            TextRelease releases = (employee, release) -> new TextRelease.Configuration("Synthetic instructions.",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "published-model", "synthetic");
            var tools = new AgentTools(null, null, null, null) {
                @Override public Toolkit forRun(String userId, String releaseId) {
                    var toolkit = new Toolkit();
                    toolkit.registerAgentTool(new ToolBase(ToolBase.builder().name("room_reserve")
                            .description("Synthetic write").inputSchema(Map.of("type", "object", "properties", Map.of()))
                            .readOnly(false).concurrencySafe(false)) {
                        @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                                PermissionContextState context) {
                            return Mono.just(PermissionDecision.ask("confirm"));
                        }
                        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            executions.incrementAndGet();
                            return Mono.just(ToolResultBlock.text("{\"status\":\"CREATED\",\"meetingId\":123}"));
                        }
                    });
                    return toolkit;
                }
            };
            var operations = new WriteOperations(null, Clock.systemUTC()) {
                @Override public Proposal propose(String threadId, String runId, String userId, String toolName,
                        Map<String, Object> arguments) {
                    proposed.add(toolName);
                    return new Proposal(1L, 1L, "reserve-probe", java.time.Instant.now().plusSeconds(600));
                }
                @Override public void adopt(String interruptedRunId, String resumingRunId) { }
                @Override public void invalidateRun(String runId) { }
            };
            ExecutionSession sessions = () -> new ExecutionSession.Lease() {
                public byte[] sessionHash() { return new byte[32]; }
                public boolean valid() { return true; }
            };
            runs = new CopilotTextRuns(store, sessions, new TextAgentExecution(releases, tools), operations);
        }

        public void close() { server.stop(0); }
    }

    static String chunk(Map<String, Object> delta, String finish) throws java.io.IOException {
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0); choice.put("delta", delta); choice.put("finish_reason", finish);
        return "data: " + JSON.writeValueAsString(Map.of("id", "synthetic", "object", "chat.completion.chunk",
                "created", 1, "model", "published-model", "choices", List.of(choice))) + "\n\n";
    }

    static final class Store implements CopilotThreads {
        final String id = UUID.randomUUID().toString();
        volatile String run = UUID.randomUUID().toString();
        final StringBuilder content = new StringBuilder();
        final List<Message> messages = new CopyOnWriteArrayList<>();
        volatile String state = "RUNNING";
        volatile String displayJson;

        public Accepted accept(String t, String r, String e, String text, byte[] session, List<Resume> resume) {
            run = r;
            state = "RUNNING";
            return new Accepted(id, r, "release", List.copyOf(messages), "trusted-user", resume);
        }
        public String interruptedRun(String t) { return "interrupted-run"; }
        public Thread open(String t) {
            return new Thread(id, "1", "release", "Synthetic", "1", state.equals("RUNNING") ? run : null, null,
                    state, null, List.copyOf(messages));
        }
        public List<Summary> list(String e, String q, int l, int o) { return List.of(); }
        public void rename(String t, String v, String title) { }
        public boolean append(String t, String r, String delta) { content.append(delta); return true; }
        public boolean recordOutput(String t, String r, String delta, String json) {
            if (delta != null) content.append(delta);
            displayJson = json;
            return true;
        }
        public boolean heartbeat(String t, String r) { return true; }
        public boolean finish(String t, String r, String terminal, String code) {
            state = terminal;
            if (displayJson != null) messages.add(new Message("assistant-" + r, "ASSISTANT", content.toString(),
                    terminal.equals("SUCCEEDED") ? "COMPLETE" : "UNFINISHED", displayJson));
            return true;
        }
    }
}
