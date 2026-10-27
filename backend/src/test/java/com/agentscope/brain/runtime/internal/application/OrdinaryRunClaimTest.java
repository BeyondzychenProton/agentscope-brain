package com.agentscope.brain.runtime.internal.application;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class OrdinaryRunClaimTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OrdinaryRunClaim claim;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 27, 8, 0, 0);

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:claim-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        claim = new OrdinaryRunClaim(jdbc);
        jdbc.execute("""
            CREATE TABLE runtime_capacity(id BIGINT PRIMARY KEY,singleton_key INT,foreground_limit INT,normal_waiting_limit INT,
              occupied_count INT,normal_waiting_count INT,boot_epoch BIGINT,row_version BIGINT,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE runtime_dispatch(id BIGINT AUTO_INCREMENT PRIMARY KEY,dispatch_key BINARY(16),user_id BIGINT,employee_id BIGINT,
              session_id BIGINT,run_id BIGINT,employee_release_id BIGINT,dispatch_kind VARCHAR(32),work_kind VARCHAR(32),
              source_kind VARCHAR(32),source_id BIGINT,source_version BIGINT,request_key BINARY(16),request_sha256 BINARY(32),
              status VARCHAR(16),permit_state VARCHAR(16),counts_toward_waiting INT,boot_epoch BIGINT,claim_fence BIGINT,
              claim_token BINARY(16),claimed_at TIMESTAMP,sdk_call_id BIGINT,row_version BIGINT,gmt_create TIMESTAMP,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE business_run(id BIGINT PRIMARY KEY,session_id BIGINT,status VARCHAR(24),row_version BIGINT,
              actor_user_id BIGINT,employee_release_id BIGINT,started_at TIMESTAMP,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE runtime_sdk_call(id BIGINT AUTO_INCREMENT PRIMARY KEY,sdk_call_key BINARY(16),run_id BIGINT,session_id BIGINT,
              dispatch_id BIGINT,call_no INT,user_id BIGINT,employee_id BIGINT,employee_release_id BIGINT,state_session_key VARCHAR(128),
              state_generation BIGINT,sdk_version VARCHAR(32),boot_epoch BIGINT,claim_fence BIGINT,executor_ref VARCHAR(64),
              started_at TIMESTAMP,call_kind VARCHAR(32),input_sha256 BINARY(32),status VARCHAR(32),
              store_version_capability VARCHAR(16),before_exists INT,save_status VARCHAR(16),readback_status VARCHAR(16),
              gmt_create TIMESTAMP,gmt_modified TIMESTAMP)
            """);
        jdbc.execute("""
            CREATE TABLE business_session(id BIGINT PRIMARY KEY,user_id BIGINT,state_session_key VARCHAR(128),employee_id BIGINT,
              employee_release_id BIGINT)
            """);
        jdbc.update("INSERT INTO runtime_capacity VALUES(1,1,2,16,0,1,7,1,?)", now);
        jdbc.update("""
            INSERT INTO business_session VALUES(10,100,'slot-session-10',5,50)
            """);
        jdbc.update("INSERT INTO business_run VALUES(20,10,'ACCEPTED',1,100,50,NULL,?)", now);
        jdbc.update("""
            INSERT INTO runtime_dispatch(id,dispatch_key,user_id,employee_id,session_id,run_id,employee_release_id,dispatch_kind,
              work_kind,source_kind,source_id,source_version,request_key,request_sha256,status,permit_state,counts_toward_waiting,
              boot_epoch,claim_fence,row_version,gmt_create,gmt_modified)
            VALUES(30,?,?,?,?,?,?,'ORDINARY','ORIGINAL_RUN','ORDINARY_SUBMISSION',40,1,?,?, 'WAITING','NONE',1,7,0,1,?,?)
            """, bytes(16), 100L, 5L, 10L, 20L, 50L, bytes(16), bytes(32), now, now);
    }

    @Test void claimsWaitingDispatchHoldsPermitAndStartsSdkCall() {
        var lease = tx.execute(status -> claim.claimNext(now, "worker-a"));
        assertThat(lease).isNotNull();
        assertThat(lease.dispatchId()).isEqualTo("30");
        assertThat(lease.runId()).isEqualTo("20");
        assertThat(lease.sessionId()).isEqualTo("10");
        assertThat(lease.sdkCallId()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT status FROM runtime_dispatch WHERE id=30", String.class)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject("SELECT permit_state FROM runtime_dispatch WHERE id=30", String.class)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT counts_toward_waiting FROM runtime_dispatch WHERE id=30", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT occupied_count FROM runtime_capacity", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT normal_waiting_count FROM runtime_capacity", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM business_run WHERE id=20", String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runtime_sdk_call WHERE dispatch_id=30 AND status='STARTED'", Integer.class)).isEqualTo(1);
    }

    @Test void refusesWhenForegroundPermitFull() {
        jdbc.update("UPDATE runtime_capacity SET occupied_count=2,normal_waiting_count=1,row_version=2");
        OrdinaryRunClaim.Lease full = tx.execute(status -> claim.claimNext(now, "worker-a"));
        assertThat(full).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM runtime_dispatch WHERE id=30", String.class)).isEqualTo("WAITING");
    }

    @Test void refusesStaleBootEpoch() {
        jdbc.update("UPDATE runtime_capacity SET boot_epoch=8,row_version=2");
        OrdinaryRunClaim.Lease stale = tx.execute(status -> claim.claimNext(now, "worker-a"));
        assertThat(stale).isNull();
    }

    private static byte[] bytes(int n) {
        var out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) (i + 1);
        return out;
    }
}
