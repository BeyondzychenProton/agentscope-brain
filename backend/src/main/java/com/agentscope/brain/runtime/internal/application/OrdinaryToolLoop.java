package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.projection.api.StreamingReplyProjection;
import com.agentscope.brain.runtime.internal.integration.CompatibleChatModelCaller;
import com.agentscope.brain.runtime.internal.integration.MeetingMcpClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Model ↔ read-only MCP tool loop with durable TEXT_APPEND previews (#43). */
@Service
public class OrdinaryToolLoop {
    private static final Logger log = LoggerFactory.getLogger(OrdinaryToolLoop.class);
    private static final int MAX_ROUNDS = 6;
    private static final int ARGS_LOG_LIMIT = 512;
    private static final int MERGE_BYTES = 4096;
    private static final long MERGE_MS = 100L;

    private final CompatibleChatModelCaller model;
    private final MeetingMcpClient mcp;
    private final ReadonlyToolCatalog tools;
    private final JdbcTemplate jdbc;
    private final StreamingReplyProjection streaming;
    private final TransactionTemplate write;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public OrdinaryToolLoop(
            CompatibleChatModelCaller model,
            MeetingMcpClient mcp,
            ReadonlyToolCatalog tools,
            JdbcTemplate jdbc,
            StreamingReplyProjection streaming,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.model = model;
        this.mcp = mcp;
        this.tools = tools;
        this.jdbc = jdbc;
        this.streaming = streaming;
        this.write = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    public String run(OrdinaryRunClaim.Lease lease, List<Map<String, Object>> seedMessages) {
        String phone = currentPhone(lease.userId());
        List<ReadonlyToolCatalog.Tool> catalog = tools.forRelease(lease.releaseId());
        Set<String> allowed = catalog.stream().map(ReadonlyToolCatalog.Tool::name).collect(Collectors.toUnmodifiableSet());
        List<Map<String, Object>> openaiTools = tools.openaiTools(catalog);
        log.info("tool_loop_start runId={} userId={} releaseId={} toolCount={} tools={}",
                lease.runId(), lease.userId(), lease.releaseId(), catalog.size(), allowed);
        List<Map<String, Object>> messages = new ArrayList<>(seedMessages);
        messages.add(0, Map.of(
                "role", "system",
                "content", "只能通过 API 提供的 tools/function calling 调用工具。禁止输出 <tool_call> 或任何工具 XML/标签。没有可用工具时用中文直接回答。"));
        StreamingReplyProjection.Context streamCtx = new StreamingReplyProjection.Context(
                lease.userId(), lease.sessionId(), lease.runId(), lease.sdkCallId());
        for (int round = 0; round < MAX_ROUNDS; round++) {
            MergeBuffer buffer = new MergeBuffer(streamCtx);
            CompatibleChatModelCaller.ChatTurn turn = model.complete(
                    messages, openaiTools, true, buffer::onCumulative);
            buffer.flush(true);
            if (turn.toolCalls().isEmpty()) {
                String text = turn.content() == null ? "" : turn.content().trim();
                if (text.isBlank()) throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
                if (looksLikeToolMarkup(text)) throw new IllegalStateException("MODEL_TOOL_MARKUP_LEAK");
                if (!text.equals(buffer.lastPublished) && !text.isBlank()) {
                    buffer.publish(text);
                }
                log.info("tool_loop_done runId={} round={} finalChars={}", lease.runId(), round, text.length());
                return text;
            }
            if (buffer.handle != null) {
                closePreview(streamCtx, buffer.handle.contentId(), "INTERRUPTED", null);
                buffer.handle = null;
                buffer.lastPublished = "";
            }
            log.info("tool_loop_round runId={} round={} toolCalls={}",
                    lease.runId(), round, turn.toolCalls().stream().map(CompatibleChatModelCaller.ToolCall::name).toList());
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", turn.content() == null ? "" : turn.content());
            assistant.put("tool_calls", turn.rawToolCalls());
            messages.add(assistant);
            for (CompatibleChatModelCaller.ToolCall call : turn.toolCalls()) {
                if (!allowed.contains(call.name())) {
                    log.warn("tool_call_denied runId={} round={} tool={} reason=TOOL_NOT_ALLOWED",
                            lease.runId(), round, call.name());
                    messages.add(toolMessage(call.id(), "{\"error\":\"TOOL_NOT_ALLOWED\"}"));
                    continue;
                }
                Map<String, Object> args;
                try {
                    args = json.readValue(call.argumentsJson(), new TypeReference<>() { });
                } catch (Exception invalid) {
                    log.warn("tool_call_denied runId={} round={} tool={} reason=TOOL_ARGUMENTS_INVALID",
                            lease.runId(), round, call.name());
                    messages.add(toolMessage(call.id(), "{\"error\":\"TOOL_ARGUMENTS_INVALID\"}"));
                    continue;
                }
                long started = System.nanoTime();
                try {
                    String result = mcp.callTool(phone, call.name(), args);
                    long ms = (System.nanoTime() - started) / 1_000_000L;
                    log.info("tool_call_ok runId={} round={} tool={} args={} resultChars={} durationMs={}",
                            lease.runId(), round, call.name(), truncate(call.argumentsJson()), result.length(), ms);
                    messages.add(toolMessage(call.id(), result));
                } catch (RuntimeException failure) {
                    long ms = (System.nanoTime() - started) / 1_000_000L;
                    String code = failure.getMessage() == null ? "MCP_CALL_FAILED" : failure.getMessage();
                    log.warn("tool_call_fail runId={} round={} tool={} args={} error={} durationMs={}",
                            lease.runId(), round, call.name(), truncate(call.argumentsJson()), code, ms);
                    messages.add(toolMessage(call.id(), "{\"error\":\"" + jsonEscape(code) + "\"}"));
                }
            }
        }
        log.warn("tool_loop_exceeded runId={} maxRounds={}", lease.runId(), MAX_ROUNDS);
        throw new IllegalStateException("TOOL_LOOP_EXCEEDED");
    }

    void closeOpenPreviewsFailed(OrdinaryRunClaim.Lease lease) {
        StreamingReplyProjection.Context streamCtx = new StreamingReplyProjection.Context(
                lease.userId(), lease.sessionId(), lease.runId(), lease.sdkCallId());
        var open = jdbc.queryForList("""
                SELECT content_id FROM projection_content
                WHERE session_id=? AND owner_user_id=? AND run_id=? AND content_kind='TEXT' AND status='OPEN'
                """, lease.sessionId(), lease.userId(), lease.runId());
        for (var row : open) {
            closePreview(streamCtx, uuid((byte[]) row.get("content_id")), "FAILED", null);
        }
    }

    private void closePreview(
            StreamingReplyProjection.Context ctx, String contentId, String reason, String replacementMessageId) {
        write.execute(status -> {
            streaming.close(ctx, contentId, reason, replacementMessageId, now());
            return null;
        });
    }

    private String currentPhone(String userId) {
        var rows = jdbc.queryForList("""
                SELECT phone_normalized FROM phone_reservation
                WHERE owner_user_id=? AND reservation_status='CURRENT'
                """, userId);
        if (rows.size() != 1 || rows.get(0).get("phone_normalized") == null) {
            throw new IllegalStateException("MCP_PHONE_UNAVAILABLE");
        }
        return String.valueOf(rows.get(0).get("phone_normalized"));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", "tool");
        row.put("tool_call_id", toolCallId);
        row.put("content", content);
        return row;
    }

    static boolean looksLikeToolMarkup(String text) {
        String lower = text.toLowerCase();
        return lower.contains("<tool_call") || lower.contains("</tool_call>") || lower.contains("<function=");
    }

    static String truncate(String value) {
        if (value == null) return "";
        if (value.length() <= ARGS_LOG_LIMIT) return value;
        return value.substring(0, ARGS_LOG_LIMIT) + "…";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String uuid(byte[] value) {
        var b = java.nio.ByteBuffer.wrap(value);
        return new java.util.UUID(b.getLong(), b.getLong()).toString();
    }

    private final class MergeBuffer {
        private final StreamingReplyProjection.Context ctx;
        private String pending = "";
        private String lastPublished = "";
        private long lastFlushAt = 0L;
        private StreamingReplyProjection.Handle handle;

        private MergeBuffer(StreamingReplyProjection.Context ctx) {
            this.ctx = ctx;
        }

        private void onCumulative(String cumulative) {
            if (cumulative == null || cumulative.isEmpty()) return;
            pending = cumulative;
            int growth = cumulative.getBytes(StandardCharsets.UTF_8).length
                    - lastPublished.getBytes(StandardCharsets.UTF_8).length;
            long now = System.currentTimeMillis();
            if (growth >= MERGE_BYTES || (lastFlushAt > 0 && now - lastFlushAt >= MERGE_MS) || lastFlushAt == 0) {
                flush(false);
            }
        }

        private void flush(boolean force) {
            if (pending.isEmpty()) return;
            if (!force && pending.equals(lastPublished)) return;
            if (!force && lastFlushAt > 0) {
                int growth = pending.getBytes(StandardCharsets.UTF_8).length
                        - lastPublished.getBytes(StandardCharsets.UTF_8).length;
                long elapsed = System.currentTimeMillis() - lastFlushAt;
                if (growth < MERGE_BYTES && elapsed < MERGE_MS) return;
            }
            publish(pending);
        }

        private void publish(String cumulative) {
            if (cumulative == null || cumulative.isBlank() || cumulative.equals(lastPublished)) return;
            if (cumulative.getBytes(StandardCharsets.UTF_8).length > 262144) {
                throw new IllegalStateException("ASSISTANT_TEXT_INVALID");
            }
            handle = write.execute(status -> streaming.publishCumulative(ctx, cumulative, now()));
            lastPublished = cumulative;
            lastFlushAt = System.currentTimeMillis();
            pending = cumulative;
        }
    }
}
