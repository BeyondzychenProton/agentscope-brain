package com.agentscope.brain.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * #27：对已核验的唯一平台输入 pending，用版本 CAS 做无执行终结；
 * 独立保存终结证据，不覆盖原调用证据；旧 writer／证明丢失保持 RECONCILING。
 */
public final class PendingTerminationProbe {
    public static final String TOOL_NAME = "request_user_input";
    public static final String AGENT_STATE_KEY = "agent_state";
    public static final String TERMINAL_REASON_PREFIX = "PLATFORM_TERMINATED:";
    static final String TABLE_NAME = "issue27_agent_state";

    private final DataSource dataSource;
    private final AbstractJdbcDialect dialect;
    private final JdbcAgentStateStore store;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolBodyCalls = new AtomicInteger();
    private final AtomicInteger otherToolSends = new AtomicInteger();
    private final AtomicInteger validationPasses = new AtomicInteger();
    private final List<AgentEvent> lastEvents = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, QuiescenceProof> quiescenceBySlot = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TerminalizationRecord> ledger = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> sessionBlocked = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SaveFact> originalCallEvidence = new ConcurrentHashMap<>();

    public PendingTerminationProbe(DataSource dataSource) {
        this.dataSource = dataSource;
        this.dialect = AbstractJdbcDialect.from(dataSource)
                .tablePrefix("issue27_")
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
        originalCallEvidence.put(slotKey(binding), save);
        return new SuspendOutcome(binding, result, eventSeen, save, pending, proposal);
    }

    /**
     * 旧 SDK 调用已退出／静止的可核验证明。新 epoch、lease 到期或 fence 本身不能代替本证明。
     */
    public QuiescenceProof markQuiescent(Binding binding, long observedCallVersion) {
        // 旧调用已退出：须有挂起收尾保存证据；冻结版本须等于当前独立读回，供后续 CAS。
        // 新 epoch／lease／fence 本身不能进入本方法冒充退出。
        SaveFact original = originalCallEvidence.get(slotKey(binding));
        if (original == null || !original.persisted()) {
            QuiescenceProof rejected = QuiescenceProof.rejected(binding, "CALL_EXIT_UNPROVEN");
            quiescenceBySlot.put(slotKey(binding), rejected);
            return rejected;
        }
        RawCheckpoint raw = readRaw(binding);
        if (raw == null || raw.version() != observedCallVersion) {
            QuiescenceProof rejected = QuiescenceProof.rejected(binding, "CALL_EXIT_UNPROVEN");
            quiescenceBySlot.put(slotKey(binding), rejected);
            return rejected;
        }
        QuiescenceProof proof = QuiescenceProof.exited(binding, observedCallVersion, raw.sha256());
        quiescenceBySlot.put(slotKey(binding), proof);
        return proof;
    }

