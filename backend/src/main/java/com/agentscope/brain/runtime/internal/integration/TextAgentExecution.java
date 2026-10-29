package com.agentscope.brain.runtime.internal.integration;

import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.release.api.TextRelease;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** One SDK agent and one model HTTP request for a single accepted text turn. */
@Component
@ConditionalOnProperty(name = "brain.copilot.enabled", havingValue = "true")
public class TextAgentExecution {
    private static final Logger LOG = LoggerFactory.getLogger(TextAgentExecution.class);
    private static final ExecutionConfig ONE_ATTEMPT = ExecutionConfig.builder().maxAttempts(1).build();
    private static final AguiAdapterConfig ADAPTER = AguiAdapterConfig.builder().enableReasoning(false)
            .emitRunFinishedAfterError(false).toolMergeMode(ToolMergeMode.AGENT_ONLY).build();
    private final TextRelease releases;

    public TextAgentExecution(TextRelease releases) { this.releases = releases; }

    public Flux<AguiEvent> run(String employeeId, CopilotThreads.Accepted accepted, String submittedText) {
        return Flux.defer(() -> {
            TextRelease.Configuration release;
            try {
                release = releases.textConfiguration(employeeId, accepted.releaseId());
            } catch (RuntimeException failure) {
                LOG.warn("Copilot text configuration failed employeeId={} releaseId={} threadId={} runId={}",
                        employeeId, accepted.releaseId(), accepted.threadId(), accepted.runId(), failure);
                return Flux.error(failure);
            }
            LOG.info("Copilot text run calling model threadId={} runId={} modelId={} baseUrl={}",
                    accepted.threadId(), accepted.runId(), release.modelId(), release.baseUrl());
            List<AguiMessage> history = new ArrayList<>();
            for (var message : accepted.history()) {
                history.add(message.role().equals("USER") ? AguiMessage.userMessage(message.id(), message.content())
                        : AguiMessage.assistantMessage(message.id(), message.content()));
            }
            history.add(AguiMessage.userMessage(UUID.randomUUID().toString(), submittedText));
            var input = new RunAgentInput.Builder().threadId(accepted.threadId()).runId(accepted.runId())
                    .messages(history).tools(List.of()).context(List.of()).build();
            return Flux.using(() -> {
                var model = OpenAIChatModel.builder().baseUrl(release.baseUrl()).apiKey(release.apiKey())
                        .modelName(release.modelId()).httpTransport(new TextStreamingTransport()).stream(true)
                        .generateOptions(GenerateOptions.builder().executionConfig(ONE_ATTEMPT).build()).build();
                return ReActAgent.builder().name("assistant").sysPrompt(release.instructions()).model(model)
                        .modelExecutionConfig(ONE_ATTEMPT).maxRetries(1).build();
            }, agent -> new AguiAgentAdapter(agent, ADAPTER).run(input), ReActAgent::close);
        });
    }
}
