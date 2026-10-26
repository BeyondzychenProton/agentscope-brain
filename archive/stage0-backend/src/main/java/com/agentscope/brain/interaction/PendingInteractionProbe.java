package com.agentscope.brain.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.MessageUtils;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * #26：在锁定 JDBC Store 上贯通唯一平台输入 pending 的挂起、就绪与合成回答消费。
 * 就绪与消费分别下结论；事件、进程句柄或 pending 消失单独都不能冒充通过。
 */
public final class PendingInteractionProbe {
    public static final String TOOL_NAME = "request_user_input";
    public static final String AGENT_STATE_KEY = "agent_state";
    static final String TABLE_NAME = "issue26_agent_state";

    private final DataSource dataSource;
    private final AbstractJdbcDialect dialect;
    private final JdbcAgentStateStore store;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolBodyCalls = new AtomicInteger();
    private final AtomicInteger otherToolSends = new AtomicInteger();
    private final AtomicInteger validationPasses = new AtomicInteger();
    private final List<AgentEvent> lastEvents = new CopyOnWriteArrayList<>();
    private final AtomicReference<PlatformCard> card = new AtomicReference<>();

    public PendingInteractionProbe(DataSource dataSource) {
        this.dataSource = dataSource;
        this.dialect = AbstractJdbcDialect.from(dataSource)
                .tablePrefix("issue26_")
                .sessionStateTableName(TABLE_NAME)
                .autoCreateTable(false)
                .build();
        this.store = new JdbcAgentStateStore(dataSource, dialect, true);
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    public int toolBodyCalls() {
        return toolBodyCalls.get();
    }

    public int otherToolSends() {
        return otherToolSends.get();
    }

    public int validationPasses() {
        return validationPasses.get();
    }

    public PlatformCard card() {
        return card.get();
    }

    public List<AgentEvent> lastEvents() {
        return List.copyOf(lastEvents);
    }

    public SuspendOutcome suspend(Binding binding, Map<String, Object> proposal) {
        ScriptedModel model = ScriptedModel.onceTool(binding.toolCallId(), TOOL_NAME, proposal);
        RequestUserInputTool tool = new RequestUserInputTool(toolBodyCalls, validationPasses, proposal);
        ReActAgent agent = buildAgent(model, tool, store);
        lastEvents.clear();
        RuntimeContext ctx = context(binding);
        List<AgentEvent> events = agent.streamEvents(List.of(userMsg("collect form")), ctx)
                .doOnNext(lastEvents::add)
                .collectList()
                .block();
        modelCalls.addAndGet(model.invocations());
        Msg result = findResult(events);
        boolean eventSeen = lastEvents.stream().anyMatch(RequireExternalExecutionEvent.class::isInstance);
        RawCheckpoint raw = readRaw(binding);
        VersionedState<AgentState> independent = independentRead(binding);
        PendingFact pending = pendingFrom(independent.value());
        SaveFact save = SaveFact.from(raw, independent);
        return new SuspendOutcome(binding, result, eventSeen, save, pending, proposal, agent, model, tool);
    }

    /**
     * 仅有等待事件、无成功保存／独立读回时，不得开放可提交卡。
     */
    public ReadyVerdict assessReady(SuspendOutcome outcome) {
        if (outcome == null) {
            return ReadyVerdict.reconciling("NO_OUTCOME");
        }
        if (outcome.result() == null || outcome.result().getGenerateReason() != GenerateReason.TOOL_SUSPENDED) {
            return ReadyVerdict.reconciling("CALL_NOT_SUSPENDED");
        }
        if (!outcome.eventSeen()) {
            return ReadyVerdict.reconciling("EVENT_MISSING");
        }
        if (!outcome.save().persisted()) {
            return ReadyVerdict.notReady("SAVE_MISSING", outcome);
        }
        PendingFact pending = outcome.pending();
        if (pending == null || !pending.supported()) {
            return ReadyVerdict.reconciling("PENDING_UNSUPPORTED");
        }
        if (!TOOL_NAME.equals(pending.toolName()) || !outcome.binding().toolCallId().equals(pending.toolId())) {
            return ReadyVerdict.reconciling("PENDING_MISMATCH");
        }
        if (!Objects.equals(pending.inputDigest(), digest(outcome.proposal()))) {
            return ReadyVerdict.reconciling("FROZEN_INPUT_MISMATCH");
        }
        PlatformCard waiting = PlatformCard.waiting(outcome.binding(), pending, outcome.proposal(), outcome.save());
        card.set(waiting);
        return new ReadyVerdict(ReadyStatus.READY, "READY", waiting, outcome.save(), pending, outcome.eventSeen());
    }

    public AcceptOutcome accept(ReadyVerdict ready, SyntheticAnswer answer) {
        if (ready.status() != ReadyStatus.READY || ready.card() == null) {
            return new AcceptOutcome(ResponseStatus.REJECTED, "NOT_READY", null, ready);
        }
        PlatformCard accepted = ready.card().accepted(answer);
        card.set(accepted);
        return new AcceptOutcome(ResponseStatus.ACCEPTED, "ACCEPTED", accepted, ready);
    }

    public ClaimOutcome claim(AcceptOutcome accepted, String resumeSdkCallKey) {
        if (accepted.status() != ResponseStatus.ACCEPTED || accepted.card() == null) {
            return new ClaimOutcome(ResponseStatus.REJECTED, "NOT_ACCEPTED", null, accepted);
        }
        PlatformCard claimed = accepted.card().claimed(resumeSdkCallKey, accepted.ready().save());
        card.set(claimed);
        return new ClaimOutcome(ResponseStatus.CLAIMED, "CLAIMED", claimed, accepted);
    }

    public ConsumeOutcome consume(ClaimOutcome claim, boolean failStoreAfterApply) {
        if (claim.status() != ResponseStatus.CLAIMED || claim.card() == null) {
            return ConsumeOutcome.rejected("NOT_CLAIMED", claim);
        }
        PlatformCard claimed = claim.card();
        Binding binding = claimed.binding().withSdkCallKey(claimed.resumeSdkCallKey());
        ToolResultBlock rebuilt = rebuildResult(claimed);
        AgentStateStore activeStore = failStoreAfterApply
                ? new FailAfterWriteStore(store, new AtomicBoolean(true))
                : store;
        ScriptedModel model = ScriptedModel.thenText("ack");
        RequestUserInputTool tool = new RequestUserInputTool(toolBodyCalls, validationPasses, claimed.proposal());
        ReActAgent agent = buildAgent(model, tool, activeStore);
        lastEvents.clear();
        int modelsBefore = modelCalls.get();
        int toolsBefore = toolBodyCalls.get();
        RuntimeContext ctx = context(binding);
        Msg resume = Msg.builder().role(MsgRole.TOOL).content(List.of(rebuilt)).build();
        List<AgentEvent> events;
        Throwable failure = null;
        try {
            events = agent.streamEvents(List.of(resume), ctx).doOnNext(lastEvents::add).collectList().block();
        } catch (RuntimeException ex) {
            failure = ex;
            events = List.copyOf(lastEvents);
        }
        modelCalls.addAndGet(model.invocations());
        boolean externalApplied = events.stream().anyMatch(ExternalExecutionResultEvent.class::isInstance);
        RawCheckpoint raw = readRaw(binding);
        VersionedState<AgentState> independent = independentRead(binding);
        SaveFact after = SaveFact.from(raw, independent);
        PendingFact pending = pendingFrom(independent.value());
        boolean matchingResult = hasMatchingResult(independent.value(), claimed.pending().toolId());
        if (failure != null || !after.persisted() || failStoreAfterApply) {
            PlatformCard reconciling = claimed.reconciling("SAVE_OR_PROOF_UNCLEAR");
            card.set(reconciling);
            return new ConsumeOutcome(ResponseStatus.RECONCILING, "SAVE_OR_PROOF_UNCLEAR", reconciling,
                    claim.beforeCheckpoint(), after, pending, externalApplied, matchingResult,
                    modelCalls.get() - modelsBefore, toolBodyCalls.get() - toolsBefore, false);
        }
        if (!matchingResult || (pending != null && pending.supported())) {
            PlatformCard reconciling = claimed.reconciling("CONSUME_INCONSISTENT");
            card.set(reconciling);
            return new ConsumeOutcome(ResponseStatus.RECONCILING, "CONSUME_INCONSISTENT", reconciling,
                    claim.beforeCheckpoint(), after, pending, externalApplied, matchingResult,
                    modelCalls.get() - modelsBefore, toolBodyCalls.get() - toolsBefore, false);
        }
        PlatformCard consumed = claimed.consumed(after);
        card.set(consumed);
        return new ConsumeOutcome(ResponseStatus.CONSUMED, "CONSUMED", consumed, claim.beforeCheckpoint(), after,
                pending, externalApplied, matchingResult, modelCalls.get() - modelsBefore,
                toolBodyCalls.get() - toolsBefore, true);
    }

    /**
     * 进程重启／租约过期后：已领取但无消费证明时不得自动重送；保留原回答。
     */
    public RestartOutcome restartWithoutAutoResend() {
        PlatformCard current = card.get();
        if (current == null) {
            return new RestartOutcome(false, false, null, "NO_CARD");
        }
        if (current.responseStatus() == ResponseStatus.CLAIMED
                || current.responseStatus() == ResponseStatus.RECONCILING) {
            return new RestartOutcome(false, true, current.answer(), "HOLD_FOR_RECONCILE");
        }
        if (current.responseStatus() == ResponseStatus.ACCEPTED) {
            return new RestartOutcome(false, false, current.answer(), "WAITING_EXPLICIT_CONTINUE");
        }
        return new RestartOutcome(false, false, current.answer(), current.responseStatus().name());
    }

    public RawCheckpoint readRaw(Binding binding) {
        String storedSessionId = binding.userId() + ":" + binding.stateSlot();
        String sql = "SELECT state_data, version FROM " + TABLE_NAME
                + " WHERE session_id = ? AND state_key = ? AND item_index = 0";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storedSessionId);
            statement.setString(2, AGENT_STATE_KEY);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                String stateData = rows.getString("state_data");
                return new RawCheckpoint(storedSessionId, rows.getLong("version"), sha256(stateData), stateData);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read agent state", e);
        }
    }

    public VersionedState<AgentState> independentRead(Binding binding) {
        return new JdbcAgentStateStore(dataSource, dialect, false)
                .getVersioned(binding.userId(), binding.stateSlot(), AGENT_STATE_KEY, AgentState.class);
    }

    private ReActAgent buildAgent(ChatModelBase model, RequestUserInputTool tool, AgentStateStore stateStore) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return ReActAgent.builder()
                .name("issue26-probe")
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .conflictPolicy(ConflictPolicy.FAIL)
                .enablePendingToolRecovery(false)
                .maxIters(4)
                .build();
    }

    private static RuntimeContext context(Binding binding) {
        return RuntimeContext.builder().userId(binding.userId()).sessionId(binding.stateSlot()).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg findResult(List<AgentEvent> events) {
        if (events == null) {
            return null;
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) instanceof AgentResultEvent resultEvent) {
                return resultEvent.getResult();
            }
        }
        return null;
    }

    private static PendingFact pendingFrom(AgentState state) {
        if (state == null || state.getContext() == null) {
            return null;
        }
        // pendingToolUseIds 不按 agent 名过滤；extractPendingToolCalls(name) 在 name 不匹配时会漏掉真实 pending。
        java.util.Set<String> pendingIds = MessageUtils.pendingToolUseIds(state.getContext());
        Msg assistant = MessageUtils.lastAssistantMessage(state.getContext());
        if (assistant == null || pendingIds.isEmpty()) {
            return PendingFact.none();
        }
        List<ToolUseBlock> pending = assistant.getContentBlocks(ToolUseBlock.class).stream()
                .filter(use -> pendingIds.contains(use.getId()))
                .toList();
        if (pending.isEmpty()) {
            return PendingFact.none();
        }
        if (pending.size() != 1) {
            return new PendingFact(false, pending.size(), null, null, null, null);
        }
        ToolUseBlock use = pending.get(0);
        String replyId = null;
        if (assistant.getMetadata() != null) {
            Object raw = assistant.getMetadata().get(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
            replyId = raw == null ? null : String.valueOf(raw);
        }
        return new PendingFact(TOOL_NAME.equals(use.getName()), 1, use.getId(), use.getName(),
                digest(use.getInput()), replyId);
    }

    private static boolean hasMatchingResult(AgentState state, String toolId) {
        if (state == null || state.getContext() == null) {
            return false;
        }
        for (Msg msg : state.getContext()) {
            for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
                if (toolId.equals(block.getId()) && !block.isSuspended()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ToolResultBlock rebuildResult(PlatformCard card) {
        SyntheticAnswer answer = card.answer();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("responseId", answer.responseId());
        payload.put("requestRevision", answer.requestRevision());
        payload.put("snapshotDigest", answer.snapshotDigest());
        payload.put("normalized", answer.normalized());
        return ToolResultBlock.builder()
                .id(card.pending().toolId())
                .name(card.pending().toolName())
                .output(TextBlock.builder().text(json(payload)).build())
                .state(ToolResultState.SUCCESS)
                .build();
    }

    private static String digest(Object value) {
        return sha256(json(value));
    }

    private static String json(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Binding(String userId, String employeeReleaseId, String businessSessionId, String runId,
            String stateSlot, long stateGeneration, String sdkCallKey, long fence, String writerId, String toolCallId) {
        public Binding withSdkCallKey(String sdkCallKey) {
            return new Binding(userId, employeeReleaseId, businessSessionId, runId, stateSlot, stateGeneration,
                    sdkCallKey, fence, writerId, toolCallId);
        }
    }

    public enum ReadyStatus {
        READY, NOT_READY, RECONCILING
    }

    public enum ResponseStatus {
        ACCEPTED, CLAIMED, CONSUMED, RECONCILING, REJECTED
    }

    public record SaveFact(boolean persisted, Long version, String sha256) {
        static SaveFact missing() {
            return new SaveFact(false, null, null);
        }

        static SaveFact from(RawCheckpoint raw, VersionedState<AgentState> independent) {
            if (raw == null || independent == null || independent.value() == null) {
                return missing();
            }
            if (raw.version() != independent.version()) {
                throw new IllegalStateException("independent version mismatch");
            }
            return new SaveFact(true, raw.version(), raw.sha256());
        }
    }

    public record PendingFact(boolean supported, int count, String toolId, String toolName, String inputDigest,
            String replyId) {
        static PendingFact none() {
            return new PendingFact(false, 0, null, null, null, null);
        }
    }

    public record RawCheckpoint(String storedSessionId, long version, String sha256, String stateData) {
    }

    public record SuspendOutcome(Binding binding, Msg result, boolean eventSeen, SaveFact save, PendingFact pending,
            Map<String, Object> proposal, ReActAgent agent, ScriptedModel model, RequestUserInputTool tool) {
    }

    public record ReadyVerdict(ReadyStatus status, String reason, PlatformCard card, SaveFact save, PendingFact pending,
            boolean eventSeen) {
        static ReadyVerdict notReady(String reason, SuspendOutcome outcome) {
            return new ReadyVerdict(ReadyStatus.NOT_READY, reason, null, outcome.save(), outcome.pending(),
                    outcome.eventSeen());
        }

        static ReadyVerdict reconciling(String reason) {
            return new ReadyVerdict(ReadyStatus.RECONCILING, reason, null, SaveFact.missing(), null, false);
        }
    }

    public record SyntheticAnswer(String responseId, int requestRevision, String snapshotDigest,
            Map<String, Object> normalized) {
    }

    public record AcceptOutcome(ResponseStatus status, String reason, PlatformCard card, ReadyVerdict ready) {
    }

    public record ClaimOutcome(ResponseStatus status, String reason, PlatformCard card, AcceptOutcome accepted) {
        SaveFact beforeCheckpoint() {
            return card == null ? SaveFact.missing() : card.beforeCheckpoint();
        }
    }

    public record ConsumeOutcome(ResponseStatus status, String reason, PlatformCard card, SaveFact before, SaveFact after,
            PendingFact pendingAfter, boolean externalEventSeen, boolean matchingResultPresent, int modelDelta,
            int toolBodyDelta, boolean durableProof) {
        static ConsumeOutcome rejected(String reason, ClaimOutcome claim) {
            return new ConsumeOutcome(ResponseStatus.REJECTED, reason, null, claim.beforeCheckpoint(),
                    SaveFact.missing(), null, false, false, 0, 0, false);
        }
    }

    public record RestartOutcome(boolean autoResent, boolean holdForReconcile, SyntheticAnswer retainedAnswer,
            String reason) {
    }

    public record PlatformCard(Binding binding, PendingFact pending, Map<String, Object> proposal,
            ResponseStatus responseStatus, SyntheticAnswer answer, String resumeSdkCallKey, SaveFact beforeCheckpoint,
            SaveFact afterCheckpoint, String reconcileReason) {
        static PlatformCard waiting(Binding binding, PendingFact pending, Map<String, Object> proposal, SaveFact save) {
            return new PlatformCard(binding, pending, Map.copyOf(proposal), null, null, null, save, null, null);
        }

        PlatformCard accepted(SyntheticAnswer answer) {
            return new PlatformCard(binding, pending, proposal, ResponseStatus.ACCEPTED, answer, null, beforeCheckpoint,
                    null, null);
        }

        PlatformCard claimed(String resumeSdkCallKey, SaveFact before) {
            return new PlatformCard(binding, pending, proposal, ResponseStatus.CLAIMED, answer, resumeSdkCallKey, before,
                    null, null);
        }

        PlatformCard consumed(SaveFact after) {
            return new PlatformCard(binding, pending, proposal, ResponseStatus.CONSUMED, answer, resumeSdkCallKey,
                    beforeCheckpoint, after, null);
        }

        PlatformCard reconciling(String reason) {
            return new PlatformCard(binding, pending, proposal, ResponseStatus.RECONCILING, answer, resumeSdkCallKey,
                    beforeCheckpoint, null, reason);
        }
    }

    static final class RequestUserInputTool extends ToolBase {
        private final AtomicInteger bodyCalls;
        private final AtomicInteger validations;
        private final Map<String, Object> expected;

        RequestUserInputTool(AtomicInteger bodyCalls, AtomicInteger validations, Map<String, Object> expected) {
            super(ToolBase.builder()
                    .name(TOOL_NAME)
                    .description("Controlled platform form input")
                    .inputSchema(schema())
                    .externalTool(false)
                    .readOnly(false)
                    .concurrencySafe(false)
                    .returnDirect(false));
            this.bodyCalls = bodyCalls;
            this.validations = validations;
            this.expected = expected;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.allow("issue26 controlled input"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            bodyCalls.incrementAndGet();
            Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
            if (!input.equals(expected)) {
                return Mono.just(ToolResultBlock.error("invalid form proposal"));
            }
            if (!input.containsKey("fields") || !(input.get("fields") instanceof List<?> fields) || fields.isEmpty()) {
                return Mono.just(ToolResultBlock.error("fields required"));
            }
            validations.incrementAndGet();
            return Mono.error(new ToolSuspendException("awaiting durable form answer"));
        }

        private static Map<String, Object> schema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("fields", Map.of("type", "array"));
            props.put("title", Map.of("type", "string"));
            schema.put("properties", props);
            schema.put("required", List.of("fields"));
            return schema;
        }
    }

    static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger();

        ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        static ScriptedModel onceTool(String toolId, String toolName, Map<String, Object> input) {
            // SDK ToolCallsAccumulator 在无 streaming content 时会把 content 落成 "{}"；
            // 必须提供合法 JSON content，否则 schema 校验在方法体前失败，挂起接缝测不到。
            String content = json(input);
            return new ScriptedModel(List.of(() -> Flux.just(ChatResponse.builder()
                    .content(List.of(ToolUseBlock.builder()
                            .id(toolId)
                            .name(toolName)
                            .input(input)
                            .content(content)
                            .build()))
                    .build())));
        }

        static ScriptedModel thenText(String text) {
            return new ScriptedModel(List.of(() -> Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(text).build()))
                    .build())));
        }

        int invocations() {
            return idx.get();
        }

        @Override
        public String getModelName() {
            return "issue26-scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int i = idx.getAndIncrement();
            if (i >= scripts.size()) {
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("").build()))
                        .build());
            }
            return scripts.get(i).get();
        }
    }

    /**
     * 模拟 SDK 已走到写路径后保存失败：第一次条件写抛错，证明不能仅凭事件标已消费。
     */
    static final class FailAfterWriteStore implements AgentStateStore {
        private final JdbcAgentStateStore delegate;
        private final AtomicBoolean failNext;

        FailAfterWriteStore(JdbcAgentStateStore delegate, AtomicBoolean failNext) {
            this.delegate = delegate;
            this.failNext = failNext;
        }

        @Override
        public void save(String userId, String sessionId, String key, io.agentscope.core.state.State state) {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("simulated save failure after apply");
            }
            delegate.save(userId, sessionId, key, state);
        }

        @Override
        public void save(String userId, String sessionId, String key, List<? extends io.agentscope.core.state.State> states) {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("simulated save failure after apply");
            }
            delegate.save(userId, sessionId, key, states);
        }

        @Override
        public boolean supportsVersioning() {
            return delegate.supportsVersioning();
        }

        @Override
        public <T extends io.agentscope.core.state.State> VersionedState<T> getVersioned(String userId, String sessionId,
                String key, Class<T> type) {
            return delegate.getVersioned(userId, sessionId, key, type);
        }

        @Override
        public long saveIfVersion(String userId, String sessionId, String key, io.agentscope.core.state.State state,
                long expectedVersion) {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("simulated save failure after apply");
            }
            return delegate.saveIfVersion(userId, sessionId, key, state, expectedVersion);
        }

        @Override
        public <T extends io.agentscope.core.state.State> java.util.Optional<T> get(String userId, String sessionId,
                String key, Class<T> type) {
            return delegate.get(userId, sessionId, key, type);
        }

        @Override
        public <T extends io.agentscope.core.state.State> List<T> getList(String userId, String sessionId, String key,
                Class<T> type) {
            return delegate.getList(userId, sessionId, key, type);
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return delegate.exists(userId, sessionId);
        }

        @Override
        public void delete(String userId, String sessionId) {
            delegate.delete(userId, sessionId);
        }

        @Override
        public java.util.Set<String> listSessionIds(String userId) {
            return delegate.listSessionIds(userId);
        }
    }
}
