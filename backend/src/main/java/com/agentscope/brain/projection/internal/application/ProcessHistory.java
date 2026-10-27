package com.agentscope.brain.projection.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only {@code web-projection-process-v1} history. No model, SDK, or tool writes on GET.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProcessHistory {
    public static final String PROTOCOL = ProcessStepWriter.PROTOCOL;
    private static final String CURSOR_PURPOSE = "process-stream";
    private static final int AFTER_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final CurrentUser users;
    private final HistoryCursor cursors;
    private final ObjectMapper json = new ObjectMapper();

    public ProcessHistory(JdbcTemplate jdbc, CurrentUser users, HistoryCursor cursors) {
        this.jdbc = jdbc;
        this.users = users;
        this.cursors = cursors;
    }

    public record OrderAnchor(String seq, String index) { }
    public record DetailRef(String detailVersion, String byteLength, String sha256) { }
    public record Step(
            String stepId,
            String runId,
            String kind,
            String title,
            String summary,
            String status,
            OrderAnchor orderAnchor,
            String viewRevision,
            boolean collapsedByDefault,
            String operationId,
            String detail,
            String detailVersion,
            DetailRef detailRef) { }
    public record Snapshot(
            String protocolVersion,
            String sessionId,
            String streamId,
            String watermark,
            String cursor,
            List<Step> steps) { }
    public record After(
            String protocolVersion,
            String sessionId,
            String streamId,
            String watermark,
            String cursor,
            List<Map<String, Object>> batches) { }
    public record Detail(
            String stepId,
            String detailVersion,
            String body,
            String byteCount,
            String sha256) { }
    private record Owned(String userId, Map<String, Object> session, String stream) { }

    public Snapshot snapshot(String sessionId, String protocolVersion, int processLimit) {
        protocol(protocolVersion);
        limit(processLimit);
        var owned = owned(sessionId);
        String watermark = number(owned.session(), "last_process_projection_seq");
        String streamId = owned.stream() == null ? "" : owned.stream();
        String cursor = cursors.encode(new HistoryCursor.Position(
                CURSOR_PURPOSE, owned.userId(), sessionId, streamId, watermark, "0", "0"));
        if (owned.stream() == null) {
            return new Snapshot(PROTOCOL, sessionId, streamId, watermark, cursor, List.of());
        }
        var rows = jdbc.queryForList("""
                SELECT s.*,d.byte_count AS detail_byte_count,d.body_sha256 AS detail_body_sha256
                FROM projection_process_step s
                LEFT JOIN projection_process_step_detail d
                  ON d.step_id=s.step_id AND d.detail_version=s.current_detail_version
                WHERE s.session_id=? AND s.owner_user_id=? AND s.stream_id=?
                ORDER BY s.first_seq DESC,s.first_position DESC
                LIMIT ?
                """, sessionId, owned.userId(), uuidBytes(owned.stream()), processLimit);
        var steps = new ArrayList<Step>(rows.size());
        for (var row : rows) {
            steps.add(toStep(row));
        }
        Collections.reverse(steps);
        return new Snapshot(PROTOCOL, sessionId, streamId, watermark, cursor, List.copyOf(steps));
    }

    public After after(String sessionId, String protocolVersion, String cursor) {
        protocol(protocolVersion);
        var owned = owned(sessionId);
        var position = cursors.decode(cursor);
        if (!position.userId().equals(owned.userId()) || !position.sessionId().equals(sessionId)) {
            throw new ProjectionReadFailure(404, "NOT_FOUND");
        }
        if (!CURSOR_PURPOSE.equals(position.purpose())) {
            throw new ProjectionReadFailure(409, "CURSOR_INVALID");
        }
        String currentStream = owned.stream() == null ? "" : owned.stream();
        if (!position.streamId().equals(currentStream)) {
            throw new ProjectionReadFailure(409, "STREAM_CHANGED");
        }
        String head = number(owned.session(), "last_process_projection_seq");
        if (new BigInteger(position.watermark()).compareTo(new BigInteger(head)) > 0) {
            throw new ProjectionReadFailure(410, "RESET_REQUIRED");
        }
        if (owned.stream() == null) {
            return new After(PROTOCOL, sessionId, currentStream, head, cursor, List.of());
        }
        var rows = jdbc.queryForList("""
                SELECT seq,batch_payload FROM session_projection_event
                WHERE session_id=? AND stream_id=? AND protocol_version=? AND seq>?
                ORDER BY seq ASC LIMIT ?
                """, sessionId, uuidBytes(owned.stream()), PROTOCOL, position.watermark(), AFTER_LIMIT);
        var batches = new ArrayList<Map<String, Object>>(rows.size());
        String latestCursor = cursor;
        String latestWatermark = position.watermark();
        for (var row : rows) {
            try {
                Map<String, Object> envelope = json.readValue(
                        (byte[]) row.get("batch_payload"), new TypeReference<LinkedHashMap<String, Object>>() { });
                batches.add(envelope);
                latestWatermark = String.valueOf(row.get("seq"));
                Object encoded = envelope.get("cursor");
                if (encoded instanceof String value && !value.isBlank()) {
                    latestCursor = value;
                }
            } catch (Exception corrupt) {
                throw new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE");
            }
        }
        return new After(PROTOCOL, sessionId, currentStream, latestWatermark, latestCursor, List.copyOf(batches));
    }

    public Detail detail(String sessionId, String protocolVersion, String stepId, String detailVersion) {
        protocol(protocolVersion);
        var owned = owned(sessionId);
        byte[] id;
        try {
            id = uuidBytes(UUID.fromString(stepId));
            positive(detailVersion);
        } catch (Exception invalid) {
            throw new ProjectionReadFailure(404, "NOT_FOUND");
        }
        var heads = jdbc.queryForList("""
                SELECT * FROM projection_process_step
                WHERE step_id=? AND session_id=? AND owner_user_id=?
                """, id, sessionId, owned.userId());
        if (heads.size() != 1) {
            throw new ProjectionReadFailure(404, "NOT_FOUND");
        }
        if (owned.stream() == null
                || !Arrays.equals((byte[]) heads.get(0).get("stream_id"), uuidBytes(owned.stream()))) {
            throw new ProjectionReadFailure(409, "STREAM_CHANGED");
        }
        var rows = jdbc.queryForList("""
                SELECT * FROM projection_process_step_detail
                WHERE step_id=? AND detail_version=?
                """, id, detailVersion);
        if (rows.size() != 1) {
            throw new ProjectionReadFailure(410, "CONTENT_VERSION_UNAVAILABLE");
        }
        var row = rows.get(0);
        byte[] body = (byte[]) row.get("body");
        try {
            if (body == null
                    || body.length == 0
                    || body.length > ProcessStepSecurity.MAX_INLINE_UTF8_BYTES
                    || body.length != ((Number) row.get("byte_count")).intValue()
                    || !MessageDigest.isEqual(
                            MessageDigest.getInstance("SHA-256").digest(body),
                            (byte[]) row.get("body_sha256"))) {
                throw new IllegalArgumentException();
            }
            var text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            return new Detail(
                    stepId,
                    detailVersion,
                    text,
                    String.valueOf(body.length),
                    HexFormat.of().formatHex((byte[]) row.get("body_sha256")));
        } catch (Exception corrupt) {
            throw new ProjectionReadFailure(410, "CONTENT_VERSION_UNAVAILABLE");
        }
    }

    private Step toStep(Map<String, Object> row) {
        String detailVersion = row.get("current_detail_version") == null
                ? null : number(row, "current_detail_version");
        DetailRef detailRef = null;
        if (detailVersion != null && row.get("detail_body_sha256") != null) {
            detailRef = new DetailRef(
                    detailVersion,
                    number(row, "detail_byte_count"),
                    HexFormat.of().formatHex((byte[]) row.get("detail_body_sha256")));
        }
        Object operation = row.get("operation_id");
        Object collapsed = row.get("collapsed_by_default");
        boolean folded = collapsed instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(collapsed);
        return new Step(
                uuid((byte[]) row.get("step_id")),
                number(row, "run_id"),
                String.valueOf(row.get("step_kind")),
                String.valueOf(row.get("title")),
                row.get("summary") == null ? null : String.valueOf(row.get("summary")),
                String.valueOf(row.get("step_status")),
                new OrderAnchor(number(row, "first_seq"), number(row, "first_position")),
                number(row, "view_revision"),
                folded,
                operation == null ? null : String.valueOf(operation),
                null,
                detailVersion,
                detailRef);
    }

    private Owned owned(String sessionId) {
        var user = users.require();
        if (user.passwordChangeRequired()) {
            throw new ProjectionReadFailure(403, "PASSWORD_CHANGE_REQUIRED");
        }
        try {
            positive(sessionId);
        } catch (Exception invalid) {
            throw new ProjectionReadFailure(404, "NOT_FOUND");
        }
        var rows = jdbc.queryForList("SELECT * FROM business_session WHERE id=? AND user_id=?", sessionId, user.userId());
        if (rows.size() != 1) {
            throw new ProjectionReadFailure(404, "NOT_FOUND");
        }
        var session = rows.get(0);
        Object raw = session.get("process_projection_stream_id");
        String stream = raw == null ? null : uuid((byte[]) raw);
        return new Owned(user.userId(), session, stream);
    }

    private static String number(Map<String, Object> row, String column) {
        return String.valueOf(row.get(column));
    }

    private static void positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,19}") || new BigInteger(value).bitLength() > 64) {
            throw new IllegalArgumentException();
        }
    }

    private static void protocol(String version) {
        if (!PROTOCOL.equals(version)) {
            throw new ProjectionReadFailure(409, "PROJECTION_VERSION_UNSUPPORTED");
        }
    }

    private static void limit(int value) {
        if (value < 1 || value > 100) {
            throw new ProjectionReadFailure(400, "REQUEST_INVALID");
        }
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static byte[] uuidBytes(String value) {
        return uuidBytes(UUID.fromString(value));
    }

    private static String uuid(byte[] bytes) {
        var b = ByteBuffer.wrap(bytes);
        return new UUID(b.getLong(), b.getLong()).toString();
    }
}
