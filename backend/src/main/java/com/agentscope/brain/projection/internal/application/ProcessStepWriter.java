package com.agentscope.brain.projection.internal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * Writes process-timeline steps under the session lock into the isolated process projection stream
 * ({@code web-projection-process-v1}). Callers must already be inside a MANDATORY transaction.
 */
@Service
public class ProcessStepWriter {
    public static final String PROTOCOL = "web-projection-process-v1";
    private static final DateTimeFormatter OCCURRED =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final JdbcTemplate jdbc;
    private final HistoryCursor cursors;
    private final ObjectMapper json = new ObjectMapper();

    public ProcessStepWriter(JdbcTemplate jdbc, HistoryCursor cursors) {
        this.jdbc = jdbc;
        this.cursors = cursors;
    }

    public record Fact(
            String userId,
            String sessionId,
            String runId,
            String stepId,
            String title,
            String summary,
            String status,
            boolean collapsedByDefault,
            Long operationId,
            String detail,
            LocalDateTime now) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public void upsertStage(Fact f) {
        upsert(f, "STAGE");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void upsertToolStep(Fact f) {
        upsert(f, "TOOL_STEP");
    }

    private void upsert(Fact f, String kind) {
        try {
            if (f.stepId() == null || f.stepId().isBlank()) {
                throw new IllegalArgumentException("STEP_ID_REQUIRED");
            }
            var session = lockSession(f.sessionId(), f.userId());
            String streamId = streamIdOf(session);
            boolean mintedStream = streamId == null;
            if (mintedStream) {
                streamId = UUID.randomUUID().toString();
            }
            String previousSeq = value(session, "last_process_projection_seq");
            String nextSeq = plus(previousSeq);

            byte[] detailBytes = f.detail() == null ? null : f.detail().getBytes(StandardCharsets.UTF_8);
            if (detailBytes != null) {
                ProcessStepSecurity.validateInlineDetail(detailBytes);
                if (detailBytes.length == 0) {
                    throw new ProcessStepSecurity.Rejected("INLINE_DETAIL_EMPTY");
                }
            }

            Map<String, Object> publicFields = new LinkedHashMap<>();
            publicFields.put("stepId", f.stepId());
            publicFields.put("runId", f.runId());
            publicFields.put("kind", kind);
            publicFields.put("title", f.title());
            publicFields.put("summary", f.summary());
            publicFields.put("status", f.status());
            publicFields.put("collapsedByDefault", f.collapsedByDefault());
            if (f.operationId() != null) {
                publicFields.put("operationId", f.operationId().toString());
            }
            ProcessStepSecurity.rejectSensitiveKeys(publicFields);

            var existing = jdbc.queryForList(
                    "SELECT * FROM projection_process_step WHERE step_id=? FOR UPDATE", uuid(f.stepId()));
            String firstSeq;
            String firstPosition;
            String viewRevision;
            Long detailVersion = null;

            if (existing.isEmpty()) {
                firstSeq = nextSeq;
                firstPosition = "1";
                viewRevision = "1";
                if (detailBytes != null) {
                    detailVersion = 1L;
                }
                jdbc.update("""
                        INSERT INTO projection_process_step(
                          step_id,owner_user_id,session_id,run_id,stream_id,step_kind,step_status,
                          title,summary,first_seq,first_position,view_revision,collapsed_by_default,
                          operation_id,current_detail_version,gmt_create,gmt_modified)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                        uuid(f.stepId()), f.userId(), f.sessionId(), f.runId(), uuid(streamId), kind, f.status(),
                        f.title(), f.summary(), firstSeq, firstPosition, viewRevision,
                        f.collapsedByDefault() ? 1 : 0, f.operationId(), detailVersion, f.now(), f.now());
            } else {
                var row = existing.get(0);
                firstSeq = value(row, "first_seq");
                firstPosition = value(row, "first_position");
                viewRevision = plus(value(row, "view_revision"));
                Object currentDetail = row.get("current_detail_version");
                if (detailBytes != null) {
                    long base = currentDetail == null ? 0L : Long.parseLong(String.valueOf(currentDetail));
                    detailVersion = base + 1L;
                } else if (currentDetail != null) {
                    detailVersion = Long.parseLong(String.valueOf(currentDetail));
                }
                jdbc.update("""
                        UPDATE projection_process_step SET step_status=?,title=?,summary=?,view_revision=?,
                          collapsed_by_default=?,operation_id=?,current_detail_version=?,gmt_modified=?
                        WHERE step_id=?
                        """,
                        f.status(), f.title(), f.summary(), viewRevision,
                        f.collapsedByDefault() ? 1 : 0, f.operationId(), detailVersion, f.now(), uuid(f.stepId()));
            }

            List<Map<String, Object>> changes = new ArrayList<>();
            changes.add(buildUpsertChange(f, kind, firstSeq, firstPosition, viewRevision));

            if (detailBytes != null) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(detailBytes);
                jdbc.update("""
                        INSERT INTO projection_process_step_detail(
                          step_id,detail_version,body,byte_count,body_sha256,payload_storage,projection_seq,
                          gmt_create,gmt_modified)
                        VALUES(?,?,?,?,?,'INLINE',?,?,?)
                        """,
                        uuid(f.stepId()), detailVersion, detailBytes, detailBytes.length, digest, nextSeq,
                        f.now(), f.now());
                changes.add(buildDetailSetChange(f.stepId(), f.detail(), detailVersion, detailBytes, digest));
            }

            String cursor = cursors.encode(new HistoryCursor.Position(
                    "process-stream", f.userId(), f.sessionId(), streamId, nextSeq, "0", "0"));
            Map<String, Object> envelope = buildEnvelope(
                    f.sessionId(), streamId, nextSeq, previousSeq, cursor, f.now(), changes);
            byte[] payload = json.writeValueAsBytes(envelope);
            if (payload.length > 1048576 || changes.size() > 128) {
                throw new IllegalArgumentException("PROCESS_BATCH_TOO_LARGE");
            }
            jdbc.update("""
                    INSERT INTO session_projection_event(session_id,stream_id,seq,previous_seq,protocol_version,
                      batch_payload,byte_count,batch_sha256,change_count,occurred_at,gmt_create,gmt_modified)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    f.sessionId(), uuid(streamId), nextSeq, previousSeq, PROTOCOL, payload, payload.length,
                    MessageDigest.getInstance("SHA-256").digest(payload), changes.size(), f.now(), f.now(), f.now());

            jdbc.update("""
                    UPDATE business_session SET process_projection_stream_id=?,last_process_projection_seq=?,gmt_modified=?
                    WHERE id=? AND user_id=?
                    """,
                    uuid(streamId), nextSeq, f.now(), f.sessionId(), f.userId());
        } catch (ProcessStepSecurity.Rejected | IllegalArgumentException | IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new IllegalStateException("PROCESS_PROJECTION_WRITE_UNAVAILABLE", invalid);
        }
    }

    /** Package-visible for focused JSON shape tests. */
    static Map<String, Object> buildUpsertChange(
            Fact f, String kind, String firstSeq, String firstPosition, String viewRevision) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("type", "PROCESS_STEP_UPSERT");
        change.put("stepId", f.stepId());
        change.put("runId", f.runId());
        change.put("kind", kind);
        change.put("title", f.title());
        change.put("summary", f.summary());
        change.put("status", f.status());
        change.put("orderAnchor", Map.of("seq", firstSeq, "index", firstPosition));
        change.put("viewRevision", viewRevision);
        change.put("collapsedByDefault", f.collapsedByDefault());
        change.put("operationId", f.operationId() == null ? null : f.operationId().toString());
        return change;
    }

    /** Package-visible for focused JSON shape tests. */
    static Map<String, Object> buildDetailSetChange(
            String stepId, String detail, long detailVersion, byte[] detailBytes, byte[] digest) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("type", "PROCESS_STEP_DETAIL_SET");
        change.put("stepId", stepId);
        change.put("detail", detail);
        change.put("detailVersion", Long.toString(detailVersion));
        change.put("byteLength", Integer.toString(detailBytes.length));
        change.put("sha256", HexFormat.of().formatHex(digest));
        return change;
    }

