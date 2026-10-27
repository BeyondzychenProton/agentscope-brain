package com.agentscope.brain.projection.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Stage-1 formal TEXT slice. No SDK, external I/O, recovery writes or mutable process projection. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TextHistory {
    public static final String PROTOCOL = "web-projection-v1";
    private final JdbcTemplate jdbc;
    private final CurrentUser users;
    private final HistoryCursor cursors;
    public TextHistory(JdbcTemplate jdbc, CurrentUser users, HistoryCursor cursors) {
        this.jdbc = jdbc; this.users = users; this.cursors = cursors;
    }
    public record ContentRef(String contentId, String contentVersion, String kind, String bodyFormat,
            String byteCount, String sha256) { }
    public record Message(String messageId, String messageNo, String senderKind, String acceptedAt, List<ContentRef> contents) { }
    public record Page(List<Message> items, String nextPageCursor, boolean hasMore, String messageUpperBound) { }
    public record Session(String sessionId, String employeeId, String title, String sessionVersion,
            String lifecycleStatus, String recoveryStatus, List<String> allowedActions) { }
    public record Snapshot(String protocolVersion, String sessionId, String streamId, String watermark, String cursor,
            Session session, Page messages) { }
    public record Content(String contentId, String contentVersion, String bodyFormat, String body, String byteCount,
            String sha256, String nextPartCursor) { }
    private record Owned(String userId, Map<String, Object> session, String stream) { }

    public Snapshot snapshot(String sessionId, String protocolVersion, int messageLimit, int processLimit) {
        protocol(protocolVersion); limit(messageLimit); limit(processLimit);
        var owned = owned(sessionId);
        // Do not return an apparently complete snapshot for functionality deferred to stage 2.
        if (owned.session().get("source_session_id") != null
                || count("SELECT COUNT(*) FROM business_run WHERE session_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')", sessionId) > 0
                || count("SELECT COUNT(*) FROM run_progress p JOIN business_run r ON r.id=p.run_id WHERE r.session_id=?", sessionId) > 0
                || count("SELECT COUNT(*) FROM projection_content WHERE session_id=? AND content_kind<>'TEXT'", sessionId) > 0) {
            throw new ProjectionReadFailure(503, "PROJECTION_SCOPE_UNAVAILABLE");
        }
        var upper = number(owned.session(), "last_message_no");
        var watermark = number(owned.session(), "last_projection_seq");
        var position = new HistoryCursor.Position("messages", owned.userId(), sessionId, owned.stream(), watermark, upper,
                new BigInteger(upper).add(BigInteger.ONE).toString());
        var session = new Session(sessionId, number(owned.session(), "employee_id"), (String) owned.session().get("title"),
                number(owned.session(), "row_version"), (String) owned.session().get("lifecycle_status"),
                (String) owned.session().get("recovery_status"), List.of());
        var cursor = cursors.encode(new HistoryCursor.Position("stream", owned.userId(), sessionId, owned.stream(), watermark, upper, "0"));
        return new Snapshot(PROTOCOL, sessionId, owned.stream(), watermark, cursor, session, page(owned, position, messageLimit));
    }

    public Page messages(String sessionId, String protocolVersion, String pageCursor, int pageLimit) {
        protocol(protocolVersion); limit(pageLimit);
        var owned = owned(sessionId);
        var position = cursors.decode(pageCursor);
        if (!position.userId().equals(owned.userId()) || !position.sessionId().equals(sessionId)) {
            throw new ProjectionReadFailure(404, "NOT_FOUND");
        }
        if (!position.streamId().equals(owned.stream())) { throw new ProjectionReadFailure(409, "STREAM_CHANGED"); }
        if (!position.purpose().equals("messages")) { throw new ProjectionReadFailure(409, "CURSOR_INVALID"); }
        if (new BigInteger(position.watermark()).compareTo(new BigInteger(number(owned.session(), "last_projection_seq"))) > 0
                || new BigInteger(position.upper()).compareTo(new BigInteger(number(owned.session(), "last_message_no"))) > 0) {
            throw new ProjectionReadFailure(410, "RESET_REQUIRED");
        }
        return page(owned, position, pageLimit);
    }

    public Content content(String sessionId, String protocolVersion, String contentId, String contentVersion) {
        protocol(protocolVersion);
        var owned = owned(sessionId);
        byte[] id;
        try { id = uuidBytes(UUID.fromString(contentId)); positive(contentVersion); }
        catch (Exception invalid) { throw new ProjectionReadFailure(404, "NOT_FOUND"); }
        var heads = jdbc.queryForList("SELECT * FROM projection_content WHERE content_id=? AND session_id=? AND owner_user_id=?",
                id, sessionId, owned.userId());
        if (heads.size() != 1) { throw new ProjectionReadFailure(404, "NOT_FOUND"); }
        if (!Arrays.equals((byte[]) heads.get(0).get("stream_id"), (byte[]) owned.session().get("projection_stream_id"))) {
            throw new ProjectionReadFailure(409, "STREAM_CHANGED");
        }
        var rows = jdbc.queryForList("""
                SELECT v.*,b.text_content,m.sender_kind FROM projection_content_version v
                JOIN message_content_block b ON v.source_kind='MESSAGE_BLOCK' AND b.id=v.source_id AND b.block_kind='TEXT'
                JOIN business_message m ON m.id=b.message_id AND m.session_id=? AND m.user_id=?
                WHERE v.content_id=? AND v.content_version=?
                """, sessionId, owned.userId(), id, contentVersion);
        if (rows.size() != 1) { throw new ProjectionReadFailure(410, "CONTENT_VERSION_UNAVAILABLE"); }
        var row = rows.get(0);
        if (!"TEXT".equals(heads.get(0).get("content_kind")) || !"CLOSED".equals(row.get("content_status"))) {
            throw new ProjectionReadFailure(410, "CONTENT_VERSION_UNAVAILABLE");
        }
        byte[] body = "FIXED_REF".equals(row.get("payload_storage"))
                ? String.valueOf(row.get("text_content")).getBytes(StandardCharsets.UTF_8) : (byte[]) row.get("body");
        try {
            if (body == null || body.length > 262144 || body.length != ((Number) row.get("byte_count")).intValue()
                    || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), (byte[]) row.get("body_sha256")))
                { throw new IllegalArgumentException(); }
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            return new Content(contentId, contentVersion, String.valueOf(row.get("body_format")), text, String.valueOf(body.length),
                    HexFormat.of().formatHex((byte[]) row.get("body_sha256")), null);
        } catch (Exception corrupt) { throw new ProjectionReadFailure(410, "CONTENT_VERSION_UNAVAILABLE"); }
    }

    private Page page(Owned owned, HistoryCursor.Position p, int limit) {
        var rows = jdbc.queryForList("""
                SELECT * FROM business_message WHERE session_id=? AND user_id=? AND message_no<=? AND message_no<?
                ORDER BY message_no DESC LIMIT ?
                """, p.sessionId(), owned.userId(), p.upper(), p.before(), limit + 1);
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        var items = new ArrayList<Message>();
        for (var row : selected) {
            String messageId = number(row, "id");
            var blocks = jdbc.queryForList("SELECT id,block_kind FROM message_content_block WHERE message_id=? ORDER BY block_no LIMIT 129", messageId);
            if (blocks.isEmpty() || blocks.size() > 128 || blocks.stream().anyMatch(b -> !"TEXT".equals(b.get("block_kind")))) {
                throw new ProjectionReadFailure(503, "PROJECTION_SCOPE_UNAVAILABLE");
            }
            var refs = new ArrayList<ContentRef>();
            for (var block : blocks) {
                var versions = jdbc.queryForList("""
                        SELECT c.content_id,v.content_version,v.body_format,v.byte_count,v.body_sha256
                        FROM projection_content c JOIN projection_content_version v ON v.content_id=c.content_id
                        WHERE c.owner_user_id=? AND c.session_id=? AND c.stream_id=? AND c.content_kind='TEXT'
                          AND v.source_kind='MESSAGE_BLOCK' AND v.source_id=? AND v.projection_seq<=? AND v.content_status='CLOSED'
                        ORDER BY v.content_version DESC LIMIT 1
                        """, owned.userId(), p.sessionId(), owned.session().get("projection_stream_id"), block.get("id"), p.watermark());
                if (versions.size() != 1) { throw new ProjectionReadFailure(410, "CONTENT_VERSION_UNAVAILABLE"); }
                var v = versions.get(0);
                refs.add(new ContentRef(uuid((byte[]) v.get("content_id")), number(v, "content_version"), "TEXT",
                        String.valueOf(v.get("body_format")), number(v, "byte_count"), HexFormat.of().formatHex((byte[]) v.get("body_sha256"))));
            }
            var timestamp = row.get("accepted_at");
            var at = (timestamp instanceof java.time.LocalDateTime local
                    ? local.toInstant(java.time.ZoneOffset.UTC) : ((java.sql.Timestamp) timestamp).toInstant()).toString();
            items.add(new Message(messageId, number(row, "message_no"), String.valueOf(row.get("sender_kind")), at, List.copyOf(refs)));
        }
        Collections.reverse(items);
        String next = more ? cursors.encode(new HistoryCursor.Position("messages", p.userId(), p.sessionId(), p.streamId(),
                p.watermark(), p.upper(), items.get(0).messageNo())) : null;
        return new Page(List.copyOf(items), next, more, p.upper());
    }

    private Owned owned(String sessionId) {
        var user = users.require();
        if (user.passwordChangeRequired()) { throw new ProjectionReadFailure(403, "PASSWORD_CHANGE_REQUIRED"); }
        try { positive(sessionId); } catch (Exception invalid) { throw new ProjectionReadFailure(404, "NOT_FOUND"); }
        var rows = jdbc.queryForList("SELECT * FROM business_session WHERE id=? AND user_id=?", sessionId, user.userId());
        if (rows.size() != 1) { throw new ProjectionReadFailure(404, "NOT_FOUND"); }
        var session = rows.get(0);
        if (session.get("projection_stream_id") == null) { throw new ProjectionReadFailure(503, "PROJECTION_READ_UNAVAILABLE"); }
        return new Owned(user.userId(), session, uuid((byte[]) session.get("projection_stream_id")));
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static String number(Map<String, Object> row, String column) { return String.valueOf(row.get(column)); }
    private static void positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,19}") || new BigInteger(value).bitLength() > 64) { throw new IllegalArgumentException(); }
    }
    private static void protocol(String version) {
        if (!PROTOCOL.equals(version)) { throw new ProjectionReadFailure(409, "PROJECTION_VERSION_UNSUPPORTED"); }
    }
    private static void limit(int value) { if (value < 1 || value > 100) { throw new ProjectionReadFailure(400, "REQUEST_INVALID"); } }
    private static byte[] uuidBytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static String uuid(byte[] bytes) {
        var b = ByteBuffer.wrap(bytes); return new UUID(b.getLong(), b.getLong()).toString();
    }
}
