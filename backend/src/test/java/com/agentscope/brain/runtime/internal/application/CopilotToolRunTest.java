package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.identity.api.ExecutionSession;
import com.agentscope.brain.release.api.TextRelease;
import com.agentscope.brain.runtime.internal.integration.ReadonlyAgentTools;
import com.agentscope.brain.runtime.internal.integration.TextAgentExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

/** The production submit/observe/stop seam, real SDK and HTTP, synthetic model and tool adapters. */
class CopilotToolRunTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TAG = "<tool_call>{\"name\":\"identity.resolve\",\"arguments\":{\"name\":\"Synthetic\"}}</tool_call>";

    @ParameterizedTest
    @ValueSource(strings = {"native", "tag", "trailing", "alternative", "two-tools"})
    void toolsContinueInTheSamePublishedModelAndSurviveReopen(String mode) throws Exception {
        try (var fixture = new Fixture(mode)) {
            var events = fixture.runs.start(fixture.store.id, fixture.store.run, "1", "Synthetic inquiry")
                    .collectList().block(Duration.ofSeconds(15));
            int count = mode.equals("two-tools") ? 2 : 1;
            assertThat(fixture.toolCalls.get()).isEqualTo(count);
            assertThat(fixture.requests).hasSize(count + 1);
            assertThat(fixture.requests).allSatisfy(request -> {
                assertThat(request.path("model").asText()).isEqualTo("published-model");
                assertThat(request.path("stream").asBoolean()).isTrue();
                assertThat(request.path("tools").get(0).path("function").path("name").asText()).isEqualTo("identity_resolve");
            });
            assertThat(fixture.requests.get(count).path("messages").toString()).contains("tool-result", "tool_call_id");
            assertThat(fixture.store.state).isEqualTo("SUCCEEDED");
            assertThat(fixture.store.content.toString()).contains("FINAL_ANSWER").doesNotContain("<tool");
            assertThat(events).anyMatch(e -> e instanceof AguiEvent.ToolCallStart)
                    .anyMatch(e -> e instanceof AguiEvent.ToolCallResult)
                    .anyMatch(e -> e instanceof AguiEvent.RunFinished)
                    .noneMatch(e -> e instanceof AguiEvent.RunError);
            var before = RunDisplay.restore(fixture.store.displayJson);
            var restored = fixture.runs.connect(fixture.store.id).collectList().block(Duration.ofSeconds(5)).stream()
                    .filter(e -> e instanceof AguiEvent.MessagesSnapshot).map(e -> (AguiEvent.MessagesSnapshot)e)
                    .findFirst().orElseThrow().messages();
            assertThat(restored).isEqualTo(before);
            assertThat(restored).anyMatch(m -> m.hasToolCalls()).anyMatch(m -> m.isToolMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"incomplete", "unpublished", "invalid-json", "truncated"})
    void invalidControlOutputIsAnExplicitFailureAndNeverExecutes(String mode) throws Exception {
        try (var fixture = new Fixture(mode)) {
            var events = fixture.runs.start(fixture.store.id, fixture.store.run, "1", "Synthetic inquiry")
                    .collectList().block(Duration.ofSeconds(15));
            assertThat(fixture.toolCalls.get()).isZero();
            assertThat(fixture.requests).hasSize(1);
            assertThat(fixture.store.state).isEqualTo("FAILED");
            assertThat(fixture.store.content.toString()).doesNotContain("<tool");
            assertThat(events).anyMatch(e -> e instanceof AguiEvent.RunError).noneMatch(e -> e instanceof AguiEvent.RunFinished);
        }
    }

    @Test void slowToolKeepsItsLeaseAndCanBeStoppedWithoutWaitingForTheTool() throws Exception {
        try (var fixture = new Fixture("slow")) {
            var result = fixture.runs.start(fixture.store.id, fixture.store.run, "1", "Synthetic inquiry").collectList().toFuture();
            assertThat(fixture.toolStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.store.heartbeat.await(5, TimeUnit.SECONDS)).isTrue();
            // A new observer must neither execute the tool again nor duplicate partial text.
            var observed = new CopyOnWriteArrayList<AguiEvent>();
            var observer = fixture.runs.connect(fixture.store.id).subscribe(observed::add);
            long start = System.nanoTime();
            assertThat(fixture.runs.stop(fixture.store.id, fixture.store.run)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
            assertThat(result.get(5, TimeUnit.SECONDS)).anyMatch(e -> e instanceof AguiEvent.RunError);
            assertThat(fixture.toolCancelled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.store.state).isEqualTo("CANCELLED");
            assertThat(fixture.requests).hasSize(1);
            assertThat(observed).anyMatch(e -> e instanceof AguiEvent.ToolCallStart);
            observer.dispose();
        }
    }

    static final class Fixture implements AutoCloseable {
        final Store store = new Store();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger toolCalls = new AtomicInteger();
        final CountDownLatch toolStarted = new CountDownLatch(1), toolCancelled = new CountDownLatch(1);
        final HttpServer server;
        final CopilotTextRuns runs;
        Fixture(String mode) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/chat/completions", exchange -> {
                var request = JSON.readTree(exchange.getRequestBody().readAllBytes()); requests.add(request);
                long results = 0;
                for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) results++;
                List<String> frames = new ArrayList<>();
                frames.add(chunk(Map.of("role","assistant"), null));
                if (results >= (mode.equals("two-tools") ? 2 : 1)) {
                    frames.add(chunk(Map.of("content","FINAL_"), null));
                    frames.add(chunk(Map.of("content","ANSWER"), null));
                    frames.add(chunk(Map.of(),"stop"));
                } else if (List.of("native","two-tools","slow").contains(mode)) {
                    frames.add(chunk(Map.of("tool_calls",List.of(Map.of("index",0,"id","call-"+results,"type","function",
                            "function",Map.of("name","identity_resolve","arguments","{\"name\":\"Synthetic\"}")))),null));
                    frames.add(chunk(Map.of(),"tool_calls"));
                } else {
                    String text = switch(mode) {
                        case "trailing" -> "Checking." + TAG + "Please wait.";
                        case "alternative" -> TAG.replace("tool_call","tool");
                        case "incomplete" -> "Checking.<tool_call>{\"name\":";
                        case "unpublished" -> TAG.replace("identity.resolve","meeting.cancel");
                        case "invalid-json" -> "<tool_call>{broken}</tool_call>";
                        default -> TAG;
                    };
                    for (int i=0;i<text.length();i++) frames.add(chunk(Map.of("content",text.substring(i,i+1)),null));
                    frames.add(chunk(Map.of(), mode.equals("truncated") ? "length" : "stop"));
                }
                frames.add("data: [DONE]\n\n");
                byte[] bytes=String.join("",frames).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            TextRelease releases = (employee, release) -> new TextRelease.Configuration("Synthetic instructions.",
                    "http://127.0.0.1:"+server.getAddress().getPort(),"published-model","synthetic");
            var tools = new ReadonlyAgentTools(null,null,null) {
                @Override public Toolkit forRun(String userId, String releaseId) {
                    assertThat(userId).isEqualTo("trusted-user");
                    var toolkit = new Toolkit();
                    toolkit.registerAgentTool(new AgentTool() {
                        public String getName() { return "identity_resolve"; }
                        public String getDescription() { return "Synthetic lookup"; }
                        public Map<String,Object> getParameters() { return Map.of("type","object","properties",Map.of("name",Map.of("type","string"))); }
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            toolCalls.incrementAndGet(); toolStarted.countDown();
                            if (mode.equals("slow")) return Mono.<ToolResultBlock>never().doOnCancel(toolCancelled::countDown);
                            return Mono.just(ToolResultBlock.text("tool-result"));
                        }
                    });
                    return toolkit;
                }
            };
            ExecutionSession sessions = () -> new ExecutionSession.Lease() {
                public byte[] sessionHash() { return new byte[32]; }
                public boolean valid() { return true; }
            };
            runs = new CopilotTextRuns(store,sessions,new TextAgentExecution(releases,tools));
        }
        public void close() { if (store.state.equals("RUNNING")) runs.stop(store.id,store.run); server.stop(0); }
    }
    static String chunk(Map<String,Object> delta,String finish) throws java.io.IOException {
        var choice=new java.util.LinkedHashMap<String,Object>(); choice.put("index",0);choice.put("delta",delta);choice.put("finish_reason",finish);
        return "data: "+JSON.writeValueAsString(Map.of("id","synthetic","object","chat.completion.chunk","created",1,
                "model","published-model","choices",List.of(choice)))+"\n\n";
    }
    static final class Store implements CopilotThreads {
        final String id=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        final StringBuilder content=new StringBuilder();
        final CountDownLatch heartbeat=new CountDownLatch(1);
        volatile String state="RUNNING",displayJson;
        public Accepted accept(String t,String r,String e,String text,byte[] session) { return new Accepted(id,run,"release",List.of(),"trusted-user"); }
        public CopilotThreads.Thread open(String t) { return new CopilotThreads.Thread(id,"1","release","Synthetic","1",
                state.equals("RUNNING")?run:null,null,state,null,List.of(new Message("stored", "ASSISTANT",content.toString(),
                state.equals("RUNNING")?"STREAMING":state.equals("SUCCEEDED")?"COMPLETE":"UNFINISHED",displayJson))); }
        public List<Summary> list(String e,String q,int l,int o) { return List.of(); }
        public void rename(String t,String v,String title) { }
        public boolean append(String t,String r,String delta) { content.append(delta); return true; }
        public boolean recordOutput(String t,String r,String delta,String json) { if(delta!=null) content.append(delta);displayJson=json;return true; }
        public boolean heartbeat(String t,String r) { heartbeat.countDown();return true; }
        public boolean finish(String t,String r,String terminal,String code) { state=terminal;return true; }
    }
}
