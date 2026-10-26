package com.agentscope.brain.interaction;

import com.agentscope.brain.interaction.PendingInteractionProbe.AcceptOutcome;
import com.agentscope.brain.interaction.PendingInteractionProbe.Binding;
import com.agentscope.brain.interaction.PendingInteractionProbe.ClaimOutcome;
import com.agentscope.brain.interaction.PendingInteractionProbe.ConsumeOutcome;
import com.agentscope.brain.interaction.PendingInteractionProbe.ReadyStatus;
import com.agentscope.brain.interaction.PendingInteractionProbe.ReadyVerdict;
import com.agentscope.brain.interaction.PendingInteractionProbe.ResponseStatus;
import com.agentscope.brain.interaction.PendingInteractionProbe.RestartOutcome;
import com.agentscope.brain.interaction.PendingInteractionProbe.SuspendOutcome;
import com.agentscope.brain.interaction.PendingInteractionProbe.SyntheticAnswer;
import com.agentscope.brain.state.IsolatedMysql;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.GenerateReason;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #26 pending 就绪与合成回答消费隔离探针。使用临时 MySQL，不连接业务库，不记录密码。
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PendingInteractionProbeTest {
    private static IsolatedMysql mysql;

    @BeforeAll
    static void startIsolatedMysql() throws Exception {
        mysql = IsolatedMysql.start("issue26_pending");
    }

    @AfterAll
    static void stopIsolatedMysql() throws Exception {
        if (mysql != null) {
            mysql.close();
        }
    }

    @Test
    void controlledInputSuspendsPersistsAndIsReadyOnlyWithSaveProof() {
        PendingInteractionProbe probe = new PendingInteractionProbe(mysql.dataSource());
        Binding binding = binding("ready-slot", "tool-ready-1", "sdk-suspend-1");
        Map<String, Object> proposal = proposal("会议室预约");

        SuspendOutcome suspended = probe.suspend(binding, proposal);
        assertThat(suspended.result()).as("suspended result").isNotNull();
        assertThat(suspended.result().getGenerateReason()).isEqualTo(GenerateReason.TOOL_SUSPENDED);
        assertThat(suspended.eventSeen()).isTrue();
        assertThat(probe.validationPasses()).isEqualTo(1);
        assertThat(probe.toolBodyCalls()).isEqualTo(1);
        assertThat(probe.lastEvents()).anyMatch(RequireExternalExecutionEvent.class::isInstance);

        ReadyVerdict eventOnly = new ReadyVerdict(
                ReadyStatus.NOT_READY,
                "EVENT_ONLY",
                null,
                PendingInteractionProbe.SaveFact.missing(),
                suspended.pending(),
                true);
        assertThat(eventOnly.status()).isEqualTo(ReadyStatus.NOT_READY);

        ReadyVerdict ready = probe.assessReady(suspended);
        assertThat(ready.status()).as(ready.reason()).isEqualTo(ReadyStatus.READY);
        assertThat(ready.save().persisted()).isTrue();
        assertThat(ready.pending().toolId()).isEqualTo("tool-ready-1");
        assertThat(ready.pending().toolName()).isEqualTo(PendingInteractionProbe.TOOL_NAME);
        assertThat(ready.card()).isNotNull();
    }

    @Test
    void eventWithoutPersistedStateIsNotReady() {
        PendingInteractionProbe probe = new PendingInteractionProbe(mysql.dataSource());
        Binding binding = binding("event-only-slot", "tool-event-1", "sdk-event-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("仅事件"));
        assertThat(suspended.eventSeen()).isTrue();

        // 人为抹去“保存证明”：把 save 视为缺失时不得 READY。
        SuspendOutcome withoutSave = new SuspendOutcome(
                suspended.binding(),
                suspended.result(),
                true,
                PendingInteractionProbe.SaveFact.missing(),
                suspended.pending(),
                suspended.proposal(),
                suspended.agent(),
                suspended.model(),
                suspended.tool());
        ReadyVerdict verdict = probe.assessReady(withoutSave);
        assertThat(verdict.status()).isEqualTo(ReadyStatus.NOT_READY);
        assertThat(verdict.reason()).isEqualTo("SAVE_MISSING");
        assertThat(verdict.card()).isNull();
    }

    @Test
    void syntheticAnswerConsumeRequiresSaveAndIndependentReadback() {
        PendingInteractionProbe probe = new PendingInteractionProbe(mysql.dataSource());
        Binding binding = binding("consume-slot", "tool-consume-1", "sdk-consume-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("消费表单"));
        ReadyVerdict ready = probe.assessReady(suspended);
        assertThat(ready.status()).isEqualTo(ReadyStatus.READY);

        SyntheticAnswer answer = new SyntheticAnswer(
                "resp-1",
                1,
                ready.save().sha256(),
                Map.of("room", "A101", "minutes", 30));
        AcceptOutcome accepted = probe.accept(ready, answer);
        assertThat(accepted.status()).isEqualTo(ResponseStatus.ACCEPTED);

        ClaimOutcome claimed = probe.claim(accepted, "sdk-resume-1");
        assertThat(claimed.status()).isEqualTo(ResponseStatus.CLAIMED);
        long beforeVersion = claimed.beforeCheckpoint().version();

        ConsumeOutcome consumed = probe.consume(claimed, false);
        assertThat(consumed.status()).isEqualTo(ResponseStatus.CONSUMED);
        assertThat(consumed.durableProof()).isTrue();
        assertThat(consumed.matchingResultPresent()).isTrue();
        assertThat(consumed.after().persisted()).isTrue();
        assertThat(consumed.after().version()).isGreaterThan(beforeVersion);
        assertThat(consumed.pendingAfter() == null || !consumed.pendingAfter().supported()).isTrue();
        assertThat(consumed.externalEventSeen()).isTrue();
    }

    @Test
    void saveFailureAfterSdkApplyStaysReconcilingAndDoesNotMarkConsumed() {
        PendingInteractionProbe probe = new PendingInteractionProbe(mysql.dataSource());
        Binding binding = binding("fail-save-slot", "tool-fail-1", "sdk-fail-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("保存失败"));
        ReadyVerdict ready = probe.assessReady(suspended);
        AcceptOutcome accepted = probe.accept(ready, new SyntheticAnswer("resp-fail", 1, ready.save().sha256(),
                Map.of("room", "B202")));
        ClaimOutcome claimed = probe.claim(accepted, "sdk-resume-fail");

        ConsumeOutcome outcome = probe.consume(claimed, true);
        assertThat(outcome.status()).isEqualTo(ResponseStatus.RECONCILING);
        assertThat(outcome.durableProof()).isFalse();
        assertThat(probe.card().responseStatus()).isEqualTo(ResponseStatus.RECONCILING);

        RestartOutcome restart = probe.restartWithoutAutoResend();
        assertThat(restart.autoResent()).isFalse();
        assertThat(restart.holdForReconcile()).isTrue();
        assertThat(restart.retainedAnswer().responseId()).isEqualTo("resp-fail");
    }

    @Test
    void claimedLeaseExpiryDoesNotAutoResend() {
        PendingInteractionProbe probe = new PendingInteractionProbe(mysql.dataSource());
        Binding binding = binding("lease-slot", "tool-lease-1", "sdk-lease-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("租约"));
        ReadyVerdict ready = probe.assessReady(suspended);
        AcceptOutcome accepted = probe.accept(ready, new SyntheticAnswer("resp-lease", 1, ready.save().sha256(),
                Map.of("room", "C303")));
        ClaimOutcome claimed = probe.claim(accepted, "sdk-resume-lease");
        assertThat(claimed.status()).isEqualTo(ResponseStatus.CLAIMED);

        RestartOutcome restart = probe.restartWithoutAutoResend();
        assertThat(restart.autoResent()).isFalse();
        assertThat(restart.holdForReconcile()).isTrue();
        assertThat(restart.retainedAnswer().normalized()).containsEntry("room", "C303");
    }

    @Test
    void writesEvidenceSummaryWithoutSecrets() throws Exception {
        PendingInteractionProbe probe = new PendingInteractionProbe(mysql.dataSource());
        Binding binding = binding("evidence-slot", "tool-ev-1", "sdk-ev-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("证据"));
        ReadyVerdict ready = probe.assessReady(suspended);
        AcceptOutcome accepted = probe.accept(ready, new SyntheticAnswer("resp-ev", 1, ready.save().sha256(),
                Map.of("ok", true)));
        ClaimOutcome claimed = probe.claim(accepted, "sdk-resume-ev");
        ConsumeOutcome consumed = probe.consume(claimed, false);

        Path dir = Path.of("evidence", "issue26");
        Files.createDirectories(dir);
        String summary = """
                {
                  "issue": 26,
                  "mysqlVersion": %s,
                  "table": "%s",
                  "readyStatus": "%s",
                  "readyVersion": %d,
                  "consumeStatus": "%s",
                  "consumeAfterVersion": %d,
                  "modelCalls": %d,
                  "toolBodyCalls": %d,
                  "validationPasses": %d
                }
                """.formatted(
                quote(mysql.version()),
                PendingInteractionProbe.TABLE_NAME,
                ready.status(),
                ready.save().version(),
                consumed.status(),
                consumed.after().version(),
                probe.modelCalls(),
                probe.toolBodyCalls(),
                probe.validationPasses());
        Files.writeString(dir.resolve("probe-summary.json"), summary, StandardCharsets.UTF_8);
        assertThat(summary).doesNotContain("123456").doesNotContain("password");
        assertThat(consumed.status()).isEqualTo(ResponseStatus.CONSUMED);
    }

    private static Binding binding(String slot, String toolId, String sdkCallKey) {
        return new Binding("user-26", "release-26", "biz-session-26", "run-26", slot, 1L, sdkCallKey, 9L, "writer-26",
                toolId);
    }

    private static Map<String, Object> proposal(String title) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("title", title);
        proposal.put("fields", List.of(Map.of("name", "room", "type", "string")));
        return proposal;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
