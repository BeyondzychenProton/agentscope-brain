package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.identity.api.ExecutionSession;
import com.agentscope.brain.runtime.internal.integration.TextAgentExecution;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Manages live subscriptions; the database, not this map, owns thread and message facts. */
@Service
@ConditionalOnProperty(name = "brain.copilot.enabled", havingValue = "true")
public class CopilotTextRuns {
    private static final Logger LOG = LoggerFactory.getLogger(CopilotTextRuns.class);
    private final CopilotThreads threads;
    private final ExecutionSession sessions;
    private final TextAgentExecution agent;
    private final Map<String, Active> live = new ConcurrentHashMap<>();
    private final Object[] stripes = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public CopilotTextRuns(CopilotThreads threads, ExecutionSession sessions, TextAgentExecution agent) {
        this.threads = threads; this.sessions = sessions; this.agent = agent;
    }

    public Flux<AguiEvent> start(String threadId, String runId, String employeeId, String text) {
        synchronized (stripe(threadId)) {
            var lease = sessions.capture();
            var accepted = threads.accept(threadId, runId, employeeId, text, lease.sessionHash());
            var active = new Active(accepted.threadId(), accepted.runId(), lease);
            var previous = live.put(threadId, active);
            if (previous != null) previous.terminate("INTERRUPTED", "EXECUTION_INTERRUPTED");
            active.start(employeeId, accepted, text);
            return active.events.asFlux();
        }
    }

    public Flux<AguiEvent> connect(String threadId) {
        synchronized (stripe(threadId)) {
            var thread = threads.open(threadId);
            var active = live.get(threadId);
            if (active != null && !active.runId.equals(thread.activeRunId())) active = null;
            if (active == null && thread.activeRunId() != null) thread = threads.open(threadId);
            String runId = active == null ? UUID.randomUUID().toString() : active.runId;
            boolean liveRun = active != null;
            String currentRun = thread.activeRunId();
            var history = thread.messages().stream().filter(m -> activeRunMessageVisible(m, currentRun, liveRun))
                    .flatMap(m -> (m.displayJson() != null ? RunDisplay.restore(m.displayJson())
                            : java.util.List.of(m.role().equals("USER") ? AguiMessage.userMessage(m.id(), m.content())
                            : AguiMessage.assistantMessage(m.id(), m.content()))).stream()).toList();
            Flux<AguiEvent> prefix = Flux.just(new AguiEvent.RunStarted(threadId, runId),
                    new AguiEvent.MessagesSnapshot(threadId, runId, history));
            if (active == null) return prefix.concatWithValues(new AguiEvent.RunFinished(threadId, runId));
            return prefix.concatWith(active.events.asFlux().filter(event -> !(event instanceof AguiEvent.RunStarted)));
        }
    }

    private Object stripe(String threadId) {
        if (threadId == null) throw new IllegalArgumentException("Thread ID required");
        return stripes[(threadId.hashCode() & Integer.MAX_VALUE) % stripes.length];
    }

    private static boolean activeRunMessageVisible(CopilotThreads.Message message, String runId, boolean liveRun) {
        return !(liveRun && runId != null && message.status().equals("STREAMING"));
    }

    public boolean stop(String threadId, String runId) {
        var thread = threads.open(threadId);
        var active = live.get(threadId);
        if (active == null || !active.runId.equals(runId) || !runId.equals(thread.activeRunId())) return false;
        active.terminate("CANCELLED", "USER_STOPPED");
        return true;
    }

    private final class Active {
        private final String threadId;
        private final String runId;
        private final ExecutionSession.Lease lease;
        private final Sinks.Many<AguiEvent> events = Sinks.many().replay().all();
        private Disposable execution;
        private Disposable monitor;
        private boolean ended;
        private boolean sawTool;
        private boolean finalTextAfterTool;
        private long characters;
        private int eventCount;
        private final RunDisplay display = new RunDisplay();

