package com.agentscope.brain.release.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import com.agentscope.brain.release.api.EmployeeAccess;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentEmployeeAccessTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:access-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    private final CurrentUser users = mock(CurrentUser.class);
    private final CurrentEmployeeAccess access = new CurrentEmployeeAccess(jdbc, users);
    CurrentEmployeeAccessTest() {
        jdbc.execute("CREATE TABLE digital_employee(id BIGINT PRIMARY KEY,display_name VARCHAR(100),current_release_id BIGINT)");
        jdbc.execute("CREATE TABLE employee_release(id BIGINT PRIMARY KEY,employee_id BIGINT,availability_revision BIGINT,availability_status VARCHAR(20),config_json VARCHAR(2000))");
        jdbc.execute("CREATE TABLE employee_use_grant(id BIGINT,user_id BIGINT,employee_id BIGINT,grant_status VARCHAR(20))");
        jdbc.execute("CREATE TABLE business_session(user_id BIGINT,employee_id BIGINT)");
        jdbc.update("INSERT INTO digital_employee VALUES(1,'员工',1)");
        jdbc.update("INSERT INTO employee_release VALUES(1,1,1,'AVAILABLE',?)", "{\"model\":{},\"images\":{\"mimeTypes\":[\"image/png\"],\"maxCount\":2,\"maxBytesPerImage\":100,\"maxPixelsPerImage\":1000}}");
        jdbc.update("INSERT INTO employee_use_grant VALUES(1,1,1,'ACTIVE')");
        when(users.require()).thenReturn(new CurrentUser.User("1", "用户", "***0029", false, Instant.EPOCH, Instant.EPOCH.plusSeconds(86400)));
    }
    @Test void cachedCapabilityCannotAuthorizeAfterRevocation() {
        assertThat(access.capability("1", "1").allowedActions()).contains("START_RUN");
        jdbc.update("UPDATE employee_use_grant SET grant_status='REVOKED'");
        assertThat(access.employees("1").get(0).readOnly()).isTrue();
        assertThat(access.capability("1", "1").unavailableReason()).isEqualTo("EMPLOYEE_USE_REVOKED");
        assertThatThrownBy(() -> access.requireExecution("1")).isInstanceOfSatisfying(EmployeeAccess.Denied.class,
                e -> { assertThat(e.status()).isEqualTo(403); assertThat(e.getMessage()).isEqualTo("CONTINUATION_FORBIDDEN"); });
        verify(users).require();
    }
    @Test void fixedConfigurationExposesOnlyPublicImageLimits() {
        var cap = access.capability("1", "1");
        assertThat(cap.acceptsText()).isTrue(); assertThat(cap.acceptsImages()).isTrue();
        assertThat(cap.imageLimits()).containsEntry("maxImageCount", 2).doesNotContainKey("model");
        access.requireExecution("1");
    }
    @Test void historicalOwnershipAllowsVisibilityButNeverCreatesGrant() {
        jdbc.update("DELETE FROM employee_use_grant"); jdbc.update("INSERT INTO business_session VALUES(1,1)");
        assertThat(access.employees("1")).hasSize(1);
        assertThatThrownBy(() -> access.requireExecution("1")).isInstanceOf(EmployeeAccess.Denied.class);
        assertThatThrownBy(() -> access.capability("2", "1")).isInstanceOfSatisfying(EmployeeAccess.Denied.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee_use_grant", Integer.class)).isZero();
    }
    @Test void releaseDisableAndCurrentIdentityAreRecheckedAtExecutionBoundary() {
        jdbc.update("UPDATE employee_release SET availability_status='DISABLED'");
        assertThatThrownBy(() -> access.requireExecution("1")).isInstanceOf(EmployeeAccess.Denied.class);
        when(users.require()).thenThrow(new IllegalStateException("current identity rejected"));
        assertThatThrownBy(() -> access.requireExecution("1")).hasMessage("current identity rejected");
    }
}