    /** Package-visible for focused JSON shape tests. */
    static Map<String, Object> buildEnvelope(
            String sessionId,
            String streamId,
            String seq,
            String previousSeq,
            String cursor,
            LocalDateTime now,
            List<Map<String, Object>> changes) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("protocolVersion", PROTOCOL);
        envelope.put("sessionId", sessionId);
        envelope.put("streamId", streamId);
        envelope.put("seq", seq);
        envelope.put("previousSeq", previousSeq);
        envelope.put("cursor", cursor);
        envelope.put("occurredAt", now.format(OCCURRED));
        envelope.put("changes", changes);
        return envelope;
    }

    private Map<String, Object> lockSession(String sessionId, String userId) {
        var rows = jdbc.queryForList(
                "SELECT * FROM business_session WHERE id=? AND user_id=? FOR UPDATE", sessionId, userId);
        if (rows.size() != 1) {
            throw new IllegalStateException("SESSION_LOCK_LOST");
        }
        return rows.get(0);
    }

    private static String streamIdOf(Map<String, Object> session) {
        Object raw = session.get("process_projection_stream_id");
        if (raw == null) {
            return null;
        }
        if (raw instanceof byte[] bytes) {
            return uuid(bytes);
        }
        return String.valueOf(raw);
    }

    private static String value(Map<String, Object> row, String key) {
        Object raw = row.get(key);
        if (raw == null) {
            return "0";
        }
        return String.valueOf(raw);
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
