package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.projection.api.AcceptedTextProjection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class OrdinaryRunFinalizeTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OrdinaryRunFinalize finalize;
    private final List<AcceptedTextProjection.Fact> projected = new ArrayList<>();
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 27, 9, 0, 0);
    private byte[] claimToken;

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:fin-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        claimToken = uuid();
        projected.clear();
        AcceptedTextProjection projection = fact -> projected.add(fact);
        finalize = new OrdinaryRunFinalize(jdbc, projection);
        jdbc.execute("""
            CREATE TABLE runtime_capacity(id BIGINT PRIMARY KEY,singleton_key INT,foreground_limit INT,normal_waiting_limit INT,
              occupied_count INT,normal_waiting_count INT,boot_epoch BIGINT,row_version BIGINT,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE runtime_dispatch(id BIGINT PRIMARY KEY,status VARCHAR(16),permit_state VARCHAR(16),claim_token BINARY(16),
              claim_fence BIGINT,finished_at TIMESTAMP,row_version BIGINT,gmt_modified TIMESTAMP,run_id BIGINT,session_id BIGINT)
            """);
        jdbc.execute("""
            CREATE TABLE business_run(id BIGINT PRIMARY KEY,session_id BIGINT,status VARCHAR(24),row_version BIGINT,
              ended_at TIMESTAMP,gmt_modified TIMESTAMP,actor_user_id BIGINT,employee_release_id BIGINT)
            """);
        jdbc.execute("""
            CREATE TABLE runtime_sdk_call(id BIGINT PRIMARY KEY,status VARCHAR(32),save_status VARCHAR(16),readback_status VARCHAR(16),
              saved_state_sha256 BINARY(32),readback_state_sha256 BINARY(32),verified_at TIMESTAMP,ended_at TIMESTAMP,
              outcome_kind VARCHAR(32),row_version BIGINT,gmt_modified TIMESTAMP,claim_fence BIGINT,dispatch_id BIGINT)
            """);
        jdbc.execute("""
            CREATE TABLE business_session(id BIGINT PRIMARY KEY,user_id BIGINT,employee_id BIGINT,employee_release_id BIGINT,
              title VARCHAR(255),row_version BIGINT,last_message_no BIGINT,last_projection_seq BIGINT,projection_stream_id BINARY(16),
              last_activity_at TIMESTAMP,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE business_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,session_id BIGINT,user_id BIGINT,employee_id BIGINT,
              message_no BIGINT,input_sequence BIGINT,sender_kind VARCHAR(16),submission_key BINARY(16),submission_sha256 BINARY(32),
              initial_run_id BIGINT,produced_by_run_id BIGINT,accepted_at TIMESTAMP,gmt_create TIMESTAMP,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE message_content_block(id BIGINT AUTO_INCREMENT PRIMARY KEY,message_id BIGINT,block_no INT,block_kind VARCHAR(16),
              text_content CLOB,gmt_create TIMESTAMP,gmt_modified TIMESTAMP)
            """);
        jdbc.update("INSERT INTO runtime_capacity VALUES(1,1,2,16,1,0,7,3,?)", now);
        jdbc.update("INSERT INTO business_session VALUES(10,100,5,50,'hello',4,1,1,?,?,?)", uuid(), now, now);
        jdbc.update("INSERT INTO business_run VALUES(20,10,'RUNNING',2,NULL,?,100,50)", now);
        jdbc.update("INSERT INTO runtime_dispatch VALUES(30,'CLAIMED','HELD',?,1,NULL,2,?,20,10)", claimToken, now);
        jdbc.update("""
            INSERT INTO runtime_sdk_call VALUES(60,'STARTED','NONE','NONE',NULL,NULL,NULL,NULL,NULL,1,?,1,30)
            """, now);
    }

    @Test void writesAssistantAndReleasesPermitOnce() {
        var lease = new OrdinaryRunClaim.Lease("30", "20", "10", "100", "5", "50", "slot", "60",
                uuidString(claimToken), "1", "7");
        byte[] digest = new byte[32];
        digest[0] = 9;
        tx.execute(status -> {
            finalize.succeed(lease, "assistant reply", digest, now);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT sender_kind FROM business_message WHERE produced_by_run_id=20", String.class))
                .isEqualTo("ASSISTANT");
        assertThat(jdbc.queryForObject("SELECT text_content FROM message_content_block WHERE message_id=(SELECT id FROM business_message WHERE produced_by_run_id=20)", String.class))
                .isEqualTo("assistant reply");
        assertThat(jdbc.queryForObject("SELECT status FROM business_run WHERE id=20", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM runtime_dispatch WHERE id=30", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT permit_state FROM runtime_dispatch WHERE id=30", String.class)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT occupied_count FROM runtime_capacity", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM runtime_sdk_call WHERE id=60", String.class)).isEqualTo("VERIFIED");
        assertThat(projected).hasSize(1);
        assertThat(projected.get(0).text()).isEqualTo("assistant reply");

        AtomicReference<RuntimeException> second = new AtomicReference<>();
        try {
            tx.execute(status -> {
                finalize.succeed(lease, "duplicate", digest, now);
                return null;
            });
        } catch (RuntimeException ex) { second.set(ex); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_message WHERE produced_by_run_id=20", Integer.class)).isEqualTo(1);
        assertThat(projected).hasSize(1);
    }

    private static byte[] uuid() {
        var id = UUID.randomUUID();
        var buf = java.nio.ByteBuffer.allocate(16);
        return buf.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static String uuidString(byte[] value) {
        var b = java.nio.ByteBuffer.wrap(value);
        return new UUID(b.getLong(), b.getLong()).toString();
    }
}