    public TerminateOutcome terminate(Binding binding, String terminalizationKey, QuiescenceProof quiescence,
            String reason, boolean failStore) {
        Objects.requireNonNull(terminalizationKey, "terminalizationKey");
        TerminalizationRecord existing = ledger.get(terminalizationKey);
        if (existing != null && (existing.status() == TerminalStatus.VERIFIED
                || existing.status() == TerminalStatus.APPLIED)) {
            RestartRead current = restartRead(binding);
            return new TerminateOutcome(TerminalStatus.VERIFIED, "IDEMPOTENT_REPLAY",
                    SaveFact.of(existing.beforeVersion(), existing.beforeSha256()),
                    SaveFact.of(existing.afterVersion(), existing.afterSha256()),
                    current.pending(), current.matchingTerminalResultCount(), current.waitingMetadataPresent() == false,
                    current.toolUsePreserved(), existing);
        }

        if (existing == null) {
            // 证明丢失窗口：状态可能已终结，但 ledger 无记录 → 只核对原事实，不再次写入
            RestartRead probe = restartRead(binding);
            if (probe.matchingTerminalResultCount() > 0) {
                sessionBlocked.put(slotKey(binding), true);
                return new TerminateOutcome(TerminalStatus.RECONCILING, "PROOF_LOST_CHECK_ONLY",
                        SaveFact.of(probe.version(), probe.sha256()),
                        SaveFact.of(probe.version(), probe.sha256()),
                        probe.pending(), probe.matchingTerminalResultCount(), !probe.waitingMetadataPresent(),
                        probe.toolUsePreserved(), null);
            }
        }

        if (quiescence == null || !quiescence.exited() || !quiescence.matches(binding)) {
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "CALL_NOT_QUIESCENT", null);
        }
        QuiescenceProof remembered = quiescenceBySlot.get(slotKey(binding));
        if (remembered == null || !remembered.exited() || remembered.observedVersion() != quiescence.observedVersion()) {
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "CALL_NOT_QUIESCENT", null);
        }

        RawCheckpoint beforeRaw = readRaw(binding);
        VersionedState<AgentState> beforeIndependent = independentRead(binding);
        SaveFact before = SaveFact.from(beforeRaw, beforeIndependent);
        if (!before.persisted() || beforeIndependent.value() == null) {
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "STATE_MISSING", before);
        }
        if (before.version() != quiescence.observedVersion()) {
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "VERSION_MOVED", before);
        }

        PendingFact pending = pendingFrom(beforeIndependent.value());
        if (pending == null || !pending.supported() || pending.count() != 1
                || !TOOL_NAME.equals(pending.toolName())
                || !binding.toolCallId().equals(pending.toolId())) {
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "PENDING_UNSUPPORTED", before);
        }

        SaveFact originalCall = originalCallEvidence.getOrDefault(slotKey(binding), before);
        TerminalizationRecord prepared = TerminalizationRecord.prepared(terminalizationKey, binding, pending, reason,
                quiescence, originalCall.version(), before.version(), before.sha256());
        ledger.put(terminalizationKey, prepared);

        AgentState mutated = applyTerminalResult(beforeIndependent.value(), pending, reason);
        AgentStateStore active = failStore
                ? new FailAfterWriteStore(store, new AtomicBoolean(true))
                : store;

        long written;
        try {
            written = active.saveIfVersion(binding.userId(), binding.stateSlot(), AGENT_STATE_KEY, mutated,
                    before.version());
        } catch (RuntimeException ex) {
            ledger.put(terminalizationKey, prepared.reconciling("SAVE_OR_PROOF_UNCLEAR"));
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "SAVE_OR_PROOF_UNCLEAR", before);
        }
        if (written == AgentStateStore.UNVERSIONED || written <= 0) {
            ledger.put(terminalizationKey, prepared.reconciling("CAS_FAIL"));
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "CAS_FAIL", before);
        }

        RawCheckpoint afterRaw = readRaw(binding);
        VersionedState<AgentState> afterIndependent = independentRead(binding);
        SaveFact after = SaveFact.from(afterRaw, afterIndependent);
        if (!after.persisted() || after.version() != written || afterIndependent.value() == null) {
            ledger.put(terminalizationKey, prepared.reconciling("SAVE_OR_PROOF_UNCLEAR"));
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "SAVE_OR_PROOF_UNCLEAR", before);
        }

        PendingFact pendingAfter = pendingFrom(afterIndependent.value());
        int terminalCount = countTerminalResults(afterIndependent.value(), pending.toolId());
        boolean waitingCleared = !hasWaitingMetadata(afterIndependent.value(), pending.toolId());
        boolean toolUsePreserved = hasToolUse(afterIndependent.value(), pending.toolId(), pending.inputDigest());
        if (terminalCount != 1 || (pendingAfter != null && pendingAfter.supported()) || !waitingCleared
                || !toolUsePreserved) {
            ledger.put(terminalizationKey, prepared.reconciling("READBACK_INCONSISTENT"));
            sessionBlocked.put(slotKey(binding), true);
            return reconciling(binding, "READBACK_INCONSISTENT", before);
        }

        TerminalizationRecord verified = prepared.verified(after.version(), after.sha256());
        ledger.put(terminalizationKey, verified);
        sessionBlocked.put(slotKey(binding), false);
        return new TerminateOutcome(TerminalStatus.VERIFIED, "VERIFIED", before, after, pendingAfter, terminalCount,
                waitingCleared, toolUsePreserved, verified);
    }

    public RestartRead restartRead(Binding binding) {
        // 新 Store／跳过进程缓存
        VersionedState<AgentState> independent = new JdbcAgentStateStore(dataSource, dialect, false)
                .getVersioned(binding.userId(), binding.stateSlot(), AGENT_STATE_KEY, AgentState.class);
        RawCheckpoint raw = readRaw(binding);
        if (raw == null || independent.value() == null) {
            return RestartRead.missing();
        }
        if (raw.version() != independent.version()) {
            throw new IllegalStateException("independent version mismatch on restart");
        }
        AgentState state = independent.value();
        PendingFact pending = pendingFrom(state);
        String toolId = binding.toolCallId();
        String frozenDigest = digestOfToolUse(state, toolId);
        return new RestartRead(true, raw.version(), raw.sha256(), pending, countTerminalResults(state, toolId),
                hasWaitingMetadata(state, toolId), hasToolUse(state, toolId, frozenDigest));
    }

    public TerminalizationRecord evidence(String terminalizationKey) {
        return ledger.get(terminalizationKey);
    }

    public void dropEvidence(String terminalizationKey) {
        ledger.remove(terminalizationKey);
    }

    public boolean sessionBlocked(Binding binding) {
        return Boolean.TRUE.equals(sessionBlocked.get(slotKey(binding)));
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

    public long casSave(Binding binding, AgentState state, long expectedVersion) {
        return store.saveIfVersion(binding.userId(), binding.stateSlot(), AGENT_STATE_KEY, state, expectedVersion);
    }

    public PendingFact pendingFrom(AgentState state) {
        if (state == null || state.getContext() == null) {
            return null;
        }
        Set<String> pendingIds = MessageUtils.pendingToolUseIds(state.getContext());
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
        return new PendingFact(TOOL_NAME.equals(use.getName()), 1, use.getId(), use.getName(), digest(use.getInput()),
                replyId);
    }

    /**
     * 测试夹具：在同一 ASSISTANT 上追加额外未决 ToolUse，制造多／混合 pending。
     */
    public void injectExtraPending(Binding binding, String extraToolId, String toolName) {
        VersionedState<AgentState> current = independentRead(binding);
        AgentState copy = AgentState.fromJsonString(current.value().toJson());
        List<Msg> context = copy.contextMutable();
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            List<io.agentscope.core.message.ContentBlock> blocks = new ArrayList<>(msg.getContent());
            blocks.add(ToolUseBlock.builder()
                    .id(extraToolId)
                    .name(toolName)
                    .input(Map.of("x", 1))
                    .content("{}")
                    .build());
            context.set(i, Msg.builder()
                    .id(msg.getId())
                    .name(msg.getName())
                    .role(msg.getRole())
                    .content(blocks)
                    .metadata(msg.getMetadata())
                    .timestamp(msg.getTimestamp())
                    .usage(msg.getUsage())
                    .generateReason(msg.getGenerateReason())
                    .build());
            break;
        }
        long written = casSave(binding, copy, current.version());
        if (written == AgentStateStore.UNVERSIONED) {
            throw new IllegalStateException("failed to inject extra pending");
        }
    }

    private TerminateOutcome reconciling(Binding binding, String reason, SaveFact before) {
        RestartRead current = restartRead(binding);
        SaveFact fact = before != null && before.persisted()
                ? before
                : (current.persisted() ? SaveFact.of(current.version(), current.sha256()) : SaveFact.missing());
        return new TerminateOutcome(TerminalStatus.RECONCILING, reason, fact, fact, current.pending(),
                current.matchingTerminalResultCount(), !current.waitingMetadataPresent(), current.toolUsePreserved(),
                null);
    }

    private static AgentState applyTerminalResult(AgentState original, PendingFact pending, String reason) {
        AgentState copy = AgentState.fromJsonString(original.toJson());
        List<Msg> context = copy.contextMutable();
        for (int i = 0; i < context.size(); i++) {
            Msg msg = context.get(i);
            if (msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            boolean ownsPending = msg.getContentBlocks(ToolUseBlock.class).stream()
                    .anyMatch(use -> pending.toolId().equals(use.getId()));
            if (!ownsPending) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (msg.getMetadata() != null) {
                metadata.putAll(msg.getMetadata());
            }
            metadata.remove(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
            context.set(i, msg.withMetadata(metadata.isEmpty() ? null : metadata));
        }
        ToolResultBlock terminal = ToolResultBlock.error(pending.toolId(), TERMINAL_REASON_PREFIX + reason)
                .withIdAndName(pending.toolId(), pending.toolName());
        context.add(Msg.builder().role(MsgRole.TOOL).content(List.of(terminal)).build());
        return copy;
    }

    private static int countTerminalResults(AgentState state, String toolId) {
        if (state == null || state.getContext() == null) {
            return 0;
        }
        int count = 0;
        for (Msg msg : state.getContext()) {
            for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
                if (toolId.equals(block.getId()) && !block.isSuspended()
                        && block.getState() == ToolResultState.ERROR
                        && outputMentionsTerminal(block)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean outputMentionsTerminal(ToolResultBlock block) {
        if (block.getOutput() == null) {
            return false;
        }
        for (var part : block.getOutput()) {
            if (part instanceof TextBlock text && text.getText() != null
                    && text.getText().contains(TERMINAL_REASON_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWaitingMetadata(AgentState state, String toolId) {
        if (state == null || state.getContext() == null) {
            return false;
        }
        for (Msg msg : state.getContext()) {
            if (msg.getRole() != MsgRole.ASSISTANT || msg.getMetadata() == null) {
                continue;
            }
            boolean owns = msg.getContentBlocks(ToolUseBlock.class).stream()
                    .anyMatch(use -> toolId.equals(use.getId()));
            if (owns && msg.getMetadata().containsKey(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasToolUse(AgentState state, String toolId, String inputDigest) {
        if (state == null || state.getContext() == null || toolId == null) {
            return false;
        }
        for (Msg msg : state.getContext()) {
            for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) {
                if (toolId.equals(use.getId())) {
                    return inputDigest == null || inputDigest.equals(digest(use.getInput()));
                }
            }
        }
        return false;
    }

    private static String digestOfToolUse(AgentState state, String toolId) {
        if (state == null || state.getContext() == null) {
            return null;
        }
        for (Msg msg : state.getContext()) {
            for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) {
                if (toolId.equals(use.getId())) {
                    return digest(use.getInput());
                }
            }
        }
        return null;
    }

    private ReActAgent buildAgent(ChatModelBase model, RequestUserInputTool tool, AgentStateStore stateStore) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return ReActAgent.builder()
                .name("issue27-probe")
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

    private static String slotKey(Binding binding) {
        return binding.userId() + ":" + binding.stateSlot();
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
    }

    public enum TerminalStatus {
        PREPARED, APPLIED, VERIFIED, RECONCILING
    }

    public record SaveFact(boolean persisted, Long version, String sha256) {
        static SaveFact missing() {
            return new SaveFact(false, null, null);
        }

        static SaveFact of(Long version, String sha256) {
            if (version == null) {
                return missing();
            }
            return new SaveFact(true, version, sha256);
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
            Map<String, Object> proposal) {
    }

    public record QuiescenceProof(boolean exited, long fence, long epoch, Long observedVersion, String observedSha256,
            String bindingSlot, String reason) {
        static QuiescenceProof exited(Binding binding, long version, String sha256) {
            return new QuiescenceProof(true, binding.fence(), binding.stateGeneration(), version, sha256,
                    slotKey(binding), "EXITED");
        }

        static QuiescenceProof rejected(Binding binding, String reason) {
            return new QuiescenceProof(false, binding.fence(), binding.stateGeneration(), null, null, slotKey(binding),
                    reason);
        }

        /** 仅有 lease／fence／epoch，不能授权终结。 */
        public static QuiescenceProof leaseOnly(long fence, long epoch) {
            return new QuiescenceProof(false, fence, epoch, null, null, null, "LEASE_ONLY");
        }

        boolean matches(Binding binding) {
            return bindingSlot != null && bindingSlot.equals(slotKey(binding));
        }
    }

    public record TerminalizationRecord(String terminalizationKey, TerminalStatus status, String reason,
            String sdkCallKey, String stateSlot, long stateGeneration, long fence, String toolId, String inputDigest,
            Long originalCallVersion, Long beforeVersion, String beforeSha256, Long afterVersion, String afterSha256,
            boolean quiescentProven) {
        static TerminalizationRecord prepared(String key, Binding binding, PendingFact pending, String reason,
                QuiescenceProof quiescence, Long originalCallVersion, long beforeVersion, String beforeSha256) {
            return new TerminalizationRecord(key, TerminalStatus.PREPARED, reason, binding.sdkCallKey(),
                    binding.stateSlot(), binding.stateGeneration(), binding.fence(), pending.toolId(),
                    pending.inputDigest(), originalCallVersion, beforeVersion, beforeSha256, null, null,
                    quiescence.exited());
        }

        TerminalizationRecord verified(long afterVersion, String afterSha256) {
            return new TerminalizationRecord(terminalizationKey, TerminalStatus.VERIFIED, "VERIFIED", sdkCallKey,
                    stateSlot, stateGeneration, fence, toolId, inputDigest, originalCallVersion, beforeVersion,
                    beforeSha256, afterVersion, afterSha256, quiescentProven);
        }

        TerminalizationRecord reconciling(String reason) {
            return new TerminalizationRecord(terminalizationKey, TerminalStatus.RECONCILING, reason, sdkCallKey,
                    stateSlot, stateGeneration, fence, toolId, inputDigest, originalCallVersion, beforeVersion,
                    beforeSha256, afterVersion, afterSha256, quiescentProven);
        }
    }

    public record TerminateOutcome(TerminalStatus status, String reason, SaveFact before, SaveFact after,
            PendingFact pendingAfter, int matchingTerminalResultCount, boolean waitingMetadataCleared,
            boolean toolUsePreserved, TerminalizationRecord record) {
    }

    public record RestartRead(boolean persisted, Long version, String sha256, PendingFact pending,
            int matchingTerminalResultCount, boolean waitingMetadataPresent, boolean toolUsePreserved) {
        static RestartRead missing() {
            return new RestartRead(false, null, null, null, 0, false, false);
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
            return Mono.just(PermissionDecision.allow("issue27 controlled input"));
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

        int invocations() {
            return idx.get();
        }

        @Override
        public String getModelName() {
            return "issue27-scripted";
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
                throw new IllegalStateException("simulated save failure");
            }
            delegate.save(userId, sessionId, key, state);
        }

        @Override
        public void save(String userId, String sessionId, String key, List<? extends io.agentscope.core.state.State> states) {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("simulated save failure");
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
                throw new IllegalStateException("simulated save failure");
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
