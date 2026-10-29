package com.agentscope.brain.runtime.internal.integration;

import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.release.api.TextRelease;
import com.agentscope.brain.runtime.internal.application.RunDisplay;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** One request-scoped SDK agent owns the entire streamed model/tool loop. */
@Component
@ConditionalOnProperty(name = "brain.copilot.enabled", havingValue = "true")
public class TextAgentExecution {
    private static final Logger LOG = LoggerFactory.getLogger(TextAgentExecution.class);
    private static final ExecutionConfig ONE_ATTEMPT = ExecutionConfig.builder().maxAttempts(1).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AguiAdapterConfig ADAPTER = AguiAdapterConfig.builder().enableReasoning(true)
            .emitRunFinishedAfterError(false).toolMergeMode(ToolMergeMode.AGENT_ONLY).build();
    private static final Duration KEEP = Duration.ofMinutes(10);
    private final TextRelease releases;
    private final AgentTools tools;
    /**
     * Agents paused on a confirmation keep their pending tool call in memory; SDK confirmation
     * resumes that same instance. Nothing else may own this state between HTTP requests.
     */
    private final Map<String, Kept> interrupted = new ConcurrentHashMap<>();

    public TextAgentExecution(TextRelease releases, AgentTools tools) {
        this.releases = releases; this.tools = tools;
    }

