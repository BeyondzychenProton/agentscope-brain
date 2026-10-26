package com.agentscope.brain.architecture;

import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class NativeStructureContractTest {
    @Test void sessionDdlComesFromTheActualPinnedJar() throws Exception {
        try (var input = new ClassPathResource("org/springframework/session/jdbc/schema-mysql.sql").getInputStream()) {
            assertThat(Files.readAllBytes(Path.of("../db/vendor/spring-session-4.1.1-mysql.sql"))).isEqualTo(input.readAllBytes());
        }
    }
    @Test void sdkDdlMatchesTheActualDialectWithoutConnectingOrAutoCreatingTables() throws Exception {
        var dialect = new MysqlDialect();
        var actual = String.join(";", dialect.storeCreateTableDdls()) + ";"
                + String.join(";", dialect.sessionStateCreateTableDdls()) + ";"
                + String.join(";", dialect.snapshotCreateTableDdls()) + ";";
        assertThat(normalize(Files.readString(Path.of("../db/vendor/agentscope-2.0.4-mysql.sql"), StandardCharsets.UTF_8)))
                .isEqualTo(normalize(actual));
    }
    private String normalize(String sql) {
        return sql.replaceAll("(?m)^--[^\\r\\n]*", "").replaceAll("(?i)IF NOT EXISTS", "")
                .replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
