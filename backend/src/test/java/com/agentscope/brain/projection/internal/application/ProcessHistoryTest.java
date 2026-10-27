package com.agentscope.brain.projection.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
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
import static org.mockito.Mockito.*;

class ProcessHistoryTest {
    private final CurrentUser users = mock(CurrentUser.class);
    private final Instant at = Instant.parse("2026-10-27T12:00:00Z");
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0, 0);
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ProcessStepWriter writer;
    private ProcessHistory history;

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:proc-hist-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var cursors = new HistoryCursor(key);
        writer = new ProcessStepWriter(jdbc, cursors);
        history = new ProcessHistory(jdbc, users, cursors);
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
        jdbc.update("INSERT INTO business_session VALUES(11,200,NULL,0,?)", now);
        actor("100");
    }

    private void actor(String id) {
        when(users.require()).thenReturn(new CurrentUser.User(id, "用户", "***", false, at, at.plusSeconds(86400)));
    }

    @Test void rejectsUnsupportedProtocolVersion() {
        assertThatThrownBy(() -> history.snapshot("10", "web-projection-v1", 50))
                .isInstanceOfSatisfying(ProjectionReadFailure.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("PROJECTION_VERSION_UNSUPPORTED");
                });
        assertThatThrownBy(() -> history.after("10", "web-projection-v1", "x"))
                .isInstanceOfSatisfying(ProjectionReadFailure.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("PROJECTION_VERSION_UNSUPPORTED");
                });
        assertThatThrownBy(() -> history.detail("10", "other", UUID.randomUUID().toString(), "1"))
                .isInstanceOfSatisfying(ProjectionReadFailure.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("PROJECTION_VERSION_UNSUPPORTED");
                });
    }

    @Test void foreignSessionIsNotFound() {
        assertThatThrownBy(() -> history.snapshot("11", ProcessHistory.PROTOCOL, 50))
                .isInstanceOfSatisfying(ProjectionReadFailure.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.getMessage()).isEqualTo("NOT_FOUND");
                });
    }

    @Test void emptySessionReturnsZeroWatermarkSnapshot() {
        var snap = history.snapshot("10", ProcessHistory.PROTOCOL, 50);
        assertThat(snap.protocolVersion()).isEqualTo(ProcessHistory.PROTOCOL);
        assertThat(snap.sessionId()).isEqualTo("10");
        assertThat(snap.watermark()).isEqualTo("0");
        assertThat(snap.streamId()).isEmpty();
        assertThat(snap.steps()).isEmpty();
        assertThat(snap.cursor()).isNotBlank();

        var page = history.after("10", ProcessHistory.PROTOCOL, snap.cursor());
        assertThat(page.batches()).isEmpty();
        assertThat(page.watermark()).isEqualTo("0");
    }

    @Test void snapshotAfterAndDetailRoundTrip() throws Exception {
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

        var snap = history.snapshot("10", ProcessHistory.PROTOCOL, 50);
        assertThat(snap.watermark()).isEqualTo("2");
        assertThat(snap.streamId()).isNotBlank();
        assertThat(snap.steps()).hasSize(1);
        ProcessHistory.Step step = snap.steps().get(0);
        assertThat(step.stepId()).isEqualTo(stepId);
        assertThat(step.runId()).isEqualTo("20");
        assertThat(step.kind()).isEqualTo("STAGE");
        assertThat(step.status()).isEqualTo("DONE");
        assertThat(step.orderAnchor()).isEqualTo(new ProcessHistory.OrderAnchor("1", "1"));
        assertThat(step.viewRevision()).isEqualTo("2");
        assertThat(step.detail()).isNull();
        assertThat(step.detailVersion()).isEqualTo("1");
        assertThat(step.detailRef()).isNotNull();
        assertThat(step.detailRef().detailVersion()).isEqualTo("1");
        assertThat(step.detailRef().byteLength()).isEqualTo(
                Integer.toString("done detail".getBytes(StandardCharsets.UTF_8).length));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("done detail".getBytes(StandardCharsets.UTF_8));
        assertThat(step.detailRef().sha256()).isEqualTo(java.util.HexFormat.of().formatHex(digest));

        var empty = history.after("10", ProcessHistory.PROTOCOL, snap.cursor());
        assertThat(empty.batches()).isEmpty();

        String mid = cursorsForWatermark(snap, "1");
        var page = history.after("10", ProcessHistory.PROTOCOL, mid);
        assertThat(page.batches()).hasSize(1);
        assertThat(page.batches().get(0).get("seq")).isEqualTo("2");
        assertThat(page.batches().get(0).get("protocolVersion")).isEqualTo(ProcessHistory.PROTOCOL);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) page.batches().get(0).get("changes");
        assertThat(changes.get(0).get("type")).isEqualTo("PROCESS_STEP_UPSERT");
        assertThat(changes.get(1).get("type")).isEqualTo("PROCESS_STEP_DETAIL_SET");

        var detail = history.detail("10", ProcessHistory.PROTOCOL, stepId, "1");
        assertThat(detail.body()).isEqualTo("done detail");
        assertThat(detail.byteCount()).isEqualTo(step.detailRef().byteLength());
        assertThat(detail.sha256()).isEqualTo(step.detailRef().sha256());
    }

    @Test void detailRejectsForeignOwner() {
        String stepId = UUID.randomUUID().toString();
        tx.execute(status -> {
            writer.upsertStage(new ProcessStepWriter.Fact(
                    "100", "10", "20", stepId, "执行完成", null, "DONE", false, null, "secret", now));
            return null;
        });
        actor("200");
        assertThatThrownBy(() -> history.detail("10", ProcessHistory.PROTOCOL, stepId, "1"))
                .isInstanceOfSatisfying(ProjectionReadFailure.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.getMessage()).isEqualTo("NOT_FOUND");
                });
        assertThatThrownBy(() -> history.snapshot("10", ProcessHistory.PROTOCOL, 50))
                .isInstanceOfSatisfying(ProjectionReadFailure.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    /** Re-encode a snapshot cursor with an earlier watermark for after() catch-up tests. */
    private String cursorsForWatermark(ProcessHistory.Snapshot snap, String watermark) {
        var cursors = new HistoryCursor(key);
        return cursors.encode(new HistoryCursor.Position(
                "process-stream", "100", snap.sessionId(), snap.streamId(), watermark, "0", "0"));
    }
}
