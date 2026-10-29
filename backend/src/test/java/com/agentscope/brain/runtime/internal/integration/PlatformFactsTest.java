package com.agentscope.brain.runtime.internal.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformFactsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-29T06:03:05Z"), ZoneId.of("UTC"));

    @Test void reportsTheConfiguredZoneTime() throws Exception {
        var facts = new PlatformFacts(mock(JdbcTemplate.class), FIXED, "Asia/Shanghai");

        JsonNode payload = JSON.readTree(facts.currentTime());

        assertThat(payload.path("iso").asText()).isEqualTo("2026-10-29T14:03:05+08:00");
        assertThat(payload.path("local").asText()).isEqualTo("2026-10-29 14:03:05");
        assertThat(payload.path("weekday").asText()).isEqualTo("星期四");
        assertThat(payload.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(payload.path("epochMillis").asLong()).isEqualTo(Instant.parse("2026-10-29T06:03:05Z").toEpochMilli());
    }

    @Test void reportsAnotherZoneWhenConfigured() throws Exception {
        var facts = new PlatformFacts(mock(JdbcTemplate.class), FIXED, "UTC");

        JsonNode payload = JSON.readTree(facts.currentTime());

        assertThat(payload.path("iso").asText()).isEqualTo("2026-10-29T06:03:05Z");
        assertThat(payload.path("local").asText()).isEqualTo("2026-10-29 06:03:05");
        assertThat(payload.path("weekday").asText()).isEqualTo("星期四");
    }

    @Test void reportsTheCallersOwnIdentityWithAMaskedPhone() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("platform_user"), eq("u-1"))).thenReturn(List.of(Map.of("display_name", "张三")));
        when(jdbc.queryForList(contains("phone_reservation"), eq("u-1")))
                .thenReturn(List.of(Map.of("phone_normalized", "13800008000")));
        var facts = new PlatformFacts(jdbc, FIXED, "Asia/Shanghai");

        JsonNode payload = JSON.readTree(facts.currentUser("u-1"));

        assertThat(payload.path("userId").asText()).isEqualTo("u-1");
        assertThat(payload.path("displayName").asText()).isEqualTo("张三");
        assertThat(payload.path("maskedPhone").asText()).isEqualTo("***8000");
        assertThat(facts.currentUser("u-1")).doesNotContain("13800008000");
    }

    @Test void omitsThePhoneWhenTheCallerHasNoReservation() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("u-2"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("platform_user"), eq("u-2"))).thenReturn(List.of(Map.of("display_name", "李四")));
        var facts = new PlatformFacts(jdbc, FIXED, "Asia/Shanghai");

        JsonNode payload = JSON.readTree(facts.currentUser("u-2"));

        assertThat(payload.path("displayName").asText()).isEqualTo("李四");
        assertThat(payload.has("maskedPhone")).isFalse();
    }

    @Test void failsWhenTheCallerIsNotOnThePlatform() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("missing"))).thenReturn(List.of());
        var facts = new PlatformFacts(jdbc, FIXED, "Asia/Shanghai");

        assertThatThrownBy(() -> facts.currentUser("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLATFORM_USER_UNAVAILABLE");
    }
}
