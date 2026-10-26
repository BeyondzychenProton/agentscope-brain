package com.agentscope.brain.state;

import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 用锁定 JDBC 方言在隔离 MySQL 上核对 AgentState 表，不手写另一套列定义。 */
class SessionStateSchemaTest {
    private static IsolatedMysql mysql;

    @BeforeAll
    static void startIsolatedMysql() throws Exception {
        mysql = IsolatedMysql.start();
    }

    @AfterAll
    static void stopIsolatedMysql() throws Exception {
        if (mysql != null) {
            mysql.close();
        }
    }

    @Test
    void mysqlDialectSessionTableMatchesIsolatedInformationSchema() throws Exception {
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(mysql.dataSource())
                .autoCreateTable(false)
                .build();
        assertThat(dialect).isInstanceOf(MysqlDialect.class);
        assertThat(dialect.sessionStateTableName()).isEqualTo("agentscope_sessions");
        List<String> ddl = dialect.sessionStateCreateTableDdls();
        assertThat(ddl).hasSize(1);
        String create = ddl.get(0);
        assertThat(create).contains("agentscope_sessions", "session_id", "state_key", "item_index",
                "state_data", "version", "created_at", "updated_at", "PRIMARY KEY", "idx_session", "utf8mb4");

        new JdbcAgentStateStore(mysql.dataSource(), dialect, true);

        assertThat(tables()).containsExactly("agentscope_sessions");
        assertThat(columnFacts()).containsExactly(
                "session_id|varchar(255)|NO|PRI",
                "state_key|varchar(255)|NO|PRI",
                "item_index|int|NO|PRI",
                "state_data|longtext|NO|",
                "version|bigint|NO|",
                "created_at|datetime|YES|",
                "updated_at|datetime|YES|");
        assertThat(indexFacts()).containsExactlyInAnyOrder(
                "PRIMARY|session_id|1",
                "PRIMARY|state_key|2",
                "PRIMARY|item_index|3",
                "idx_session|session_id|1");
        assertThat(tableCollation()).isEqualTo("utf8mb4_unicode_ci");
        assertThat(showCreate()).contains("idx_session", "utf8mb4");
        System.out.println("ISSUE25_SCHEMA_FINGERPRINT " + fingerprint(dialect.getClass().getName(), create,
                String.join("\n", columnFacts()), mysql.version()));
    }

    private static List<String> tables() throws Exception {
        return query("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name");
    }

    private static List<String> columnFacts() throws Exception {
        return query("SELECT CONCAT(column_name, '|', column_type, '|', is_nullable, '|', column_key)"
                + " FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'agentscope_sessions' ORDER BY ordinal_position");
    }

    private static List<String> indexFacts() throws Exception {
        return query("SELECT CONCAT(index_name, '|', column_name, '|', seq_in_index)"
                + " FROM information_schema.statistics WHERE table_schema = DATABASE()"
                + " AND table_name = 'agentscope_sessions' ORDER BY index_name, seq_in_index");
    }

    private static String tableCollation() throws Exception {
        List<String> rows = query("SELECT table_collation FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = 'agentscope_sessions'");
        return rows.get(0);
    }

    private static String showCreate() throws Exception {
        try (Connection connection = mysql.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("SHOW CREATE TABLE agentscope_sessions");
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getString(2);
        }
    }

    private static List<String> query(String sql) throws Exception {
        try (Connection connection = mysql.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static String fingerprint(String... parts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String part : parts) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
