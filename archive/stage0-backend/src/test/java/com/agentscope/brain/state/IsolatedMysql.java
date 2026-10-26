package com.agentscope.brain.state;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 本轮独占的临时 MySQL。只初始化自己的数据目录，核对 {@code @@datadir} 之后才允许关闭；
 * 不连接、不关闭本机 3306 上已有的实例。临时实例用 {@code --initialize-insecure}，root 空密码；
 * 不使用本机业务库账号。
 */
public final class IsolatedMysql implements AutoCloseable {
    static final String SCHEMA = "issue25_agent_state";

    private final Process server;
    private final Path root;
    private final Path dataDir;
    private final int port;
    private final String schema;
    private final DataSource dataSource;
    private final String version;
    private boolean identityVerified;

    private IsolatedMysql(Process server, Path root, Path dataDir, int port, String schema,
            DataSource dataSource, String version, boolean identityVerified) {
        this.server = server;
        this.root = root;
        this.dataDir = dataDir;
        this.port = port;
        this.schema = schema;
        this.dataSource = dataSource;
        this.version = version;
        this.identityVerified = identityVerified;
    }

    public static IsolatedMysql start() throws Exception {
        return start(SCHEMA);
    }

    public static IsolatedMysql start(String schema) throws Exception {
        requireIdent(schema);
        Path bin = mysqlBin();
        Path baseDir = bin.getParent();
        Path root = Files.createTempDirectory("agentscope-" + schema + "-mysql-");
        Path dataDir = root.resolve("data");
        Path log = root.resolve("mysql.log");
        int port = freePort();
        Map<String, String> env = isolatedEnvironment(root);
        runAndWait(List.of(bin.resolve("mysqld.exe").toString(), "--no-defaults", "--initialize-insecure",
                "--basedir=" + baseDir, "--datadir=" + dataDir), env, Duration.ofSeconds(120), log);
        ProcessBuilder builder = new ProcessBuilder(bin.resolve("mysqld.exe").toString(), "--no-defaults",
                "--basedir=" + baseDir, "--datadir=" + dataDir, "--bind-address=127.0.0.1", "--port=" + port,
                "--mysqlx=0", "--skip-log-bin", "--log-error=" + log, "--pid-file=" + root.resolve("mysql.pid"));
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process server = builder.start();
        try {
            waitUntilReady(server, port, log);
            assertDataDir(port, dataDir);
            createSchema(port, schema);
            DataSource dataSource = schemaDataSource(port, schema);
            String version = queryVersion(dataSource);
            return new IsolatedMysql(server, root, dataDir, port, schema, dataSource, version, true);
        } catch (Exception failure) {
            new IsolatedMysql(server, root, dataDir, port, schema, null, null, false).close();
            throw failure;
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public String version() {
        return version;
    }

    public int port() {
        return port;
    }

    @Override
    public void close() throws Exception {
        try {
            if (server != null && server.isAlive()) {
                if (identityVerified) {
                    Path admin = mysqlBin().resolve("mysqladmin.exe");
                    Process shutdown = new ProcessBuilder(admin.toString(), "--no-defaults", "--protocol=TCP",
                            "--host=127.0.0.1", "--port=" + port, "--user=root", "--password=", "shutdown")
                            .redirectErrorStream(true)
                            .start();
                    if (!shutdown.waitFor(20, TimeUnit.SECONDS) || shutdown.exitValue() != 0) {
                        killOwnedProcess();
                    }
                } else {
                    killOwnedProcess();
                }
                server.waitFor(20, TimeUnit.SECONDS);
            }
        } finally {
            identityVerified = false;
            if (root != null && root.getFileName().toString().startsWith("agentscope-")
                    && root.getFileName().toString().contains("-mysql-")) {
                deleteTree(root);
            }
        }
    }

    private void killOwnedProcess() throws Exception {
        Process kill = new ProcessBuilder("taskkill", "/PID", Long.toString(server.pid()), "/T", "/F")
                .redirectErrorStream(true)
                .start();
        kill.waitFor(15, TimeUnit.SECONDS);
    }

    private static void assertDataDir(int port, Path expected) throws Exception {
        String actual = scalar(port, null, "SELECT @@datadir");
        Path actualPath = normalize(actual);
        Path expectedPath = expected.toAbsolutePath().normalize();
        if (!actualPath.equals(expectedPath)) {
            throw new IllegalStateException("拒绝连接数据目录不属于本轮临时实例的 MySQL：" + actualPath);
        }
    }

    private static void createSchema(int port, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(serverUrl(port), "root", "");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private static String queryVersion(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT VERSION()")) {
            if (!rows.next()) {
                throw new IllegalStateException("临时 MySQL 没有返回版本");
            }
            String version = rows.getString(1);
            if (version.toLowerCase().contains("mariadb")) {
                throw new IllegalStateException("需要 MySQL，当前是 " + version);
            }
            return version;
        }
    }

    private static void waitUntilReady(Process server, int port, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                throw new IllegalStateException("临时 MySQL 提前退出：" + tail(log));
            }
            try {
                scalar(port, null, "SELECT 1");
                return;
            } catch (Exception failure) {
                last = failure;
                Thread.sleep(250);
            }
        }
        throw new IllegalStateException("临时 MySQL 启动超时：" + tail(log), last);
    }

    private static String scalar(int port, String schema, String sql) throws Exception {
        String url = schema == null ? serverUrl(port) : schemaUrl(port, schema);
        try (Connection connection = DriverManager.getConnection(url, "root", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("查询没有结果：" + sql);
            }
            return rows.getString(1);
        }
    }

    private static DataSource schemaDataSource(int port, String schema) {
        return new DriverManagerDataSource(schemaUrl(port, schema), "root", "");
    }

    private static String serverUrl(int port) {
        return "jdbc:mysql://127.0.0.1:" + port
                + "/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=30000";
    }

    private static String schemaUrl(int port, String schema) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=30000";
    }

    private static void requireIdent(String schema) {
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("illegal schema name: " + schema);
        }
    }

    private static Path mysqlBin() {
        String configured = System.getenv("AGENTSCOPE_MYSQL_BIN");
        Path bin = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of("D:/code environment/mysql/mysql-8.0.45-winx64/bin");
        if (!Files.isRegularFile(bin.resolve("mysqld.exe")) || !Files.isRegularFile(bin.resolve("mysqladmin.exe"))) {
            throw new IllegalStateException("未找到隔离探针用的 mysqld.exe／mysqladmin.exe：" + bin
                    + "。可设置 AGENTSCOPE_MYSQL_BIN。不使用本机 3306 上已有的实例。");
        }
        return bin;
    }

    private static Map<String, String> isolatedEnvironment(Path root) {
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        env.remove("MYSQL_PWD");
        env.put("MYSQL_TEST_LOGIN_FILE", root.resolve("unused-login.cnf").toString());
        return env;
    }

    private static void runAndWait(List<String> command, Map<String, String> env, Duration timeout, Path log)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process process = builder.start();
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("MySQL 初始化超时：" + tail(log));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("MySQL 初始化失败，退出码 " + process.exitValue() + "：" + tail(log));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path normalize(String raw) {
        String trimmed = raw.trim();
        while (trimmed.endsWith("\\") || trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Path.of(trimmed).toAbsolutePath().normalize();
    }

    private static String tail(Path log) {
        try {
            String text = Files.readString(log);
            return text.substring(Math.max(0, text.length() - 2000));
        } catch (IOException failure) {
            return "无法读取日志：" + failure.getMessage();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.forEach(paths::add);
        }
        for (int i = paths.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(paths.get(i));
        }
    }
}