    private record Kept(ReActAgent agent, Instant at, List<AguiEvent.Interrupt> interrupts) { }

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
            boolean resuming = !accepted.resume().isEmpty();
            Kept kept = interrupted.remove(accepted.threadId());
            if (kept != null && (!resuming || kept.at().isBefore(Instant.now().minus(KEEP)))) {
                if (!resuming) LOG.info("Closing a superseded interrupted agent threadId={}", accepted.threadId());
                closeQuietly(kept.agent());
                kept = null;
            }
            LOG.info("Copilot text run calling model threadId={} runId={} modelId={} baseUrl={} resume={} reuse={}",
                    accepted.threadId(), accepted.runId(), release.modelId(), release.baseUrl(),
                    accepted.resume().size(), kept != null);
            List<AguiMessage> history = new ArrayList<>();
            ReActAgent agent;
            if (kept != null) {
                agent = kept.agent();
            } else {
                agent = buildAgent(release, accepted.userId(), accepted.releaseId());
                if (!resuming) {
                    for (var message : accepted.history()) {
                        if (message.displayJson() != null) {
                            // Structured display keeps assistant tool calls, which a resumed run needs.
                            history.addAll(RunDisplay.restore(message.displayJson()));
                        } else {
                            history.add(message.role().equals("USER")
                                    ? AguiMessage.userMessage(message.id(), message.content())
                                    : AguiMessage.assistantMessage(message.id(), message.content()));
                        }
                    }
                    history.add(AguiMessage.userMessage(UUID.randomUUID().toString(), submittedText));
                }
            }
            var input = new RunAgentInput.Builder().threadId(accepted.threadId()).runId(accepted.runId())
                    .messages(history).tools(List.of()).context(List.of())
                    .resume(accepted.resume().stream().map(r -> new AguiResume(r.interruptId(), r.status(), r.payload())).toList())
                    .build();
            // A resumed agent already owns the conversation; only the confirmation answer is new.
            var runtimeContext = resumeContext(kept != null ? kept.interrupts() : List.of(),
                    accepted.resume(), history);
            var paused = new AtomicBoolean();
            var pausedInterrupts = new java.util.concurrent.atomic.AtomicReference<List<AguiEvent.Interrupt>>(List.of());
            var adapter = new AguiAgentAdapter(agent, ADAPTER);
            return adapter.run(input, runtimeContext)
                    .doOnNext(event -> {
                        if (event instanceof AguiEvent.RunFinished finished
                                && finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome pending
                                && !pending.interrupts().isEmpty()) {
                            paused.set(true);
                            pausedInterrupts.set(pending.interrupts());
                        }
                    })
                    .doFinally(signal -> {
                        if (paused.get()) {
                            interrupted.put(accepted.threadId(),
                                    new Kept(agent, Instant.now(), pausedInterrupts.get()));
                        } else {
                            closeQuietly(agent);
                        }
                        evictExpired();
                    });
        });
    }

    private ReActAgent buildAgent(TextRelease.Configuration release, String userId, String releaseId) {
        var toolkit = tools.forRun(userId, releaseId);
        var model = OpenAIChatModel.builder().baseUrl(release.baseUrl()).apiKey(release.apiKey())
                .modelName(release.modelId()).httpTransport(new TextStreamingTransport()).stream(true)
                .generateOptions(GenerateOptions.builder().executionConfig(ONE_ATTEMPT).build()).build();
        return ReActAgent.builder().name("assistant").sysPrompt(release.instructions()
                + "\n通过已提供的工具调用获取事实，收到工具结果后继续回答。不要在正文中输出工具 XML 或 JSON 调用。"
                + "\n预约或取消会议属于写操作：调用工具前先用中文向用户复述会议室、时间、参会人和主题，"
                + "平台会弹出一次性确认，用户确认后才真正提交；不要自行编造 requestId。")
                .model(model).toolkit(toolkit).maxIters(8)
                .modelExecutionConfig(ONE_ATTEMPT).toolExecutionConfig(ONE_ATTEMPT).maxRetries(1).build();
    }

    private void evictExpired() {
        Instant limit = Instant.now().minus(KEEP);
        interrupted.entrySet().removeIf(entry -> {
            if (!entry.getValue().at().isBefore(limit)) return false;
            closeQuietly(entry.getValue().agent());
            return true;
        });
    }

    private static void closeQuietly(ReActAgent agent) {
        try { agent.close(); } catch (RuntimeException failure) {
            LOG.warn("Closing the Copilot agent failed", failure);
        }
    }

    @PreDestroy
    void closeAll() {
        interrupted.values().forEach(kept -> closeQuietly(kept.agent()));
        interrupted.clear();
    }

    /**
     * Rebuilds the interrupt identity a resume entry points at. A live interrupted agent already
     * reported its interrupts; otherwise the identity is recovered from the stored tool calls.
     */
    static RuntimeContext resumeContext(List<AguiEvent.Interrupt> known, List<CopilotThreads.Resume> resume,
            List<AguiMessage> history) {
        var context = RuntimeContext.empty();
        if (resume.isEmpty()) return context;
        var interrupts = new LinkedHashMap<String, AguiEvent.Interrupt>();
        for (var interrupt : known) interrupts.put(interrupt.id(), interrupt);
        for (var answer : resume) {
            String id = answer.interruptId();
            if (interrupts.containsKey(id)) continue;
            var rebuilt = rebuildInterrupt(id, history);
            if (rebuilt != null) interrupts.put(id, rebuilt);
            else LOG.warn("Resume answer does not match a known interrupt; the write will not run id={}", id);
        }
        context.put(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY, interrupts);
        return context;
    }

    private static AguiEvent.Interrupt rebuildInterrupt(String id, List<AguiMessage> history) {
        int split = id.lastIndexOf(':');
        String replyId = split < 0 ? "" : id.substring(0, split);
        String toolCallId = split < 0 ? id : id.substring(split + 1);
        String name = null;
        String arguments = "{}";
        for (var message : history) {
            if (message.getToolCalls() == null) continue;
            for (var call : message.getToolCalls()) {
                if (!toolCallId.equals(call.getId()) || call.getFunction() == null) continue;
                name = call.getFunction().getName();
                arguments = call.getFunction().getArguments();
            }
        }
        if (name == null) return null;
        Map<String, Object> toolInput;
        try {
            toolInput = JSON.readValue(arguments, new TypeReference<Map<String, Object>>() { });
        } catch (Exception invalid) {
            toolInput = Map.of();
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put(AguiInterruptConstants.METADATA_AGENTSCOPE_INTERRUPT_KIND,
                AguiInterruptConstants.INTERRUPT_KIND_PERMISSION_CONFIRM);
        metadata.put(AguiInterruptConstants.METADATA_TOOL_NAME, name);
        metadata.put(AguiInterruptConstants.METADATA_TOOL_INPUT, toolInput);
        metadata.put(AguiInterruptConstants.METADATA_TOOL_CONTENT, arguments);
        metadata.put(AguiInterruptConstants.METADATA_REPLY_ID, replyId);
        return new AguiEvent.Interrupt(id, AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON,
                "", toolCallId, null, null, metadata);
    }
}
