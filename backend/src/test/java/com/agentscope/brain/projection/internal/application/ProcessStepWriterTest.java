package com.agentscope.brain.projection.internal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ProcessStepWriterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0, 0);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ProcessStepWriter writer;

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:proc-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        writer = new ProcessStepWriter(jdbc, new HistoryCursor(""));
        jdbc.execute("""
            CREATE TABLE business_session(
              id BIGINT PRIMARY KEY,user_id BIGINT,process_projection_stream_id BINARY(16),
              last_process_projection_seq BIGINT NOT NULL DEFAULT 0,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE projection_process_step(
              step_id BINARY(16) PRIMARY KEY,owner_user_id BIGINT,session_id BIGINT,run_id BIGINT,
              stream_id BINARY(16),step_kind VARCHAR(24),step_status VARCHAR(16),title VARCHAR(200),
              summary VARCHAR(500),first_seq BIGINT,first_position SMALLINT,view_revision BIGINT,
              collapsed_by_default TINYINT,operation_id BIGINT,current_detail_version BIGINT,
              gmt_create TIMESTAMP,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE projection_process_step_detail(
              step_id BINARY(16),detail_version BIGINT,body BLOB,byte_count INT,body_sha256 BINARY(32),
              payload_storage VARCHAR(16),projection_seq BIGINT,gmt_create TIMESTAMP,gmt_modified TIMESTAMP,
              PRIMARY KEY(step_id,detail_version))
            """);
        jdbc.execute("""
            CREATE TABLE session_projection_event(
              session_id BIGINT,stream_id BINARY(16),seq BIGINT,previous_seq BIGINT,
              protocol_version VARCHAR(32),batch_payload BLOB,byte_count INT,batch_sha256 BINARY(32),
              change_count INT,occurred_at TIMESTAMP,gmt_create TIMESTAMP,gmt_modified TIMESTAMP)
            """);
        jdbc.update("INSERT INTO business_session VALUES(10,100,NULL,0,?)", now);
    }

    @Test void upsertChangeUsesFrozenFieldNames() {
        var fact = new ProcessStepWriter.Fact(
                "100", "10", "20", "11111111-1111-1111-1111-111111111111",
                "执行完成", null, "DONE", false, null, null, now);
        Map<String, Object> change = ProcessStepWriter.buildUpsertChange(fact, "STAGE", "1", "1", "1");
        assertThat(change.keySet()).containsExactly(
                "type", "stepId", "runId", "kind", "title", "summary", "status",
                "orderAnchor", "viewRevision", "collapsedByDefault", "operationId");
        assertThat(change.get("type")).isEqualTo("PROCESS_STEP_UPSERT");
        assertThat(change.get("kind")).isEqualTo("STAGE");
        assertThat(change.get("orderAnchor")).isEqualTo(Map.of("seq", "1", "index", "1"));
        assertThat(change.get("viewRevision")).isEqualTo("1");
    }

    @Test void writesProcessBatchAndPreservesFirstAnchorOnUpdate() throws Exception {
        String stepId = UUID.nameUUIDFromBytes("stage-20".getBytes(StandardCharsets.UTF_8)).toString();
        tx.execute(status -> {
            writer.upsertStage(new ProcessStepWriter.Fact(
                    "100", "10", "20", stepId, "执行中", "开始", "RUNNING", false, null, null, now));
            return null;
        });
        tx.execute(status -> {
            writer.upsertStage(new ProcessStepWriter.Fact(
                    "100", "10", "20", stepId, "执行完成", null, "DONE", false, null, "done detail", now));
            return null;
        });

        assertThat(jdbc.queryForObject("SELECT last_process_projection_seq FROM business_session WHERE id=10", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT process_projection_stream_id IS NOT NULL FROM business_session WHERE id=10", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT first_seq FROM projection_process_step", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT first_position FROM projection_process_step", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT view_revision FROM projection_process_step", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT step_status FROM projection_process_step", String.class)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT current_detail_version FROM projection_process_step", Long.class))
                .isEqualTo(1L);

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT * FROM session_projection_event WHERE protocol_version=? ORDER BY seq",
                ProcessStepWriter.PROTOCOL);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("seq")).isEqualTo(1L);
        assertThat(events.get(1).get("seq")).isEqualTo(2L);
        assertThat(events.get(1).get("previous_seq")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> batch = json.readValue((byte[]) events.get(1).get("batch_payload"), Map.class);
        assertThat(batch.get("protocolVersion")).isEqualTo(ProcessStepWriter.PROTOCOL);
        assertThat(batch.get("seq")).isEqualTo("2");
        assertThat(batch.get("previousSeq")).isEqualTo("1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) batch.get("changes");
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).get("type")).isEqualTo("PROCESS_STEP_UPSERT");
        assertThat(changes.get(0).get("status")).isEqualTo("DONE");
        assertThat(changes.get(1).get("type")).isEqualTo("PROCESS_STEP_DETAIL_SET");
        assertThat(changes.get(1).get("detailVersion")).isEqualTo("1");
        assertThat(changes.get(1).get("byteLength")).isEqualTo(
                Integer.toString("done detail".getBytes(StandardCharsets.UTF_8).length));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("done detail".getBytes(StandardCharsets.UTF_8));
        assertThat(changes.get(1).get("sha256")).isEqualTo(java.util.HexFormat.of().formatHex(digest));
    }

    @Test void rejectsOversizedDetailWithoutWriting() {
        String stepId = UUID.randomUUID().toString();
        byte[] oversized = new byte[ProcessStepSecurity.MAX_INLINE_UTF8_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');
        assertThatThrownBy(() -> tx.execute(status -> {
            writer.upsertStage(new ProcessStepWriter.Fact(
                    "100", "10", "20", stepId, "执行完成", null, "DONE", false, null,
                    new String(oversized, StandardCharsets.UTF_8), now));
            return null;
        })).isInstanceOf(ProcessStepSecurity.Rejected.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projection_process_step", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_projection_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_process_projection_seq FROM business_session WHERE id=10", Long.class))
                .isEqualTo(0L);
    }
}
