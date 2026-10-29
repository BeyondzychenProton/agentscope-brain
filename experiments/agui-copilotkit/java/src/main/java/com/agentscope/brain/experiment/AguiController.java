package com.agentscope.brain.experiment;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import java.time.Duration;

@RestController
public class AguiController {
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final ExecutionConfig oneAttempt = ExecutionConfig.builder().maxAttempts(1).build();
    private final AguiAdapterConfig config = AguiAdapterConfig.builder()
            .enableReasoning(false)
            .emitRunFinishedAfterError(false)
            .toolMergeMode(ToolMergeMode.AGENT_ONLY)
            .build();

    public AguiController() {
        baseUrl = required("MODEL_BASE_URL");
        apiKey = required("MODEL_API_KEY");
        modelName = required("MODEL_NAME");
    }

    private OpenAIChatModel newModel() {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .httpTransport(new StreamingTransport())
                .stream(true)
                .generateOptions(GenerateOptions.builder().executionConfig(oneAttempt).build())
                .build();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing model configuration: " + name);
        }
        return value;
    }

    @PostMapping(value = "/agui", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> run(@RequestBody String body) {
        RunAgentInput input = new AguiRequestBodyParser().parse(body);
        if (input.hasTools() || input.hasResume() || !input.hasMessages()
                || input.getMessages().stream().anyMatch(message ->
                    !(message.isUserMessage() || message.isAssistantMessage())
                    || message.hasBlocks() || message.hasToolCalls())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only text conversation is supported");
        }
        return Flux.using(
                () -> ReActAgent.builder()
                        .name("assistant")
                        .sysPrompt("You are a helpful assistant. Answer the user's request.")
                        .model(newModel())
                        .modelExecutionConfig(oneAttempt)
                        .maxRetries(1)
                        .build(),
                agent -> new AguiAgentAdapter(agent, config).run(input)
                        .map(event -> ServerSentEvent.builder(JsonUtils.getJsonCodec().toJson(event)).build())
                        .publish(events -> Flux.merge(events,
                                Flux.interval(Duration.ofSeconds(1))
                                        .map(tick -> ServerSentEvent.<String>builder().comment("keepalive").build())
                                        .takeUntilOther(events.ignoreElements()))),
                ReActAgent::close);
    }
}
