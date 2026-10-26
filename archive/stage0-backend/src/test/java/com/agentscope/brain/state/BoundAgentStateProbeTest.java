package com.agentscope.brain.state;

import io.agentscope.core.state.AgentState;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #25 普通 AgentState 探针。使用锁定 JDBC 扩展在一次性 MySQL 实例中核验会话状态表，
 * 不连接既有业务库，不覆盖 Memory／Workspace 的 KV 表。
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class BoundAgentStateProbeTest {
    private static IsolatedMysql mysql;
    private static DataSource dataSource;

    @BeforeAll
    static void startIsolatedMysql() throws Exception {
        mysql = IsolatedMysql.start();
        dataSource = mysql.dataSource();
    }

    @AfterAll
    static void stopIsolatedMysql() throws Exception {
        if (mysql != null) {
            mysql.close();
        }
    }

    @Test
    void lockedMysqlDialectSchemaMatchesLiveIsolatedTable() throws Exception {
        BoundAgentStateProbe probe = new BoundAgentStateProbe(dataSource);
        BoundAgentStateProbe.SchemaReport report = probe.schemaReport();

        assertThat(report.dialectClass()).endsWith("MysqlDialect");
        assertThat(report.jarName()).isEqualTo("agentscope-extensions-jdbc-2.0.4.jar");
        assertThat(report.mysqlVersion()).startsWith("8.");
        assertThat(report.tableCount()).isEqualTo(1);
        assertThat(report.tableName()).isEqualTo("issue25_agent_state");
        assertThat(report.ddl()).contains("CREATE TABLE IF NOT EXISTS issue25_agent_state");
        assertThat(report.tableCollation()).isEqualTo("utf8mb4_unicode_ci");

        List<ExpectedColumn> expected = columnsFromDialectDdl(report.ddl());
        assertThat(report.columns()).extracting(BoundAgentStateProbe.Column::name)
                .containsExactlyElementsOf(expected.stream().map(ExpectedColumn::name).toList());
        for (int i = 0; i < expected.size(); i++) {
            BoundAgentStateProbe.Column actual = report.columns().get(i);
            ExpectedColumn want = expected.get(i);
            assertThat(actual.columnType()).isEqualToIgnoringCase(want.type());
            assertThat(actual.nullable()).isEqualTo(want.nullable());
        }
        assertThat(report.indexes())
                .anySatisfy(index -> {
                    assertThat(index.name()).isEqualTo("PRIMARY");
                    assertThat(index.column()).isEqualTo("session_id");
                    assertThat(index.seq()).isEqualTo(1);
                })
                .anySatisfy(index -> {
                    assertThat(index.name()).isEqualTo("PRIMARY");
                    assertThat(index.column()).isEqualTo("state_key");
                    assertThat(index.seq()).isEqualTo(2);
                })
                .anySatisfy(index -> {
                    assertThat(index.name()).isEqualTo("PRIMARY");
                    assertThat(index.column()).isEqualTo("item_index");
                    assertThat(index.seq()).isEqualTo(3);
                })
                .anySatisfy(index -> {
                    assertThat(index.name()).isEqualTo("idx_session");
                    assertThat(index.column()).isEqualTo("session_id");
                    assertThat(index.nonUnique()).isTrue();
                });
        assertThat(report.structureSha256()).isEqualTo(sha256(report.canonical()));
        assertThat(report.ddlSha256()).hasSize(64);

        Path evidence = Path.of("evidence", "issue25");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("schema-fingerprint.json"), report.toJson(), StandardCharsets.UTF_8);
    }

    @Test
    void bootstrapSaveThenUpdateReadsBackWithoutModelOrTool() {
        BoundAgentStateProbe probe = new BoundAgentStateProbe(dataSource);
        BoundAgentStateProbe.SessionFact fact = probe.register(
                BoundAgentStateProbe.SessionKind.NEW, "user-a", "release-1", "session-a", "run-a",
                "slot-a", 3L, 7L, "writer-a");
        probe.recordModelCall();
        probe.recordToolCall();
        int models = probe.modelCalls();
        int tools = probe.toolCalls();

        BoundAgentStateProbe.CallBinding call = probe.bind(fact, "sdk-call-a").bootstrap();
        BoundAgentStateProbe.Freeze freeze = probe.freeze(call);
        assertThat(freeze.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.READY);
        assertThat(freeze.beforeExists()).isFalse();
        assertThat(freeze.expectedVersion()).isZero();

        AgentState created = state("user-a", "slot-a", "summary-v1");
        BoundAgentStateProbe.SaveReceipt saved = probe.commit(call, freeze, created, BoundAgentStateProbe.CAS_FAIL);
        assertThat(saved.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.SAVED);
        assertThat(saved.savedVersion()).isEqualTo(1L);
        assertThat(saved.readbackVersion()).isEqualTo(1L);
        assertThat(saved.readbackSha256()).isEqualTo(saved.savedSha256()).hasSize(64);
        assertThat(saved.bootstrap()).isTrue();
        assertThat(saved.userId()).isEqualTo("user-a");
        assertThat(saved.employeeReleaseId()).isEqualTo("release-1");
        assertThat(saved.businessSessionId()).isEqualTo("session-a");
        assertThat(saved.runId()).isEqualTo("run-a");
        assertThat(saved.stateSlot()).isEqualTo("slot-a");
        assertThat(saved.stateGeneration()).isEqualTo(3L);
        assertThat(saved.sdkCallKey()).isEqualTo("sdk-call-a");
        assertThat(saved.fence()).isEqualTo(7L);
        assertThat(saved.writerId()).isEqualTo("writer-a");
        assertThat(saved.executionContinued()).isFalse();
        assertThat(probe.modelCalls()).isEqualTo(models);
        assertThat(probe.toolCalls()).isEqualTo(tools);
        assertThat(probe.overwriteAttempts()).isZero();
        assertThat(probe.mergeAttempts()).isZero();

        BoundAgentStateProbe.RawRow raw = probe.readRaw(call);
        assertThat(raw.storedSessionId()).isEqualTo("user-a:slot-a");
        assertThat(raw.version()).isEqualTo(1L);
        assertThat(raw.sha256()).isEqualTo(saved.savedSha256());
        assertThat(raw.stateData()).contains("summary-v1");

        BoundAgentStateProbe.CallBinding update = probe.bind(fact, "sdk-call-a2");
        BoundAgentStateProbe.Freeze again = probe.freeze(update);
        assertThat(again.beforeExists()).isTrue();
        assertThat(again.expectedVersion()).isEqualTo(1L);
        BoundAgentStateProbe.SaveReceipt updated = probe.commit(
                update, again, state("user-a", "slot-a", "summary-v2"), BoundAgentStateProbe.CAS_FAIL);
        assertThat(updated.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.SAVED);
        assertThat(updated.savedVersion()).isEqualTo(2L);
        assertThat(updated.readbackSha256()).isEqualTo(updated.savedSha256());
        assertThat(updated.bootstrap()).isFalse();
        BoundAgentStateProbe.RawRow after = probe.readRaw(update);
        assertThat(after.version()).isEqualTo(2L);
        assertThat(after.stateData()).contains("summary-v2").doesNotContain("summary-v1");
        assertThat(after.sha256()).isNotEqualTo(raw.sha256());
        assertThat(probe.modelCalls()).isEqualTo(models);
        assertThat(probe.toolCalls()).isEqualTo(tools);
    }

    @Test
    void conflictKeepsCommittedStateAndDoesNotOverwriteMergeOrContinue() {
        BoundAgentStateProbe probe = new BoundAgentStateProbe(dataSource);
        BoundAgentStateProbe.SessionFact fact = probe.register(
                BoundAgentStateProbe.SessionKind.NEW, "user-b", "release-1", "session-b", "run-b",
                "slot-b", 1L, 4L, "writer-b");
        BoundAgentStateProbe.CallBinding winner = probe.bind(fact, "sdk-win").bootstrap();
        probe.commit(winner, probe.freeze(winner), state("user-b", "slot-b", "kept"), BoundAgentStateProbe.CAS_FAIL);

        BoundAgentStateProbe.CallBinding stale = probe.bind(fact, "sdk-stale");
        BoundAgentStateProbe.Freeze frozen = probe.freeze(stale);
        assertThat(frozen.expectedVersion()).isEqualTo(1L);
        BoundAgentStateProbe.CallBinding newer = probe.bind(fact, "sdk-newer");
        BoundAgentStateProbe.SaveReceipt advanced = probe.commit(
                newer, probe.freeze(newer), state("user-b", "slot-b", "kept-v2"), BoundAgentStateProbe.CAS_FAIL);
        assertThat(advanced.savedVersion()).isEqualTo(2L);

        int models = probe.modelCalls();
        int tools = probe.toolCalls();
        BoundAgentStateProbe.SaveReceipt conflict = probe.commit(
                stale, frozen, state("user-b", "slot-b", "lost"), BoundAgentStateProbe.CAS_FAIL);
        assertThat(conflict.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.CAS_FAIL);
        assertThat(conflict.executionContinued()).isFalse();
        assertThat(conflict.readbackVersion()).isEqualTo(2L);
        assertThat(conflict.readbackSha256()).isEqualTo(advanced.savedSha256());
        assertThat(probe.readRaw(stale).stateData()).contains("kept-v2").doesNotContain("lost");
        assertThat(probe.overwriteAttempts()).isZero();
        assertThat(probe.mergeAttempts()).isZero();
        assertThat(probe.modelCalls()).isEqualTo(models);
        assertThat(probe.toolCalls()).isEqualTo(tools);

        BoundAgentStateProbe.Freeze ready = probe.freeze(probe.bind(fact, "sdk-policy"));
        for (String policy : List.of("OVERWRITE", "APPEND_MERGE", "FAIL")) {
            BoundAgentStateProbe.SaveReceipt refused = probe.commit(
                    probe.bind(fact, "sdk-policy"), ready, state("user-b", "slot-b", "nope"), policy);
            assertThat(refused.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.REJECTED);
            assertThat(refused.reason()).isEqualTo("POLICY_FORBIDDEN");
        }
        assertThat(probe.readRaw(stale).stateData()).contains("kept-v2").doesNotContain("nope");
        assertThat(probe.overwriteAttempts()).isZero();
        assertThat(probe.mergeAttempts()).isZero();
    }

    @Test
    void wrongBindingAndOldWriterDoNotWrite() {
        BoundAgentStateProbe probe = new BoundAgentStateProbe(dataSource);
        BoundAgentStateProbe.SessionFact fact = probe.register(
                BoundAgentStateProbe.SessionKind.NEW, "user-c", "release-9", "session-c", "run-c",
                "slot-c", 2L, 5L, "writer-c");
        BoundAgentStateProbe.CallBinding good = probe.bind(fact, "sdk-c").bootstrap();
        probe.commit(good, probe.freeze(good), state("user-c", "slot-c", "original"), BoundAgentStateProbe.CAS_FAIL);
        String digest = probe.readRaw(good).sha256();

        assertRejected(probe, probe.bind(fact, "sdk-user").withUser("user-x"), "WRONG_USER");
        assertRejected(probe, probe.bind(fact, "sdk-session").withBusinessSession("session-x"), "WRONG_BUSINESS_SESSION");
        assertRejected(probe, probe.bind(fact, "sdk-release").withRelease("release-x"), "WRONG_RELEASE");
        assertRejected(probe, probe.bind(fact, "sdk-run").withRun("run-x"), "WRONG_RUN");
        assertRejected(probe, probe.bind(fact, "sdk-slot").withSlot("slot-x"), "WRONG_SLOT");
        assertRejected(probe, probe.bind(fact, "sdk-gen").withGeneration(9L), "WRONG_GENERATION");
        assertRejected(probe, probe.bind(fact, "sdk-fence").withFence(4L), "OLD_FENCE");
        assertRejected(probe, probe.bind(fact, "sdk-writer").withWriter("writer-old"), "OLD_WRITER");

        BoundAgentStateProbe.RawRow raw = probe.readRaw(good);
        assertThat(raw.version()).isEqualTo(1L);
        assertThat(raw.sha256()).isEqualTo(digest);
        assertThat(raw.stateData()).contains("original");
        assertThat(probe.readRaw(probe.bind(fact, "sdk-slot").withSlot("slot-x"))).isNull();
        assertThat(probe.readRaw(probe.bind(fact, "sdk-user").withUser("user-x"))).isNull();
    }

    @Test
    void existingSessionMissingStateIsReconcilingAndRecoveryDoesNotCreateEmpty() {
        BoundAgentStateProbe probe = new BoundAgentStateProbe(dataSource);
        BoundAgentStateProbe.SessionFact existing = probe.register(
                BoundAgentStateProbe.SessionKind.EXISTING, "user-d", "release-1", "session-d", "run-d",
                "slot-d", 1L, 1L, "writer-d");
        BoundAgentStateProbe.CallBinding call = probe.bind(existing, "sdk-d").bootstrap();
        BoundAgentStateProbe.Freeze freeze = probe.freeze(call);
        assertThat(freeze.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.RECONCILING);
        assertThat(freeze.reason()).isEqualTo("MISSING_EXISTING_STATE");
        BoundAgentStateProbe.SaveReceipt commit = probe.commit(
                call, freeze, state("user-d", "slot-d", "should-not-exist"), BoundAgentStateProbe.CAS_FAIL);
        assertThat(commit.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.RECONCILING);
        assertThat(probe.readRaw(call)).isNull();

        BoundAgentStateProbe.ReadResult recovered = probe.recover(probe.bind(existing, "sdk-d-read"));
        BoundAgentStateProbe.ReadResult read = probe.read(probe.bind(existing, "sdk-d-get"));
        assertThat(recovered.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.RECONCILING);
        assertThat(read.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.RECONCILING);
        assertThat(probe.readRaw(call)).isNull();
        assertThat(probe.modelCalls()).isZero();
        assertThat(probe.toolCalls()).isZero();

        BoundAgentStateProbe.SessionFact fresh = probe.register(
                BoundAgentStateProbe.SessionKind.NEW, "user-e", "release-1", "session-e", "run-e",
                "slot-e", 1L, 1L, "writer-e");
        BoundAgentStateProbe.ReadResult newRecover = probe.recover(probe.bind(fresh, "sdk-e-read"));
        assertThat(newRecover.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.ABSENT);
        assertThat(probe.readRaw(probe.bind(fresh, "sdk-e-read"))).isNull();
    }

    @Test
    void concurrentCommitsLeaveExactlyOneState() throws Exception {
        BoundAgentStateProbe probe = new BoundAgentStateProbe(dataSource);
        BoundAgentStateProbe.SessionFact fact = probe.register(
                BoundAgentStateProbe.SessionKind.NEW, "user-f", "release-1", "session-f", "run-f",
                "slot-f", 1L, 2L, "writer-f");
        BoundAgentStateProbe.CallBinding left = probe.bind(fact, "sdk-left").bootstrap();
        BoundAgentStateProbe.CallBinding right = probe.bind(fact, "sdk-right").bootstrap();
        BoundAgentStateProbe.Freeze leftFreeze = probe.freeze(left);
        BoundAgentStateProbe.Freeze rightFreeze = probe.freeze(right);
        assertThat(leftFreeze.expectedVersion()).isZero();
        assertThat(rightFreeze.expectedVersion()).isZero();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BoundAgentStateProbe.SaveReceipt> one = pool.submit(() -> probe.commit(
                    left, leftFreeze, state("user-f", "slot-f", "left-body"), BoundAgentStateProbe.CAS_FAIL));
            Future<BoundAgentStateProbe.SaveReceipt> two = pool.submit(() -> probe.commit(
                    right, rightFreeze, state("user-f", "slot-f", "right-body"), BoundAgentStateProbe.CAS_FAIL));
            List<BoundAgentStateProbe.SaveReceipt> results = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            assertThat(results).filteredOn(item -> item.status() == BoundAgentStateProbe.SaveStatus.SAVED).hasSize(1);
            assertThat(results).filteredOn(item -> item.status() == BoundAgentStateProbe.SaveStatus.CAS_FAIL).hasSize(1);
            assertThat(results).allMatch(item -> !item.executionContinued());
        } finally {
            pool.shutdownNow();
        }
        BoundAgentStateProbe.RawRow raw = probe.readRaw(left);
        assertThat(raw.version()).isEqualTo(1L);
        assertThat(raw.stateData()).satisfiesAnyOf(
                data -> assertThat(data).contains("left-body").doesNotContain("right-body"),
                data -> assertThat(data).contains("right-body").doesNotContain("left-body"));
        assertThat(probe.overwriteAttempts()).isZero();
        assertThat(probe.mergeAttempts()).isZero();
        assertThat(probe.modelCalls()).isZero();
        assertThat(probe.toolCalls()).isZero();
    }

    private static void assertRejected(BoundAgentStateProbe probe, BoundAgentStateProbe.CallBinding call, String reason) {
        BoundAgentStateProbe.Freeze freeze = probe.freeze(call);
        assertThat(freeze.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.REJECTED);
        assertThat(freeze.reason()).isEqualTo(reason);
        BoundAgentStateProbe.SaveReceipt receipt = probe.commit(
                call, freeze, state(call.userId(), call.stateSlot(), "rejected"), BoundAgentStateProbe.CAS_FAIL);
        assertThat(receipt.status()).isEqualTo(BoundAgentStateProbe.SaveStatus.REJECTED);
        assertThat(receipt.reason()).isEqualTo(reason);
        assertThat(receipt.executionContinued()).isFalse();
    }

    private static AgentState state(String userId, String slot, String summary) {
        return AgentState.builder().userId(userId).sessionId(slot).summary(summary).context(List.of()).build();
    }

    private static List<ExpectedColumn> columnsFromDialectDdl(String ddl) {
        List<ExpectedColumn> columns = new ArrayList<>();
        for (String line : ddl.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("CREATE") || trimmed.startsWith("PRIMARY")
                    || trimmed.startsWith("INDEX") || trimmed.startsWith(")") || trimmed.startsWith("(")) {
                continue;
            }
            String bare = trimmed.replaceFirst(",$", "");
            String[] parts = bare.split("\\s+");
            columns.add(new ExpectedColumn(parts[0], parts[1], !bare.toUpperCase(Locale.ROOT).contains("NOT NULL")));
        }
        return columns;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record ExpectedColumn(String name, String type, boolean nullable) {
    }

    /** 只启动本测试创建的临时 MySQL，不使用 3306 上的既有实例。 */
    private static final class IsolatedMysql implements AutoCloseable {
        private final Process process;
        private final Path root;
        private final DataSource dataSource;
        private final int port;

        private IsolatedMysql(Process process, Path root, DataSource dataSource, int port) {
            this.process = process;
            this.root = root;
            this.dataSource = dataSource;
            this.port = port;
        }

        static IsolatedMysql start() throws Exception {
            Path home = mysqlHome();
            Path mysqld = home.resolve("bin").resolve("mysqld.exe");
            Path mysql = home.resolve("bin").resolve("mysql.exe");
            Path mysqladmin = home.resolve("bin").resolve("mysqladmin.exe");
            assertThat(Files.isRegularFile(mysqld)).isTrue();
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Path root = Files.createTempDirectory("agentscope-issue25-mysql-");
            Path data = root.resolve("data");
            Path log = root.resolve("mysql.log");
            run(home, List.of(mysqld.toString(), "--no-defaults", "--initialize-insecure",
                    "--basedir=" + home, "--datadir=" + data), Duration.ofSeconds(120));
            Process server = new ProcessBuilder(mysqld.toString(), "--no-defaults",
                    "--basedir=" + home.toString(), "--datadir=" + data.toString(),
                    "--bind-address=127.0.0.1", "--port=" + port, "--mysqlx=0", "--skip-log-bin",
                    "--log-error=" + log, "--pid-file=" + root.resolve("mysql.pid"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String url = "jdbc:mysql://127.0.0.1:" + port + "/?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000";
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            SQLException last = null;
            while (System.nanoTime() < deadline) {
                if (!server.isAlive()) {
                    throw new IllegalStateException("临时 MySQL 提前退出：" + tail(log));
                }
                try (Connection connection = DriverManager.getConnection(url, "root", "");
                        Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                    last = null;
                    break;
                } catch (SQLException e) {
                    last = e;
                    Thread.sleep(250);
                }
            }
            if (last != null) {
                server.destroyForcibly();
                throw new IllegalStateException("临时 MySQL 启动超时：" + tail(log), last);
            }
            try (Connection connection = DriverManager.getConnection(url, "root", "");
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE issue25_state CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            com.mysql.cj.jdbc.MysqlDataSource source = new com.mysql.cj.jdbc.MysqlDataSource();
            source.setUrl("jdbc:mysql://127.0.0.1:" + port
                    + "/issue25_state?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=utf8");
            source.setUser("root");
            source.setPassword("");
            IsolatedMysql isolated = new IsolatedMysql(server, root, source, port);
            isolated.mysqlAdmin = mysqladmin;
            return isolated;
        }

        private Path mysqlAdmin;

        DataSource dataSource() {
            return dataSource;
        }

        @Override
        public void close() throws Exception {
            if (mysqlAdmin != null) {
                runQuiet(List.of(mysqlAdmin.toString(), "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                        "--port=" + port, "--user=root", "--password=", "shutdown"));
            }
            process.waitFor(20, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            deleteTree(root);
        }

        private static Path mysqlHome() {
            String configured = System.getProperty("issue25.mysql.home", System.getenv("ISSUE25_MYSQL_HOME"));
            if (configured != null && !configured.isBlank()) {
                return Path.of(configured);
            }
            return Path.of("D:\\code environment\\mysql\\mysql-8.0.45-winx64");
        }

        private static void run(Path home, List<String> command, Duration timeout) throws Exception {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("MySQL 初始化失败：" + new String(output, StandardCharsets.UTF_8));
            }
        }

        private static void runQuiet(List<String> command) {
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                process.waitFor(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 关闭失败时由 destroyForcibly 接手。
            }
        }

        private static String tail(Path log) {
            try {
                String text = Files.readString(log);
                return text.substring(Math.max(0, text.length() - 2000));
            } catch (IOException e) {
                return e.getMessage();
            }
        }

        private static void deleteTree(Path root) throws IOException {
            if (!Files.exists(root)) {
                return;
            }
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 进程退出后的残留文件不影响下一次独立数据目录。
                    }
                });
            }
        }
    }
}
