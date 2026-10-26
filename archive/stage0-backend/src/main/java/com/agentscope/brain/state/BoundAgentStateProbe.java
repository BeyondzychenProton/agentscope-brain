package com.agentscope.brain.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.VersionedState;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * #25：把一次可信调用绑定到锁定版本的 MySQL {@link JdbcAgentStateStore}。
 * 只接受 CAS_FAIL；不覆盖、不合并、不在冲突后重载继续执行。
 */
public final class BoundAgentStateProbe {
    public static final String CAS_FAIL = "CAS_FAIL";
    public static final String AGENT_STATE_KEY = "agent_state";
    static final String TABLE_NAME = "issue25_agent_state";

    private final DataSource dataSource;
    private final AbstractJdbcDialect dialect;
    private final JdbcAgentStateStore store;
    private final ConcurrentHashMap<String, SessionFact> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Freeze> freezes = new ConcurrentHashMap<>();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger overwriteAttempts = new AtomicInteger();
    private final AtomicInteger mergeAttempts = new AtomicInteger();

    public BoundAgentStateProbe(DataSource dataSource) {
        this.dataSource = dataSource;
        this.dialect = AbstractJdbcDialect.from(dataSource)
                .tablePrefix("issue25_")
                .sessionStateTableName(TABLE_NAME)
                .autoCreateTable(false)
                .build();
        this.store = new JdbcAgentStateStore(dataSource, dialect, true);
    }

    /** 无网络计数入口。状态保存、读回和拒绝路径都不会调用它。 */
    public void recordModelCall() {
        modelCalls.incrementAndGet();
    }

