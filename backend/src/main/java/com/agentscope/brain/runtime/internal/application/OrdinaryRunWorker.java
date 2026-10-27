package com.agentscope.brain.runtime.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Polls WAITING ordinary work, runs read-only tool loop outside locks, then finalizes. */
@Component
public class OrdinaryRunWorker {
    private final JdbcTemplate jdbc;
    private final OrdinaryRunClaim claim;
    private final OrdinaryRunFinalize finalize;
    private final OrdinaryToolLoop toolLoop;
    private final Clock clock;
    private final boolean enabled;
    private final TransactionTemplate write;
    private final String executorRef = "ordinary-worker";

    public OrdinaryRunWorker(
            JdbcTemplate jdbc,
            OrdinaryRunClaim claim,
            OrdinaryRunFinalize finalize,
            OrdinaryToolLoop toolLoop,
            Clock clock,
            PlatformTransactionManager transactions,
            @Value("${brain.conversation.text-submission-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.claim = claim;
        this.finalize = finalize;
        this.toolLoop = toolLoop;
        this.clock = clock;
        this.enabled = enabled;
        this.write = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${brain.runtime.ordinary-poll-ms:1000}")
    public void poll() {
        if (!enabled) return;
        OrdinaryRunClaim.Lease lease = write.execute(status -> claim.claimNext(now(), executorRef));
        if (lease == null) return;
        try {
            String text = toolLoop.run(lease.userId(), lease.releaseId(), dialogue(lease));
            byte[] digest = sha((lease.runId() + '\n' + text).getBytes(StandardCharsets.UTF_8));
            write.execute(status -> {
                finalize.succeed(lease, text, digest, now());
                return null;
            });
        } catch (Exception failure) {
            String reason = failure instanceof IllegalStateException
                    ? truncate(failure.getMessage()) : "MODEL_OR_FINALIZE_FAILED";
            write.execute(status -> {
                finalize.fail(lease, reason, now());
                return null;
            });
        }
    }

    private List<Map<String, Object>> dialogue(OrdinaryRunClaim.Lease lease) {
        List<Map<String, Object>> out = new ArrayList<>();
        var skills = jdbc.queryForList("""
                SELECT skill_markdown FROM employee_release_skill
                WHERE employee_release_id=? ORDER BY id ASC LIMIT 8
                """, lease.releaseId());
        StringBuilder system = new StringBuilder();
        for (var skill : skills) {
            Object markdown = skill.get("skill_markdown");
            if (markdown != null && !String.valueOf(markdown).isBlank()) {
                if (system.length() > 0) system.append("\n\n");
                system.append(String.valueOf(markdown));
            }
        }
        if (system.length() > 0) {
            out.add(message("system", system.toString()));
        }
        var rows = jdbc.queryForList("""
                SELECT m.sender_kind,b.text_content FROM business_message m
                JOIN message_content_block b ON b.message_id=m.id AND b.block_no=1 AND b.block_kind='TEXT'
                WHERE m.session_id=? AND m.user_id=? ORDER BY m.message_no ASC
                """, lease.sessionId(), lease.userId());
        boolean hasUser = false;
        for (Map<String, Object> row : rows) {
            String role = "ASSISTANT".equals(row.get("sender_kind")) ? "assistant" : "user";
            if ("user".equals(role)) hasUser = true;
            out.add(message(role, String.valueOf(row.get("text_content"))));
        }
        if (!hasUser) throw new IllegalStateException("RUN_INPUT_MISSING");
        return out;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        return row;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static byte[] sha(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) return "MODEL_OR_FINALIZE_FAILED";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
