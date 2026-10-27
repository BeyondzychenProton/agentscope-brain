package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.runtime.api.OrdinaryDispatch;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class OrdinaryQueueAdmissionTest {
    @Test void globalCapacityHoldsAcrossActorsWithoutIdentityLockSerialization() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:c0-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE runtime_capacity(id BIGINT PRIMARY KEY,singleton_key INT,foreground_limit INT,normal_waiting_limit INT,occupied_count INT,normal_waiting_count INT,boot_epoch BIGINT,row_version BIGINT,gmt_modified TIMESTAMP)");
        jdbc.execute("INSERT INTO runtime_capacity VALUES(1,1,2,16,0,0,1,1,CURRENT_TIMESTAMP)");
        jdbc.execute("""
                CREATE TABLE runtime_dispatch(id BIGINT AUTO_INCREMENT PRIMARY KEY,dispatch_key BINARY(16),user_id BIGINT,
                    employee_id BIGINT,session_id BIGINT,run_id BIGINT,employee_release_id BIGINT,dispatch_kind VARCHAR(32),
                    work_kind VARCHAR(32),source_kind VARCHAR(32),source_id BIGINT,source_version BIGINT,request_key BINARY(16),
                    request_sha256 BINARY(32),status VARCHAR(16),permit_state VARCHAR(16),counts_toward_waiting INT,
                    boot_epoch BIGINT,gmt_create TIMESTAMP,gmt_modified TIMESTAMP)
                """);
        var admission=new OrdinaryQueueAdmission(jdbc);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var now=LocalDateTime.of(2026,10,27,0,0);
        var executor=Executors.newFixedThreadPool(8);
        try {
            // These participants deliberately have no platform-user row or L1 lock in common.
            for(int actor=1;actor<=2;actor++) {
                final int id=actor;
                tx.execute(status -> { admission.enqueue(admission.lockCapacity(),work(id,now));return null; });
            }
            var results=new ArrayList<Future<Boolean>>();
            for(int i=3;i<=42;i++) {
                final int id=i;
                results.add(executor.submit(() -> {
                    try {
                        tx.execute(status -> { admission.enqueue(admission.lockCapacity(),work(id,now));return null; });
                        return true;
                    } catch(OrdinaryDispatch.Denied denied) {
                        assertThat(denied.getMessage()).isEqualTo("EXECUTION_CAPACITY_EXCEEDED");return false;
                    }
                }));
            }
            int accepted=2;
            for(var result:results) if(result.get()) accepted++;
            assertThat(accepted).isEqualTo(16);
            assertThat(jdbc.queryForObject("SELECT normal_waiting_count FROM runtime_capacity",Integer.class)).isEqualTo(16);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runtime_dispatch",Integer.class)).isEqualTo(16);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM runtime_dispatch",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT occupied_count FROM runtime_capacity",Integer.class)).isZero();
        } finally { executor.shutdownNow(); }
    }
    private static OrdinaryDispatch.Work work(int id,LocalDateTime now) {
        return new OrdinaryDispatch.Work(String.valueOf(id%2+1),"1","1",String.valueOf(id),String.valueOf(id),String.valueOf(id),new byte[16],new byte[32],now);
    }
}