    /** 无网络计数入口。状态保存、读回和拒绝路径都不会调用它。 */
    public void recordToolCall() {
        toolCalls.incrementAndGet();
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int overwriteAttempts() {
        return overwriteAttempts.get();
    }

    public int mergeAttempts() {
        return mergeAttempts.get();
    }

    public SessionFact register(SessionKind kind, String userId, String employeeReleaseId, String businessSessionId,
            String runId, String stateSlot, long stateGeneration, long fence, String writerId) {
        requireText(userId, "userId");
        requireText(employeeReleaseId, "employeeReleaseId");
        requireText(businessSessionId, "businessSessionId");
        requireText(runId, "runId");
        requireText(stateSlot, "stateSlot");
        requireText(writerId, "writerId");
        if ((userId + ":" + stateSlot).length() > 255) {
            throw new IllegalArgumentException("state slot id exceeds 255 characters");
        }
        SessionFact fact = new SessionFact(UUID.randomUUID().toString(), kind, userId, employeeReleaseId,
                businessSessionId, runId, stateSlot, stateGeneration, fence, writerId);
        sessions.put(fact.registrationId(), fact);
        return fact;
    }

    public CallBinding bind(SessionFact fact, String sdkCallKey) {
        requireText(sdkCallKey, "sdkCallKey");
        return new CallBinding(fact.registrationId(), fact.userId(), fact.employeeReleaseId(), fact.businessSessionId(),
                fact.runId(), fact.stateSlot(), fact.stateGeneration(), sdkCallKey, fact.fence(), fact.writerId(), false);
    }

    public Freeze freeze(CallBinding call) {
        String mismatch = mismatch(call);
        if (mismatch != null) {
            return remember(call, SaveStatus.REJECTED, mismatch, 0L, false);
        }
        VersionedState<AgentState> current = store.getVersioned(call.userId(), call.stateSlot(), AGENT_STATE_KEY, AgentState.class);
        boolean exists = current.value() != null;
        SessionFact fact = sessions.get(call.registrationId());
        if (!exists && fact.kind() == SessionKind.EXISTING) {
            return remember(call, SaveStatus.RECONCILING, "MISSING_EXISTING_STATE", current.version(), false);
        }
        if (!exists && !call.bootstrapEmpty()) {
            return remember(call, SaveStatus.REJECTED, "BOOTSTRAP_REQUIRED", current.version(), false);
        }
        return remember(call, SaveStatus.READY, "READY", current.version(), exists);
    }

    public SaveReceipt commit(CallBinding call, Freeze freeze, AgentState state, String policy) {
        String mismatch = mismatch(call);
        if (mismatch != null) {
            return receipt(call, SaveStatus.REJECTED, mismatch, null, null, null, null, false);
        }
        Freeze stored = freezes.get(freeze.freezeId());
        if (stored == null || !stored.sdkCallKey().equals(call.sdkCallKey())) {
            return receipt(call, SaveStatus.REJECTED, "FREEZE_MISMATCH", null, null, null, null, false);
        }
        if (stored.status() != SaveStatus.READY) {
            return receipt(call, stored.status(), stored.reason(), null, null, null, null, false);
        }
        if (!CAS_FAIL.equals(policy)) {
            return receipt(call, SaveStatus.REJECTED, "POLICY_FORBIDDEN", null, null, null, null, false);
        }
        if (call.bootstrapEmpty() && stored.beforeExists()) {
            return receipt(call, SaveStatus.REJECTED, "BOOTSTRAP_NOT_ALLOWED", null, null, null, null, false);
        }
        if (freezes.remove(stored.freezeId()) == null) {
            return receipt(call, SaveStatus.REJECTED, "FREEZE_MISMATCH", null, null, null, null, false);
        }
        long written = casSave(call, state, stored.expectedVersion());
        RawRow raw = readRaw(call);
        VersionedState<AgentState> independent = new JdbcAgentStateStore(dataSource, dialect, false)
                .getVersioned(call.userId(), call.stateSlot(), AGENT_STATE_KEY, AgentState.class);
        if (raw != null && independent.version() != raw.version()) {
            throw new IllegalStateException("independent store version does not match raw readback");
        }
        if (written == AgentStateStore.UNVERSIONED) {
            return receipt(call, SaveStatus.CAS_FAIL, "CAS_FAIL", null, null,
                    raw == null ? null : raw.version(), raw == null ? null : raw.sha256(), false);
        }
        if (raw == null || raw.version() != written) {
            throw new IllegalStateException("saved state could not be read back");
        }
        return receipt(call, SaveStatus.SAVED, "SAVED", written, raw.sha256(), raw.version(), raw.sha256(),
                call.bootstrapEmpty() && !stored.beforeExists());
    }

    public ReadResult read(CallBinding call) {
        return readWithoutWriting(call);
    }

    public ReadResult recover(CallBinding call) {
        return readWithoutWriting(call);
    }

    public RawRow readRaw(CallBinding call) {
        String storedSessionId = call.userId() + ":" + call.stateSlot();
        String sql = "SELECT state_data, version FROM " + tableName()
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
                long version = rows.getLong("version");
                return new RawRow(storedSessionId, stateData, version, sha256(stateData));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read agent state", e);
        }
    }

