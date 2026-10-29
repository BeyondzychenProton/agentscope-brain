package com.agentscope.brain.runtime.internal.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Platform facts the meeting assistant may read without a meeting MCP round trip: the platform
 * clock and the authenticated caller's own identity.
 */
@Component
public class PlatformFacts {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<DayOfWeek, String> WEEKDAYS = Map.of(
            DayOfWeek.MONDAY, "星期一",
            DayOfWeek.TUESDAY, "星期二",
            DayOfWeek.WEDNESDAY, "星期三",
            DayOfWeek.THURSDAY, "星期四",
            DayOfWeek.FRIDAY, "星期五",
            DayOfWeek.SATURDAY, "星期六",
            DayOfWeek.SUNDAY, "星期日");
    private static final String PHONE_SQL =
            "SELECT phone_normalized FROM phone_reservation WHERE owner_user_id=? AND reservation_status='CURRENT'";
    private static final String USER_SQL = "SELECT display_name FROM platform_user WHERE id=?";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;
    private final ObjectMapper json = new ObjectMapper();

    public PlatformFacts(JdbcTemplate jdbc, Clock clock,
            @Value("${brain.tools.timezone:Asia/Shanghai}") String timezone) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = ZoneId.of(timezone);
    }

    /** ISO instant with offset, a readable local timestamp, the Chinese weekday and the zone. */
    public String currentTime() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        payload.put("local", now.format(LOCAL));
        payload.put("weekday", WEEKDAYS.get(now.getDayOfWeek()));
        payload.put("timezone", zone.getId());
        payload.put("epochMillis", now.toInstant().toEpochMilli());
        return write(payload);
    }

    /** The caller's own identity; the phone is masked exactly like the session identity. */
    public String currentUser(String userId) {
        List<Map<String, Object>> users = jdbc.queryForList(USER_SQL, userId);
        if (users.size() != 1 || users.get(0).get("display_name") == null) {
            throw new IllegalStateException("PLATFORM_USER_UNAVAILABLE");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("displayName", String.valueOf(users.get(0).get("display_name")));
        String phone = currentPhone(userId);
        if (phone != null) payload.put("maskedPhone", mask(phone));
        return write(payload);
    }

    private String currentPhone(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(PHONE_SQL, userId);
        if (rows.size() != 1 || rows.get(0).get("phone_normalized") == null) return null;
        return String.valueOf(rows.get(0).get("phone_normalized"));
    }

    /** Never expose the full number: keep the same shape the session identity returns. */
    static String mask(String phone) {
        return "***" + phone.substring(Math.max(0, phone.length() - 4));
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("PLATFORM_FACT_UNAVAILABLE", failure);
        }
    }
}