        Active(String threadId, String runId, ExecutionSession.Lease lease) {
            this.threadId = threadId; this.runId = runId; this.lease = lease;
        }
        synchronized void start(String employeeId, CopilotThreads.Accepted accepted, String text) {
            monitor = Flux.interval(Duration.ofSeconds(2)).publishOn(Schedulers.boundedElastic())
                    .subscribe(tick -> checkLease(), error -> {
                        LOG.warn("Copilot text run lease check failed threadId={} runId={}", threadId, runId, error);
                        terminate("INTERRUPTED", "SESSION_UNAVAILABLE");
                    });
            execution = agent.run(employeeId, accepted, text).subscribeOn(Schedulers.boundedElastic())
                    .publishOn(Schedulers.boundedElastic()).subscribe(this::event,
                            error -> {
                                LOG.warn("Copilot text run failed threadId={} runId={} cause={}", threadId, runId, describe(error), error);
                                terminate("FAILED", "MODEL_STREAM_FAILED");
                            },
                            () -> {
                                LOG.warn("Copilot text run completed without RunFinished threadId={} runId={}", threadId, runId);
                                terminate("FAILED", "MODEL_STREAM_INCOMPLETE");
                            });
        }
        synchronized void event(AguiEvent event) {
            if (ended) return;
            try {
                if (++eventCount > 20000) { terminate("FAILED", "MODEL_OUTPUT_TOO_LARGE"); return; }
                if (event instanceof AguiEvent.ToolCallStart || event instanceof AguiEvent.ToolCallChunk) {
                    sawTool = true; finalTextAfterTool = false;
                }
                if (event instanceof AguiEvent.ToolCallResult) finalTextAfterTool = false;
                String delta = event instanceof AguiEvent.TextMessageContent content ? content.delta()
                        : event instanceof AguiEvent.TextMessageChunk chunk ? chunk.delta() : null;
                String reasoning = event instanceof AguiEvent.ReasoningMessageContent content ? content.delta()
                        : event instanceof AguiEvent.ReasoningMessageChunk chunk ? chunk.delta() : null;
                if (reasoning != null) characters += reasoning.length();
                if (characters > 1_048_576) { terminate("FAILED", "MODEL_OUTPUT_TOO_LARGE"); return; }
                if (delta != null && !delta.isEmpty()) {
                    characters += delta.length();
                    if (characters > 1_048_576) { terminate("FAILED", "MODEL_OUTPUT_TOO_LARGE"); return; }
                    if (sawTool && !delta.isBlank()) finalTextAfterTool = true;
                }
                if (display.apply(event)) {
                    String view = display.json();
                    if (view.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4_194_304) {
                        terminate("FAILED", "MODEL_OUTPUT_TOO_LARGE"); return;
                    }
                    if (!threads.recordOutput(threadId, runId, delta, view)) {
                        terminate("INTERRUPTED", "EXECUTION_INTERRUPTED"); return;
                    }
                }
                if (event instanceof AguiEvent.RunError failure) {
                    LOG.warn("Copilot text run received RunError threadId={} runId={} message={} code={}",
                            threadId, runId, failure.message(), failure.code());
                    String code = failure.message() != null && failure.message().matches("MODEL_[A-Z0-9_]{1,56}")
                            ? failure.message() : "MODEL_STREAM_FAILED";
                    terminate("FAILED", code); return;
                }
                if (event instanceof AguiEvent.RunFinished) {
                    if (sawTool && !finalTextAfterTool) { terminate("FAILED", "MODEL_FINAL_ANSWER_MISSING"); return; }
                    if (!threads.finish(threadId, runId, "SUCCEEDED", null)) {
                        terminate("INTERRUPTED", "EXECUTION_INTERRUPTED"); return;
                    }
                    ended = true;
                    events.tryEmitNext(event);
                    events.tryEmitComplete();
                    close();
                    return;
                }
                events.tryEmitNext(event);
            } catch (RuntimeException failure) {
                if (ended) return;
                String code = failure.getMessage();
                if ("MODEL_OUTPUT_TOO_LARGE".equals(code)) terminate("FAILED", code);
                else if ("EXECUTION_INTERRUPTED".equals(code)) terminate("INTERRUPTED", code);
                else {
                    LOG.warn("Copilot text run store failed threadId={} runId={}", threadId, runId, failure);
                    terminate("FAILED", "THREAD_STORE_UNAVAILABLE");
                }
            }
        }
        synchronized void checkLease() {
            if (!ended && (!lease.valid() || !threads.heartbeat(threadId, runId)))
                terminate("CANCELLED", "SESSION_EXPIRED");
        }
        synchronized void terminate(String state, String code) {
            if (ended) return;
            if ("FAILED".equals(state) || "INTERRUPTED".equals(state))
                LOG.warn("Copilot text run ended threadId={} runId={} state={} code={}", threadId, runId, state, code);
            ended = true;
            try { threads.finish(threadId, runId, state, code); }
            finally {
                events.tryEmitNext(new AguiEvent.RunError(threadId, runId, "回复未完成", code));
                events.tryEmitComplete();
                close();
            }
        }
        private static String describe(Throwable error) {
            var parts = new StringBuilder();
            var seen = 0;
            for (var current = error; current != null && seen < 6; current = current.getCause(), seen++) {
                if (seen > 0) parts.append(" <- ");
                parts.append(current.getClass().getSimpleName());
                if (current.getMessage() != null && !current.getMessage().isBlank())
                    parts.append(": ").append(brief(current.getMessage()));
            }
            return parts.toString();
        }
        private static String brief(String value) {
            var compact = value.replaceAll("\\s+", " ")
                    .replaceAll("(?i)(bearer\\s+)\\S+", "$1[redacted]")
                    .replaceAll("sk-[A-Za-z0-9._\\-]{8,}", "sk-[redacted]").trim();
            return compact.length() <= 400 ? compact : compact.substring(0, 400) + "...";
        }
        private void close() {
            if (execution != null) execution.dispose();
            if (monitor != null) monitor.dispose();
            live.remove(threadId, this);
        }
    }
}
