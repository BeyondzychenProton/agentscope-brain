package com.agentscope.brain.projection.internal.application;

import com.agentscope.brain.projection.api.StreamingReplyProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists OPEN preview text as {@code CONTENT_SET}/{@code TEXT_APPEND} under the Session lock.
 * Model I/O must happen outside these short transactions.
 */
@Service
public class StreamingReplyWriter implements StreamingReplyProjection {
    public static final String PROTOCOL = TextHistory.PROTOCOL;
    private static final DateTimeFormatter OCCURRED =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    private static final int MAX_BODY = 262144;
    private static final short POSITION = 1;

    private final JdbcTemplate jdbc;
    private final HistoryCursor cursors;
    private final ObjectMapper json = new ObjectMapper();

    public StreamingReplyWriter(JdbcTemplate jdbc, HistoryCursor cursors) {
        this.jdbc = jdbc;
        this.cursors = cursors;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Handle publishCumulative(Context context, String cumulativeText, LocalDateTime now) {
        try {
            if (cumulativeText == null) {
                throw new IllegalArgumentException("ASSISTANT_TEXT_INVALID");
            }
            byte[] body = cumulativeText.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_BODY) {
                throw new IllegalArgumentException("ASSISTANT_TEXT_INVALID");
            }
            var session = lockSession(context.sessionId(), context.userId());
            String streamId = streamIdOf(session);
            if (streamId == null) {
                throw new IllegalStateException("PROJECTION_STREAM_UNAVAILABLE");
            }
            byte[] callKey = sdkCallKey(context.sdkCallId());
            var open = jdbc.queryForList("""
                    SELECT * FROM projection_content
                    WHERE session_id=? AND owner_user_id=? AND run_id=? AND content_kind='TEXT' AND status='OPEN'
                    FOR UPDATE
                    """, context.sessionId(), context.userId(), context.runId());
            if (open.isEmpty()) {
                return openNew(context, session, streamId, callKey, cumulativeText, body, now);
            }
            if (open.size() != 1) {
                throw new IllegalStateException("CONTENT_OPEN_AMBIGUOUS");
            }
            return appendExisting(context, session, streamId, callKey, open.get(0), cumulativeText, body, now);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new IllegalStateException("STREAMING_REPLY_WRITE_UNAVAILABLE", invalid);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void close(
            Context context, String contentId, String closeReason, String replacementMessageId, LocalDateTime now) {
        try {
            var session = lockSession(context.sessionId(), context.userId());
            String streamId = streamIdOf(session);
            if (streamId == null) {
                throw new IllegalStateException("PROJECTION_STREAM_UNAVAILABLE");
            }
            byte[] id = uuid(contentId);
            var rows = jdbc.queryForList(
                    "SELECT * FROM projection_content WHERE content_id=? AND session_id=? AND owner_user_id=? FOR UPDATE",
                    id, context.sessionId(), context.userId());
            if (rows.size() != 1) {
                throw new IllegalStateException("CONTENT_NOT_FOUND");
            }
            var head = rows.get(0);
            if (!"OPEN".equals(String.valueOf(head.get("status")))) {
                throw new IllegalStateException("CONTENT_NOT_OPEN");
            }
            boolean promoted = "PROMOTED".equals(closeReason);
            if (promoted && (replacementMessageId == null || replacementMessageId.isBlank())) {
                throw new IllegalArgumentException("REPLACEMENT_REQUIRED");
            }
            if (!promoted && replacementMessageId != null) {
                throw new IllegalArgumentException("REPLACEMENT_FORBIDDEN");
            }
            String status = promoted ? "PROMOTED" : "CLOSED";
            Long replacement = promoted ? Long.valueOf(replacementMessageId) : null;
            long baseVersion = ((Number) head.get("current_version")).longValue();
            long nextVersion = baseVersion + 1;
            String previousSeq = value(session, "last_projection_seq");
            String nextSeq = plus(previousSeq);
            byte[] callKey = sdkCallKey(context.sdkCallId());

            if (jdbc.update("""
                    UPDATE projection_content SET status=?,close_reason=?,replacement_message_id=?,
                      current_version=?,gmt_modified=?
                    WHERE content_id=? AND status='OPEN'
                    """, status, closeReason, replacement, nextVersion, now, id) != 1) {
                throw new IllegalStateException("CONTENT_CLOSE_LOST");
            }

            var latestBody = jdbc.queryForList("""
                    SELECT body,byte_count,body_sha256,body_format FROM projection_content_version
                    WHERE content_id=? AND content_version=?
                    """, id, baseVersion);
            if (latestBody.size() != 1) {
                throw new IllegalStateException("CONTENT_VERSION_MISSING");
            }
            var bodyRow = latestBody.get(0);
            jdbc.update("""
                    INSERT INTO projection_content_version(
                      content_id,content_version,base_version,change_kind,content_status,close_reason,
                      replacement_message_id,body_format,payload_storage,body,byte_count,body_sha256,
                      source_kind,source_id,projection_seq,projection_position,gmt_create,gmt_modified)
                    VALUES(?,?,?,'CONTENT_CLOSE',?,?,?,'markdown-v1','INLINE',?,?,?,'RUN_PROGRESS',?,?,?,?,?)
                    """,
                    id, nextVersion, baseVersion, status, closeReason, replacement,
                    bodyRow.get("body"), bodyRow.get("byte_count"), bodyRow.get("body_sha256"),
                    context.sdkCallId(), nextSeq, POSITION, now, now);

            Map<String, Object> change = new LinkedHashMap<>();
            change.put("type", "CONTENT_CLOSE");
            change.put("contentId", contentId);
            change.put("baseContentVersion", Long.toString(baseVersion));
            change.put("contentVersion", Long.toString(nextVersion));
            change.put("status", status);
            change.put("closeReason", closeReason);
            if (replacement != null) {
                change.put("replacementMessageId", replacementMessageId);
            }

            writeBatch(context, session, streamId, previousSeq, nextSeq, List.of(change), now);
            writeProgress(context, callKey, id, nextVersion, status, closeReason, replacement,
                    firstSeq(head), firstPosition(head), streamId, nextSeq, displayOf(bodyRow), now);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new IllegalStateException("STREAMING_REPLY_WRITE_UNAVAILABLE", invalid);
        }
    }

    private Handle openNew(
            Context context,
            Map<String, Object> session,
            String streamId,
            byte[] callKey,
            String text,
            byte[] body,
            LocalDateTime now) throws Exception {
        String contentId = UUID.randomUUID().toString();
        byte[] id = uuid(contentId);
        String previousSeq = value(session, "last_projection_seq");
        String nextSeq = plus(previousSeq);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);

        jdbc.update("""
                INSERT INTO projection_content(
                  content_id,owner_user_id,session_id,run_id,content_kind,stream_id,
                  first_seq,first_position,current_version,status,close_reason,gmt_create,gmt_modified)
                VALUES(?,?,?,?,'TEXT',?,?,?,?, 'OPEN',NULL,?,?)
                """,
                id, context.userId(), context.sessionId(), context.runId(), uuid(streamId),
                nextSeq, POSITION, 1L, now, now);
        jdbc.update("""
                INSERT INTO projection_content_version(
                  content_id,content_version,base_version,change_kind,content_status,close_reason,
                  body_format,payload_storage,body,byte_count,body_sha256,source_kind,source_id,
                  projection_seq,projection_position,gmt_create,gmt_modified)
                VALUES(?,1,0,'CONTENT_SET','OPEN',NULL,'markdown-v1','INLINE',?,?,?,'RUN_PROGRESS',?,?,?,?,?)
                """,
                id, body, body.length, digest, context.sdkCallId(), nextSeq, POSITION, now, now);

        Map<String, Object> change = contentSetChange(contentId, context.runId(), nextSeq, text, body, digest);
        writeBatch(context, session, streamId, previousSeq, nextSeq, List.of(change), now);
        writeProgress(context, callKey, id, 1L, "OPEN", null, null, nextSeq, POSITION, streamId, nextSeq, text, now);
        return new Handle(contentId, "1");
    }

    private Handle appendExisting(
            Context context,
            Map<String, Object> session,
            String streamId,
            byte[] callKey,
            Map<String, Object> head,
            String cumulative,
            byte[] body,
            LocalDateTime now) throws Exception {
        if (!"OPEN".equals(String.valueOf(head.get("status")))) {
            throw new IllegalStateException("CONTENT_NOT_OPEN");
        }
        byte[] id = (byte[]) head.get("content_id");
        long baseVersion = ((Number) head.get("current_version")).longValue();
        var prior = jdbc.queryForList("""
                SELECT body FROM projection_content_version WHERE content_id=? AND content_version=?
                """, id, baseVersion);
        if (prior.size() != 1) {
            throw new IllegalStateException("CONTENT_VERSION_MISSING");
        }
        String previousText = new String((byte[]) prior.get(0).get("body"), StandardCharsets.UTF_8);
        if (cumulative.equals(previousText)) {
            return new Handle(uuid(id), Long.toString(baseVersion));
        }
        if (!cumulative.startsWith(previousText)) {
            throw new IllegalStateException("TEXT_APPEND_BASE_MISMATCH");
        }
        String delta = cumulative.substring(previousText.length());
        long nextVersion = baseVersion + 1;
        String previousSeq = value(session, "last_projection_seq");
        String nextSeq = plus(previousSeq);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);

        jdbc.update("UPDATE projection_content SET current_version=?,gmt_modified=? WHERE content_id=? AND status='OPEN'",
                nextVersion, now, id);
        jdbc.update("""
                INSERT INTO projection_content_version(
                  content_id,content_version,base_version,change_kind,content_status,close_reason,
                  body_format,payload_storage,body,byte_count,body_sha256,source_kind,source_id,
                  projection_seq,projection_position,gmt_create,gmt_modified)
                VALUES(?,?,?,'TEXT_APPEND','OPEN',NULL,'markdown-v1','INLINE',?,?,?,'RUN_PROGRESS',?,?,?,?,?)
                """,
                id, nextVersion, baseVersion, body, body.length, digest, context.sdkCallId(), nextSeq, POSITION, now, now);

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("type", "TEXT_APPEND");
        change.put("contentId", uuid(id));
        change.put("baseContentVersion", Long.toString(baseVersion));
        change.put("contentVersion", Long.toString(nextVersion));
        change.put("delta", delta);
        change.put("bodyFormat", "markdown-v1");
        change.put("byteCount", Integer.toString(body.length));
        change.put("sha256", HexFormat.of().formatHex(digest));

        writeBatch(context, session, streamId, previousSeq, nextSeq, List.of(change), now);
        writeProgress(context, callKey, id, nextVersion, "OPEN", null, null,
                firstSeq(head), firstPosition(head), streamId, nextSeq, cumulative, now);
        return new Handle(uuid(id), Long.toString(nextVersion));
    }