    public SchemaReport schemaReport() {
        String ddl = String.join("\n", dialect.sessionStateCreateTableDdls());
        try (Connection connection = dataSource.getConnection()) {
            String mysqlVersion = scalar(connection, "SELECT VERSION()");
            int tableCount = Integer.parseInt(scalar(connection,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"));
            String collation = scalar(connection,
                    "SELECT TABLE_COLLATION FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '"
                            + tableName() + "'");
            List<Column> columns = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_KEY "
                            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                            + "ORDER BY ORDINAL_POSITION")) {
                statement.setString(1, tableName());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        columns.add(new Column(rows.getString("COLUMN_NAME"), rows.getString("COLUMN_TYPE"),
                                "YES".equals(rows.getString("IS_NULLABLE")), text(rows, "COLUMN_DEFAULT"),
                                text(rows, "EXTRA"), text(rows, "COLUMN_KEY")));
                    }
                }
            }
            List<Index> indexes = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX")) {
                statement.setString(1, tableName());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        indexes.add(new Index(rows.getString("INDEX_NAME"), rows.getString("COLUMN_NAME"),
                                rows.getInt("SEQ_IN_INDEX"), rows.getInt("NON_UNIQUE") == 1));
                    }
                }
            }
            SchemaReport report = new SchemaReport(dialect.getClass().getName(), jarName(dialect.getClass()),
                    mysqlVersion, tableName(), tableCount, collation, ddl, sha256(ddl), "", columns, indexes);
            return new SchemaReport(report.dialectClass(), report.jarName(), report.mysqlVersion(), report.tableName(),
                    report.tableCount(), report.tableCollation(), report.ddl(), report.ddlSha256(),
                    sha256(report.canonical()), report.columns(), report.indexes());
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read agent state schema", e);
        }
    }

    private ReadResult readWithoutWriting(CallBinding call) {
        String mismatch = mismatch(call);
        if (mismatch != null) {
            return new ReadResult(SaveStatus.REJECTED, mismatch, null, null);
        }
        RawRow raw = readRaw(call);
        if (raw == null) {
            SessionFact fact = sessions.get(call.registrationId());
            if (fact.kind() == SessionKind.EXISTING) {
                return new ReadResult(SaveStatus.RECONCILING, "MISSING_EXISTING_STATE", null, null);
            }
            return new ReadResult(SaveStatus.ABSENT, "NOT_CREATED", null, null);
        }
        return new ReadResult(SaveStatus.PRESENT, "PRESENT", raw.version(), raw.sha256());
    }

    private Freeze remember(CallBinding call, SaveStatus status, String reason, long expectedVersion, boolean beforeExists) {
        Freeze freeze = new Freeze(UUID.randomUUID().toString(), call.sdkCallKey(), status, reason, expectedVersion, beforeExists);
        freezes.put(freeze.freezeId(), freeze);
        return freeze;
    }

    private long casSave(CallBinding call, AgentState state, long expectedVersion) {
        if (expectedVersion == AgentStateStore.UNVERSIONED) {
            throw new IllegalStateException("UNVERSIONED save is overwrite");
        }
        return store.saveIfVersion(call.userId(), call.stateSlot(), AGENT_STATE_KEY, state, expectedVersion);
    }

    private String mismatch(CallBinding call) {
        SessionFact fact = sessions.get(call.registrationId());
        if (fact == null) {
            return "UNKNOWN_SESSION";
        }
        if (!fact.userId().equals(call.userId())) {
            return "WRONG_USER";
        }
        if (!fact.businessSessionId().equals(call.businessSessionId())) {
            return "WRONG_BUSINESS_SESSION";
        }
        if (!fact.employeeReleaseId().equals(call.employeeReleaseId())) {
            return "WRONG_RELEASE";
        }
        if (!fact.runId().equals(call.runId())) {
            return "WRONG_RUN";
        }
        if (!fact.stateSlot().equals(call.stateSlot())) {
            return "WRONG_SLOT";
        }
        if (fact.stateGeneration() != call.stateGeneration()) {
            return "WRONG_GENERATION";
        }
        if (call.fence() != fact.fence()) {
            return "OLD_FENCE";
        }
        if (!fact.writerId().equals(call.writerId())) {
            return "OLD_WRITER";
        }
        return null;
    }

    private String tableName() {
        String name = dialect.sessionStateTableName();
        if (!TABLE_NAME.equals(name)) {
            throw new IllegalStateException("unexpected session state table: " + name);
        }
        return name;
    }

    private static SaveReceipt receipt(CallBinding call, SaveStatus status, String reason, Long savedVersion,
            String savedSha256, Long readbackVersion, String readbackSha256, boolean bootstrap) {
        return new SaveReceipt(status, reason, savedVersion, savedSha256, readbackVersion, readbackSha256, bootstrap,
                false, call.userId(), call.employeeReleaseId(), call.businessSessionId(), call.runId(),
                call.stateSlot(), call.stateGeneration(), call.sdkCallKey(), call.fence(), call.writerId());
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("query returned no row");
            }
            return rows.getString(1);
        }
    }

    private static String text(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        return value == null ? "" : value;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jarName(Class<?> type) {
        try {
            String path = java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getFileName().toString();
            return path;
        } catch (Exception e) {
            throw new IllegalStateException("cannot locate dialect jar", e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum SessionKind {
        NEW, EXISTING
    }

    public enum SaveStatus {
        READY, SAVED, CAS_FAIL, REJECTED, RECONCILING, ABSENT, PRESENT
    }

    public record SessionFact(String registrationId, SessionKind kind, String userId, String employeeReleaseId,
            String businessSessionId, String runId, String stateSlot, long stateGeneration, long fence, String writerId) {
    }

    public record CallBinding(String registrationId, String userId, String employeeReleaseId, String businessSessionId,
            String runId, String stateSlot, long stateGeneration,             String sdkCallKey, long fence, String writerId,
            boolean bootstrapEmpty) {
        public CallBinding withUser(String userId) {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, bootstrapEmpty);
        }

        public CallBinding withBusinessSession(String businessSessionId) {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, bootstrapEmpty);
        }

        public CallBinding withRelease(String employeeReleaseId) {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, bootstrapEmpty);
        }

        public CallBinding withRun(String runId) {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, bootstrapEmpty);
        }

        public CallBinding withSlot(String stateSlot) {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, bootstrapEmpty);
        }

        public CallBinding withGeneration(long stateGeneration) {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, bootstrapEmpty);
        }

        public CallBinding withFence(long fence) {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, bootstrapEmpty);
        }

        public CallBinding withWriter(String writerId) {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, bootstrapEmpty);
        }

        public CallBinding bootstrap() {
            return new CallBinding(registrationId, userId, employeeReleaseId, businessSessionId, runId, stateSlot,
                    stateGeneration, sdkCallKey, fence, writerId, true);
        }
    }

    public record Freeze(String freezeId, String sdkCallKey, SaveStatus status, String reason, long expectedVersion,
            boolean beforeExists) {
    }

    public record SaveReceipt(SaveStatus status, String reason, Long savedVersion, String savedSha256,
            Long readbackVersion, String readbackSha256, boolean bootstrap, boolean executionContinued, String userId,
            String employeeReleaseId, String businessSessionId, String runId, String stateSlot, long stateGeneration,
            String sdkCallKey, long fence, String writerId) {
    }

    public record ReadResult(SaveStatus status, String reason, Long version, String sha256) {
    }

    public record RawRow(String storedSessionId, String stateData, long version, String sha256) {
    }

    public record Column(String name, String columnType, boolean nullable, String defaultValue, String extra,
            String columnKey) {
    }

    public record Index(String name, String column, int seq, boolean nonUnique) {
    }

    public record SchemaReport(String dialectClass, String jarName, String mysqlVersion, String tableName, int tableCount,
            String tableCollation, String ddl, String ddlSha256, String structureSha256, List<Column> columns,
            List<Index> indexes) {
        public String canonical() {
            StringBuilder builder = new StringBuilder();
            builder.append(mysqlVersion).append('\n').append(tableName).append('\n').append(tableCollation).append('\n');
            for (Column column : columns) {
                builder.append(column.name()).append('|').append(column.columnType()).append('|')
                        .append(column.nullable()).append('|').append(column.defaultValue()).append('|')
                        .append(column.extra()).append('|').append(column.columnKey()).append('\n');
            }
            for (Index index : indexes) {
                builder.append(index.name()).append('|').append(index.column()).append('|').append(index.seq())
                        .append('|').append(index.nonUnique()).append('\n');
            }
            return builder.toString();
        }

        public String toJson() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("issue", 25);
            payload.put("dialectClass", dialectClass);
            payload.put("jarName", jarName);
            payload.put("mysqlVersion", mysqlVersion);
            payload.put("tableName", tableName);
            payload.put("tableCount", tableCount);
            payload.put("tableCollation", tableCollation);
            payload.put("ddlSha256", ddlSha256);
            payload.put("structureSha256", structureSha256);
            payload.put("columns", columns);
            payload.put("indexes", indexes);
            payload.put("ddl", ddl);
            try {
                return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            } catch (Exception e) {
                throw new IllegalStateException("failed to write schema fingerprint", e);
            }
        }
    }
}
