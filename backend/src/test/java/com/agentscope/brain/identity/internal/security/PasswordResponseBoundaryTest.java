package com.agentscope.brain.identity.internal.security;

import com.agentscope.brain.identity.internal.readiness.StartupReadiness;
import com.agentscope.brain.identity.internal.web.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResponseBoundaryTest {
    @Test void finalSessionSaveFailureReplacesBufferedSuccessAndExpiresCookie() throws Exception {
        var readiness = mock(StartupReadiness.class);
        when(readiness.ready()).thenReturn(true);
        var filter = new AuthenticationConfiguration().authInfrastructureBoundary(readiness, "ASSESSION", false).getFilter();
        var request = new MockHttpServletRequest("POST", "/api/auth/password/initial");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            req.setAttribute(AuthController.PASSWORD_SQL_COMMITTED, Boolean.TRUE);
            ((HttpServletResponse) res).setStatus(200);
            res.getWriter().write("{\"authStage\":\"READY\"}");
            res.flushBuffer();
            assertThat(response.isCommitted()).isFalse();
            assertThat(response.getContentAsString()).isEmpty();
            throw new DataAccessResourceFailureException("synthetic final session save failure");
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("PASSWORD_CHANGED_RELOGIN_REQUIRED").doesNotContain("READY");
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains("ASSESSION=", "Max-Age=0", "HttpOnly", "SameSite=Lax"));
    }

    @Test void uncertainOutcomeSurvivesSessionCleanupFailure() throws Exception {
        var readiness = mock(StartupReadiness.class);
        when(readiness.ready()).thenReturn(true);
        var filter = new AuthenticationConfiguration().authInfrastructureBoundary(readiness, "__Host-ASSESSION", true).getFilter();
        var request = new MockHttpServletRequest("POST", "/api/auth/password/initial");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            req.setAttribute(AuthController.PASSWORD_FAILURE, "AUTH_OUTCOME_UNCERTAIN");
            throw new DataAccessResourceFailureException("synthetic cleanup failure");
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("AUTH_OUTCOME_UNCERTAIN");
        assertThat(response.getHeader("Set-Cookie")).contains("__Host-ASSESSION=", "Max-Age=0", "Secure");
    }
}