    private Map<String, Object> contentSetChange(
            String contentId, String runId, String firstSeq, String text, byte[] body, byte[] digest) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("type", "CONTENT_SET");
        change.put("contentId", contentId);
        change.put("contentVersion", "1");
        change.put("kind", "TEXT");
        change.put("bodyFormat", "markdown-v1");
        change.put("byteCount", Integer.toString(body.length));
        change.put("sha256", HexFormat.of().formatHex(digest));
        change.put("runId", runId);
        change.put("baseContentVersion", "0");
        change.put("firstSeq", firstSeq);
        change.put("firstPosition", Integer.toString(POSITION));
        change.put("status", "OPEN");
        change.put("body", text);
        return change;
    }

    private void writeBatch(
            Context context,
            Map<String, Object> session,
            String streamId,
            String previousSeq,
            String nextSeq,
            List<Map<String, Object>> changes,
            LocalDateTime now) throws Exception {
        String cursor = cursors.encode(new HistoryCursor.Position(
                "stream",
                context.userId(),
                context.sessionId(),
                streamId,
                nextSeq,
                value(session, "last_message_no"),
                "0"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("protocolVersion", PROTOCOL);
        envelope.put("sessionId", context.sessionId());
        envelope.put("streamId", streamId);
        envelope.put("seq", nextSeq);
        envelope.put("previousSeq", previousSeq);
        envelope.put("cursor", cursor);
        envelope.put("occurredAt", now.format(OCCURRED));
        envelope.put("changes", changes);
        byte[] payload = json.writeValueAsBytes(envelope);
        if (payload.length > 1048576) {
            throw new IllegalArgumentException("PROJECTION_BATCH_TOO_LARGE");
        }
        jdbc.update("""
                INSERT INTO session_projection_event(
                  session_id,stream_id,seq,previous_seq,protocol_version,batch_payload,
                  byte_count,batch_sha256,change_count,occurred_at,gmt_create,gmt_modified)
                VALUES(?,?,?,?,'web-projection-v1',?,?,?,?,?,?,?)
                """,
                context.sessionId(), uuid(streamId), nextSeq, previousSeq, payload, payload.length,
                MessageDigest.getInstance("SHA-256").digest(payload), changes.size(), now, now, now);
        jdbc.update("""
                UPDATE business_session SET last_projection_seq=?,gmt_modified=?
                WHERE id=? AND user_id=?
                """, nextSeq, now, context.sessionId(), context.userId());
    }

    private void writeProgress(
            Context context,
            byte[] callKey,
            byte[] contentId,
            long contentVersion,
            String contentStatus,
            String closeReason,
            Long replacementMessageId,
            String firstSeq,
            short firstPosition,
            String streamId,
            String projectionSeq,
            String displayText,
            LocalDateTime now) {
        Long eventNo = jdbc.query("""
                SELECT COALESCE(MAX(event_no),0)+1 FROM run_progress WHERE run_id=?
                """, rs -> rs.next() ? rs.getLong(1) : 1L, context.runId());
        jdbc.update("""
                INSERT INTO run_progress(
                  run_id,event_no,event_kind,display_text,occurred_at,gmt_create,gmt_modified,
                  payload_version,structured_payload,content_id,content_version,sdk_call_key,
                  source_kind,source_id,first_projection_seq,first_projection_position,
                  content_status,close_reason,replacement_message_id,
                  projection_stream_id,projection_seq,projection_position)
                VALUES(?,?,'TEXT_DELTA',?,?,?,?,1,NULL,?,?,?,'MODEL_TEXT',?,?,?,?,?,?,?,?,?)
                """,
                context.runId(), eventNo, displayText, now, now, now,
                contentId, contentVersion, callKey, context.sdkCallId(),
                firstSeq, firstPosition, contentStatus, closeReason, replacementMessageId,
                uuid(streamId), projectionSeq, POSITION);
    }

    private Map<String, Object> lockSession(String sessionId, String userId) {
        var rows = jdbc.queryForList(
                "SELECT * FROM business_session WHERE id=? AND user_id=? FOR UPDATE", sessionId, userId);
        if (rows.size() != 1) {
            throw new IllegalStateException("SESSION_LOCK_LOST");
        }
        return rows.get(0);
    }

    private byte[] sdkCallKey(String sdkCallId) {
        var rows = jdbc.queryForList("SELECT sdk_call_key FROM runtime_sdk_call WHERE id=?", sdkCallId);
        if (rows.size() != 1 || !(rows.get(0).get("sdk_call_key") instanceof byte[] key)) {
            throw new IllegalStateException("SDK_CALL_UNAVAILABLE");
        }
        return key;
    }

    private static String streamIdOf(Map<String, Object> session) {
        Object raw = session.get("projection_stream_id");
        if (raw == null) {
            return null;
        }
        if (raw instanceof byte[] bytes) {
            return uuid(bytes);
        }
        return String.valueOf(raw);
    }

    private static String firstSeq(Map<String, Object> head) {
        return String.valueOf(head.get("first_seq"));
    }

    private static short firstPosition(Map<String, Object> head) {
        return ((Number) head.get("first_position")).shortValue();
    }

    private static String displayOf(Map<String, Object> bodyRow) {
        Object body = bodyRow.get("body");
        if (body instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return body == null ? "" : String.valueOf(body);
    }

    private static String value(Map<String, Object> row, String key) {
        Object raw = row.get(key);
        return raw == null ? "0" : String.valueOf(raw);
    }

    private static String plus(String value) {
        return new BigInteger(value).add(BigInteger.ONE).toString();
    }

    private static byte[] uuid(String value) {
        var id = UUID.fromString(value);
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static String uuid(byte[] value) {
        var b = ByteBuffer.wrap(value);
        return new UUID(b.getLong(), b.getLong()).toString();
    }
}
