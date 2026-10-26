package com.agentscope.brain.interaction;

import com.agentscope.brain.interaction.PendingTerminationProbe.Binding;
import com.agentscope.brain.interaction.PendingTerminationProbe.QuiescenceProof;
import com.agentscope.brain.interaction.PendingTerminationProbe.RestartRead;
import com.agentscope.brain.interaction.PendingTerminationProbe.SuspendOutcome;
import com.agentscope.brain.interaction.PendingTerminationProbe.TerminalStatus;
import com.agentscope.brain.interaction.PendingTerminationProbe.TerminateOutcome;
import com.agentscope.brain.interaction.PendingTerminationProbe.TerminalizationRecord;
import com.agentscope.brain.state.IsolatedMysql;
import io.agentscope.core.state.AgentState;
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
 * #27 pending 无执行终结与旧 writer 竞争隔离探针。
 * 临时 MySQL，不连业务库，不记录密码。
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PendingTerminationProbeTest {
    private static IsolatedMysql mysql;

    @BeforeAll
    static void startIsolatedMysql() throws Exception {
        mysql = IsolatedMysql.start("issue27_pending_term");
    }

    @AfterAll
    static void stopIsolatedMysql() throws Exception {
        if (mysql != null) {
            mysql.close();
        }
    }

    @Test
    void quiescenceThenCasTerminatesWithoutModelOrTools() {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-ok", "tool-term-1", "sdk-suspend-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("终结表单"));
        assertThat(suspended.save().persisted()).isTrue();
        assertThat(suspended.pending().supported()).isTrue();

        QuiescenceProof quiescence = probe.markQuiescent(binding, suspended.save().version());
        assertThat(quiescence.exited()).isTrue();

        int modelsBefore = probe.modelCalls();
        int toolsBefore = probe.toolBodyCalls();
        int otherBefore = probe.otherToolSends();

        TerminateOutcome first = probe.terminate(binding, "term-key-1", quiescence, "PLATFORM_CANCEL", false);
        assertThat(first.status()).as(first.reason()).isEqualTo(TerminalStatus.VERIFIED);
        assertThat(first.after().version()).isGreaterThan(first.before().version());
        assertThat(first.matchingTerminalResultCount()).isEqualTo(1);
        assertThat(first.pendingAfter() == null || !first.pendingAfter().supported()).isTrue();
        assertThat(first.waitingMetadataCleared()).isTrue();
        assertThat(first.toolUsePreserved()).isTrue();
        assertThat(probe.modelCalls() - modelsBefore).isZero();
        assertThat(probe.toolBodyCalls() - toolsBefore).isZero();
        assertThat(probe.otherToolSends() - otherBefore).isZero();

        TerminalizationRecord record = probe.evidence("term-key-1");
        assertThat(record).isNotNull();
        assertThat(record.status()).isEqualTo(TerminalStatus.VERIFIED);
        assertThat(record.beforeVersion()).isEqualTo(first.before().version());
        assertThat(record.afterVersion()).isEqualTo(first.after().version());
        assertThat(record.originalCallVersion()).isEqualTo(suspended.save().version());
    }

    @Test
    void fenceOrLeaseAloneCannotAuthorizeTerminateWhileInFlight() {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-inflight", "tool-inflight-1", "sdk-inflight-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("在途"));
        // 仅有 fence／epoch／lease，没有静止退出证明 → 不得终结
        QuiescenceProof fake = QuiescenceProof.leaseOnly(binding.fence(), binding.stateGeneration());
        TerminateOutcome outcome = probe.terminate(binding, "term-key-inflight", fake, "PLATFORM_CANCEL", false);
        assertThat(outcome.status()).isEqualTo(TerminalStatus.RECONCILING);
        assertThat(outcome.reason()).isEqualTo("CALL_NOT_QUIESCENT");
        assertThat(probe.readRaw(binding).version()).isEqualTo(suspended.save().version());
    }

    @Test
    void sameTerminalizationKeyRetryDoesNotSecondTransform() {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-retry", "tool-retry-1", "sdk-retry-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("重试"));
        QuiescenceProof quiescence = probe.markQuiescent(binding, suspended.save().version());

        TerminateOutcome first = probe.terminate(binding, "term-key-retry", quiescence, "PLATFORM_CANCEL", false);
        assertThat(first.status()).isEqualTo(TerminalStatus.VERIFIED);
        long afterVersion = first.after().version();
        int terminalCount = first.matchingTerminalResultCount();

        TerminateOutcome retry = probe.terminate(binding, "term-key-retry", quiescence, "PLATFORM_CANCEL", false);
        assertThat(retry.status()).isEqualTo(TerminalStatus.VERIFIED);
        assertThat(retry.reason()).isEqualTo("IDEMPOTENT_REPLAY");
        assertThat(retry.after().version()).isEqualTo(afterVersion);
        assertThat(retry.matchingTerminalResultCount()).isEqualTo(terminalCount);
        assertThat(probe.readRaw(binding).version()).isEqualTo(afterVersion);
    }

    @Test
    void concurrentOldWriterCasConflictStaysReconciling() {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-cas", "tool-cas-1", "sdk-cas-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("竞争"));
        QuiescenceProof quiescence = probe.markQuiescent(binding, suspended.save().version());

        // 旧 writer 抢先 CAS 抬版本
        AgentState hijacked = probe.independentRead(binding).value();
        AgentState mutated = AgentState.fromJsonString(hijacked.toJson());
        mutated.setSummary("old-writer-hijack");
        long hijack = probe.casSave(binding, mutated, suspended.save().version());
        assertThat(hijack).isGreaterThan(0);

        TerminateOutcome outcome = probe.terminate(binding, "term-key-cas", quiescence, "PLATFORM_CANCEL", false);
        assertThat(outcome.status()).isEqualTo(TerminalStatus.RECONCILING);
        assertThat(outcome.reason()).isIn("CAS_FAIL", "VERSION_MOVED");
        // 不明时不二次变换成终结；当前版本仍是旧 writer 写入
        assertThat(probe.readRaw(binding).version()).isEqualTo(hijack);
        assertThat(probe.pendingFrom(probe.independentRead(binding).value()).supported()).isTrue();
    }

    @Test
    void saveFailureKeepsReconcilingAndDoesNotClaimTerminated() {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-fail", "tool-fail-1", "sdk-fail-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("保存失败"));
        QuiescenceProof quiescence = probe.markQuiescent(binding, suspended.save().version());

        TerminateOutcome outcome = probe.terminate(binding, "term-key-fail", quiescence, "PLATFORM_CANCEL", true);
        assertThat(outcome.status()).isEqualTo(TerminalStatus.RECONCILING);
        assertThat(outcome.reason()).isEqualTo("SAVE_OR_PROOF_UNCLEAR");
        assertThat(probe.readRaw(binding).version()).isEqualTo(suspended.save().version());
        assertThat(probe.pendingFrom(probe.independentRead(binding).value()).supported()).isTrue();
    }

    @Test
    void lostProofOnlyChecksOriginalFactsNoSecondWrite() {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-lost", "tool-lost-1", "sdk-lost-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("证明丢失"));
        QuiescenceProof quiescence = probe.markQuiescent(binding, suspended.save().version());

        TerminateOutcome first = probe.terminate(binding, "term-key-lost", quiescence, "PLATFORM_CANCEL", false);
        assertThat(first.status()).isEqualTo(TerminalStatus.VERIFIED);
        long version = first.after().version();

        probe.dropEvidence("term-key-lost");
        TerminateOutcome unclear = probe.terminate(binding, "term-key-lost", quiescence, "PLATFORM_CANCEL", false);
        assertThat(unclear.status()).isEqualTo(TerminalStatus.RECONCILING);
        assertThat(unclear.reason()).isEqualTo("PROOF_LOST_CHECK_ONLY");
        assertThat(probe.readRaw(binding).version()).isEqualTo(version);
        assertThat(unclear.matchingTerminalResultCount()).isEqualTo(1);
    }

    @Test
    void restartFreshReadDoesNotRevivePendingAndCountsZero() {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-restart", "tool-restart-1", "sdk-restart-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("重启"));
        QuiescenceProof quiescence = probe.markQuiescent(binding, suspended.save().version());
        TerminateOutcome terminated = probe.terminate(binding, "term-key-restart", quiescence, "PLATFORM_CANCEL", false);
        assertThat(terminated.status()).isEqualTo(TerminalStatus.VERIFIED);

        PendingTerminationProbe fresh = new PendingTerminationProbe(mysql.dataSource());
        int modelsBefore = fresh.modelCalls();
        int toolsBefore = fresh.toolBodyCalls();
        int otherBefore = fresh.otherToolSends();
        RestartRead restart = fresh.restartRead(binding);
        assertThat(restart.persisted()).isTrue();
        assertThat(restart.pending() == null || !restart.pending().supported()).isTrue();
        assertThat(restart.matchingTerminalResultCount()).isEqualTo(1);
        assertThat(restart.waitingMetadataPresent()).isFalse();
        assertThat(restart.toolUsePreserved()).isTrue();
        assertThat(fresh.modelCalls() - modelsBefore).isZero();
        assertThat(fresh.toolBodyCalls() - toolsBefore).isZero();
        assertThat(fresh.otherToolSends() - otherBefore).isZero();
    }

    @Test
    void unsupportedOrMixedPendingStaysReconciling() {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-multi", "tool-multi-1", "sdk-multi-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("多 pending"));
        assertThat(suspended.save().persisted()).isTrue();
        // 注入第二个未决 ToolUse，制造不受支持的混合／多 pending；再在新版本上取得静止证明
        probe.injectExtraPending(binding, "extra-tool-2", "other_tool");
        long current = probe.readRaw(binding).version();
        QuiescenceProof quiescence = probe.markQuiescent(binding, current);

        TerminateOutcome outcome = probe.terminate(binding, "term-key-multi", quiescence, "PLATFORM_CANCEL", false);
        assertThat(outcome.status()).isEqualTo(TerminalStatus.RECONCILING);
        assertThat(outcome.reason()).isEqualTo("PENDING_UNSUPPORTED");
        assertThat(probe.sessionBlocked(binding)).isTrue();
    }

    @Test
    void writesEvidenceSummaryWithoutSecrets() throws Exception {
        PendingTerminationProbe probe = new PendingTerminationProbe(mysql.dataSource());
        Binding binding = binding("term-ev", "tool-ev-1", "sdk-ev-1");
        SuspendOutcome suspended = probe.suspend(binding, proposal("证据"));
        QuiescenceProof quiescence = probe.markQuiescent(binding, suspended.save().version());
        TerminateOutcome terminated = probe.terminate(binding, "term-key-ev", quiescence, "PLATFORM_CANCEL", false);
        RestartRead restart = new PendingTerminationProbe(mysql.dataSource()).restartRead(binding);

        Path dir = Path.of("evidence", "issue27");
        Files.createDirectories(dir);
        String summary = """
                {
                  "issue": 27,
                  "mysqlVersion": %s,
                  "table": "%s",
                  "terminalStatus": "%s",
                  "beforeVersion": %d,
                  "afterVersion": %d,
                  "terminalResultCount": %d,
                  "restartPendingSupported": %s,
                  "modelCalls": %d,
                  "toolBodyCalls": %d,
                  "otherToolSends": %d
                }
                """.formatted(
                quote(mysql.version()),
                PendingTerminationProbe.TABLE_NAME,
                terminated.status(),
                terminated.before().version(),
                terminated.after().version(),
                terminated.matchingTerminalResultCount(),
                restart.pending() != null && restart.pending().supported(),
                probe.modelCalls(),
                probe.toolBodyCalls(),
                probe.otherToolSends());
        Files.writeString(dir.resolve("probe-summary.json"), summary, StandardCharsets.UTF_8);
        assertThat(summary).doesNotContain("123456").doesNotContain("password");
        assertThat(terminated.status()).isEqualTo(TerminalStatus.VERIFIED);
    }

    private static Binding binding(String slot, String toolId, String sdkCallKey) {
        return new Binding("user-27", "release-27", "biz-session-27", "run-27", slot, 1L, sdkCallKey, 11L,
                "writer-27", toolId);
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
