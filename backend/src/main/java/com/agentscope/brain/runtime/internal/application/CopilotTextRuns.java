package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.conversation.api.CopilotThreads;
import com.agentscope.brain.identity.api.ExecutionSession;
import com.agentscope.brain.runtime.internal.integration.TextAgentExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CopilotThreads threads;
    private final ExecutionSession sessions;
    private final TextAgentExecution agent;
    private final WriteOperations operations;
    private final Map<String, Active> live = new ConcurrentHashMap<>();
    private final Object[] stripes = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public CopilotTextRuns(CopilotThreads threads, ExecutionSession sessions, TextAgentExecution agent,
            WriteOperations operations) {
        this.threads = threads; this.sessions = sessions; this.agent = agent; this.operations = operations;
    }

    public Flux<AguiEvent> start(String threadId, String runId, String employeeId, String text, String userMessageId,
            List<CopilotThreads.Resume> resume) {
        synchronized (stripe(threadId)) {
            var lease = sessions.capture();
            List<CopilotThreads.Resume> answers = resume == null ? List.of() : List.copyOf(resume);
            if (!answers.isEmpty()) prepareResume(threadId, runId, answers);
            var accepted = threads.accept(threadId, runId, employeeId, text, userMessageId, lease.sessionHash(), answers);
            var active = new Active(accepted.threadId(), accepted.runId(), lease, accepted.userId());
            var previous = live.put(threadId, active);
            if (previous != null) previous.terminate("INTERRUPTED", "EXECUTION_INTERRUPTED");
            active.start(employeeId, accepted, text);
            return active.events.asFlux();
        }
    }

    /** Moves the interrupted run's proposals onto the resuming run, or retires them on refusal. */
    private void prepareResume(String threadId, String runId, List<CopilotThreads.Resume> answers) {
        String interrupted = threads.interruptedRun(threadId);
        if (interrupted == null) return;
        boolean denied = answers.stream().anyMatch(answer -> "cancelled".equals(answer.status())
                || (answer.payload() != null && Boolean.FALSE.equals(answer.payload().get("approved"))));
        if (denied) operations.invalidateRun(interrupted);
        else operations.adopt(interrupted, runId);
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

    private record Lookup(Map<String, String> names, Map<String, String> rooms) { }

    private final class Active {
        private final String threadId;
        private final String runId;
        private final ExecutionSession.Lease lease;
        private final String userId;
        private final Sinks.Many<AguiEvent> events = Sinks.many().replay().all();
        private Disposable execution;
        private Disposable monitor;
        private boolean ended;
        private boolean sawTool;
        private boolean finalTextAfterTool;
        private long characters;
        private int eventCount;
        private final RunDisplay display = new RunDisplay();
        private Lookup priorLookup = new Lookup(Map.of(), Map.of());

        Active(String threadId, String runId, ExecutionSession.Lease lease, String userId) {
            this.threadId = threadId; this.runId = runId; this.lease = lease; this.userId = userId;
        }
        synchronized void start(String employeeId, CopilotThreads.Accepted accepted, String text) {
            priorLookup = lookup(accepted.history());
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
                // SDK RunStarted carries its model replay as raw input. The AG-UI client appends
                // input.messages to the visible chat, duplicating users/tools under SDK ids.
                // Only connect() may restore the transcript from the platform's stored display.
                if (event instanceof AguiEvent.RunStarted)
                    event = new AguiEvent.RunStarted(threadId, runId);
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
                if (event instanceof AguiEvent.RunFinished finished) {
                    if (finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome pending
                            && !pending.interrupts().isEmpty()) {
                        // The write proposal is recorded before the user sees the confirmation card.
                        for (var interrupt : pending.interrupts()) propose(interrupt);
                        if (!threads.finish(threadId, runId, "INTERRUPTED", "CONFIRMATION_REQUIRED")) {
                            terminate("INTERRUPTED", "EXECUTION_INTERRUPTED"); return;
                        }
                        ended = true;
                        events.tryEmitNext(enriched(finished, pending.interrupts()));
                        events.tryEmitComplete();
                        close();
                        return;
                    }
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
        /** Records the write proposal carried by a permission interrupt; nothing is sent yet. */
        private void propose(AguiEvent.Interrupt interrupt) {
            Map<String, Object> metadata = interrupt.metadata() == null ? Map.of() : interrupt.metadata();
            if (!AguiInterruptConstants.INTERRUPT_KIND_PERMISSION_CONFIRM.equals(
                    String.valueOf(metadata.get(AguiInterruptConstants.METADATA_AGENTSCOPE_INTERRUPT_KIND)))) return;
            String registered = String.valueOf(metadata.get(AguiInterruptConstants.METADATA_TOOL_NAME));
            String tool = AgentToolCatalog.catalogName(registered);
            if (!AgentToolCatalog.MUTATING.contains(tool)) {
                LOG.warn("Permission interrupt for an unknown tool registered={} catalog={}", registered, tool);
                return;
            }
            Object raw = metadata.get(AguiInterruptConstants.METADATA_TOOL_INPUT);
            @SuppressWarnings("unchecked")
            Map<String, Object> input = raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            operations.propose(threadId, runId, userId, tool, input);
        }

        /** Adds human-readable attendees and room to the copy the user sees; the resume path keeps the original. */
        private AguiEvent enriched(AguiEvent.RunFinished finished, List<AguiEvent.Interrupt> interrupts) {
            List<AguiEvent.Interrupt> view = new ArrayList<>(interrupts.size());
            Lookup lookup = lookup();
            for (var interrupt : interrupts) view.add(enrich(interrupt, lookup));
            return new AguiEvent.RunFinished(finished.threadId(), finished.runId(), finished.result(),
                    new AguiEvent.RunFinishedInterruptOutcome(view), finished.timestamp(), finished.rawEvent());
        }

        private AguiEvent.Interrupt enrich(AguiEvent.Interrupt interrupt, Lookup lookup) {
            Map<String, Object> metadata = interrupt.metadata();
            if (metadata == null) return interrupt;
            String tool = AgentToolCatalog.catalogName(
                    String.valueOf(metadata.get(AguiInterruptConstants.METADATA_TOOL_NAME)));
            if (!AgentToolCatalog.MUTATING.contains(tool)) return interrupt;
            Object raw = metadata.get(AguiInterruptConstants.METADATA_TOOL_INPUT);
            if (!(raw instanceof Map<?, ?> map)) return interrupt;
            Map<String, Object> input = new LinkedHashMap<>();
            map.forEach((key, value) -> input.put(String.valueOf(key), value));
            if (input.get("attendeePhones") instanceof List<?> phones && !phones.isEmpty()) {
                input.put("attendeeNames", phones.stream().map(String::valueOf)
                        .map(phone -> lookup.names().getOrDefault(phone, mask(phone))).toList());
            }
            String room = lookup.rooms().get(String.valueOf(input.get("roomId")));
            if (room != null && !room.isBlank()) input.put("roomName", room);
            Map<String, Object> copy = new LinkedHashMap<>(metadata);
            copy.put(AguiInterruptConstants.METADATA_TOOL_INPUT, input);
            return new AguiEvent.Interrupt(interrupt.id(), interrupt.reason(), interrupt.message(),
                    interrupt.toolCallId(), interrupt.responseSchema(), interrupt.expiresAt(), copy);
        }

        /** Employee and room names already returned by this conversation's lookups. */
        private Lookup lookup() {
            Map<String, String> names = new java.util.HashMap<>(priorLookup.names());
            Map<String, String> rooms = new java.util.HashMap<>(priorLookup.rooms());
            collect(display.messages(), names, rooms);
            return new Lookup(names, rooms);
        }

        private static Lookup lookup(List<CopilotThreads.Message> history) {
            Map<String, String> names = new java.util.HashMap<>();
            Map<String, String> rooms = new java.util.HashMap<>();
            for (var message : history) {
                if (message.displayJson() == null) continue;
                try {
                    collect(RunDisplay.restore(message.displayJson()), names, rooms);
                } catch (RuntimeException unreadable) {
                    // A legacy display is simply skipped.
                }
            }
            return new Lookup(names, rooms);
        }

        private static void collect(List<AguiMessage> messages, Map<String, String> names, Map<String, String> rooms) {
            for (var message : messages) {
                String text = message.getRole().equals("tool") ? message.getTextContent() : null;
                if (text == null || !text.contains("{\"")) continue;
                try {
                    var root = JSON.readTree(text);
                    for (var employee : root.path("employees")) {
                        String phone = employee.path("phone").asText("");
                        String name = employee.path("name").asText("");
                        if (!phone.isBlank() && !name.isBlank()) names.put(phone, name);
                    }
                    for (var room : root.path("rooms")) {
                        String id = room.path("chamberId").asText("");
                        String name = room.path("chamberName").asText("");
                        if (!id.isBlank() && !name.isBlank()) rooms.put(id, name);
                    }
                } catch (Exception unreadable) {
                    // Not a directory payload.
                }
            }
        }

        private static String mask(String phone) {
            return phone != null && phone.length() >= 7
                    ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : phone;
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
