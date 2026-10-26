package com.agentscope.brain.memoryseam;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemorySeamTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void routesMemoryRuntimeAndReleaseFromTheServerBinding() {
        TrustedBinding binding = TrustedBinding.of("employee-1", "user-1", 3, "session-1", "release-1");

        assertThat(KvNamespaces.memory(binding)).containsExactly(
                "memory", "employees", "employee-1", "users", "user-1", "generations", "3");
        assertThat(KvNamespaces.runtime(binding)).containsExactly(
                "runtime", "employees", "employee-1", "users", "user-1", "sessions", "session-1");
        assertThat(KvNamespaces.release(binding)).containsExactly(
                "employees", "employee-1", "releases", "release-1");
    }

    @Test
    void rejectsNamespaceSegmentsThatCouldMergeIntoTheStoreKey() {
        assertThatThrownBy(() -> TrustedBinding.of("emp\u001f1", "user-1", 1, "session-1", "release-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedBinding.of("employee-1", " ", 1, "session-1", "release-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedBinding.of("employee-1", "user-1", 0, "session-1", "release-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callerPathAndRuntimeIdentityDoNotChooseTheNamespace() {
        TrustedBinding binding = TrustedBinding.of("employee-1", "user-1", 3, "session-1", "release-1");
        RuntimeContext attacker = RuntimeContext.builder()
                .userId("other-user")
                .sessionId("other-session")
                .build();

        assertThat(PlatformNamespaceFactory.memory(binding).getNamespace(attacker))
                .containsExactlyElementsOf(KvNamespaces.memory(binding));
        assertThat(PlatformNamespaceFactory.runtime(binding).getNamespace(attacker))
                .containsExactlyElementsOf(KvNamespaces.runtime(binding));
        assertThat(PlatformNamespaceFactory.release(binding).getNamespace(null))
                .containsExactlyElementsOf(KvNamespaces.release(binding));
    }

    @Test
    void candidateKeysAreNotDiaryInput() {
        String todayDiary = "memory/" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        assertThat(DiaryKeys.isDiaryInput(todayDiary)).isTrue();
        assertThat(DiaryKeys.isDiaryInput("candidates/job-1.md")).isFalse();
        assertThat(DiaryKeys.isDiaryInput("memory/.consolidation_state")).isFalse();
        assertThat(DiaryKeys.isDiaryInput("MEMORY.md")).isFalse();
    }

    @Test
    void putIfVersionFalseIsAmbiguousUntilAnIndependentRead() {
        DataSource dataSource = memoryDatabase();
        JdbcStore writer = jdbcStore(dataSource, true);
        List<String> namespace = List.of("memory", "employees", "employee-1", "users", "user-1", "generations", "1");

        assertThat(KvDocuments.presence(writer, namespace, "MEMORY.md")).isEqualTo(DocumentPresence.MISSING);
        assertThat(writer.putIfVersion(namespace, "MEMORY.md", KvDocuments.content(""), 0L)).isTrue();
        assertThat(writer.putIfVersion(namespace, "MEMORY.md", KvDocuments.content("replacement"), 0L)).isFalse();
        assertThat(writer.putIfVersion(namespace, "absent.md", KvDocuments.content("created-by-update"), 4L)).isFalse();

        JdbcStore reader = jdbcStore(dataSource, false);
        assertThat(KvDocuments.presence(reader, namespace, "MEMORY.md")).isEqualTo(DocumentPresence.EMPTY);
        assertThat(KvDocuments.presence(reader, namespace, "absent.md")).isEqualTo(DocumentPresence.MISSING);
        StoreItem kept = reader.get(namespace, "MEMORY.md");
        assertThat(writer.putIfVersion(namespace, "MEMORY.md", KvDocuments.content("second"), kept.version())).isTrue();
        assertThat(writer.putIfVersion(namespace, "MEMORY.md", KvDocuments.content("stale"), kept.version())).isFalse();
        assertThat(jdbcStore(dataSource, false).get(namespace, "MEMORY.md").value())
                .containsEntry("content", "second");
    }

    @Test
    void redirectsWatermarkIntoTheCurrentGenerationAndKeepsOtherGenerations() {
        DataSource dataSource = memoryDatabase();
        JdbcStore delegate = jdbcStore(dataSource, true);
        TrustedBinding current = TrustedBinding.of("employee-1", "user-1", 3, "session-1", "release-1");
        TrustedBinding previous = TrustedBinding.of("employee-1", "user-1", 2, "session-1", "release-1");
        ControlledKvStore currentStore = new ControlledKvStore(delegate, current);
        ControlledKvStore previousStore = new ControlledKvStore(delegate, previous);

        currentStore.put(KvNamespaces.nativeWatermark(), "watermark", KvDocuments.content("gen-3"));
        previousStore.put(KvNamespaces.nativeWatermark(), "watermark", KvDocuments.content("gen-2"));

        assertThat(delegate.get(KvNamespaces.nativeWatermark(), "watermark")).isNull();
        assertThat(currentStore.get(KvNamespaces.nativeWatermark(), "watermark").value())
                .containsEntry("content", "gen-3");
        assertThat(previousStore.get(KvNamespaces.nativeWatermark(), "watermark").value())
                .containsEntry("content", "gen-2");
        assertThat(delegate.get(KvNamespaces.memory(current), "watermark").value())
                .containsEntry("content", "gen-3");
        assertThat(currentStore.search(KvNamespaces.nativeWatermark(), 0, 20)).isEmpty();

        currentStore.put(KvNamespaces.nativeWatermark(), "memory/.consolidation_state", KvDocuments.content("file-3"));
        assertThat(delegate.get(KvNamespaces.nativeWatermark(), "memory/.consolidation_state")).isNull();
        assertThat(currentStore.get(KvNamespaces.nativeWatermark(), "memory/.consolidation_state").value())
                .containsEntry("content", "file-3");
        assertThat(previousStore.get(KvNamespaces.nativeWatermark(), "memory/.consolidation_state")).isNull();
        TrustedBinding otherUser = TrustedBinding.of("employee-1", "user-9", 3, "session-1", "release-1");
        assertThat(new ControlledKvStore(delegate, otherUser)
                .get(KvNamespaces.nativeWatermark(), "watermark")).isNull();
    }

    @Test
    void refusesNativeWritesWithoutBreakingALaterAdmittedSave() {
        DataSource dataSource = memoryDatabase();
        JdbcStore delegate = jdbcStore(dataSource, true);
        TrustedBinding binding = TrustedBinding.of("employee-1", "user-1", 3, "session-1", "release-1");
        ControlledKvStore store = new ControlledKvStore(delegate, binding);
        List<String> memory = KvNamespaces.memory(binding);

        store.withAdmission(WriteAdmission.MANUAL_SAVE, () ->
                assertThat(store.putIfVersion(memory, "MEMORY.md", KvDocuments.content("kept"), 0L)).isTrue());

        long version = jdbcStore(dataSource, false).get(memory, "MEMORY.md").version();
        assertThatCode(() -> store.put(memory, "MEMORY.md", KvDocuments.content("native-overwrite"))).doesNotThrowAnyException();
        assertThat(store.putIfVersion(memory, "MEMORY.md", KvDocuments.content("native-cas"), version)).isFalse();
        assertThat(jdbcStore(dataSource, false).get(memory, "MEMORY.md").value()).containsEntry("content", "kept");

        store.withAdmission(WriteAdmission.MANUAL_SAVE, () ->
                assertThat(store.putIfVersion(memory, "MEMORY.md", KvDocuments.content("saved"), version)).isTrue());
        assertThat(jdbcStore(dataSource, false).get(memory, "MEMORY.md").value()).containsEntry("content", "saved");
    }

    @Test
    void admitsOnlyTheThreeControlledMemoryPathsAndKeepsReleaseReadOnly() {
        DataSource dataSource = memoryDatabase();
        JdbcStore delegate = jdbcStore(dataSource, true);
        TrustedBinding binding = TrustedBinding.of("employee-1", "user-1", 3, "session-1", "release-1");
        TrustedBinding other = TrustedBinding.of("employee-2", "user-2", 3, "session-2", "release-2");
        ControlledKvStore store = new ControlledKvStore(delegate, binding);
        List<String> memory = KvNamespaces.memory(binding);
        List<String> release = KvNamespaces.release(binding);
        String diaryKey = "memory/" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";

        delegate.put(release, "SKILL.md", KvDocuments.content("snapshot"));
        store.withAdmission(WriteAdmission.MANUAL_SAVE, () -> {
            assertThat(store.putIfVersion(memory, diaryKey, KvDocuments.content("diary"), 0L)).isFalse();
            assertThat(store.putIfVersion(memory, "MEMORY.md", KvDocuments.content("baseline"), 0L)).isTrue();
            store.put(release, "SKILL.md", KvDocuments.content("tamper"));
        });
        store.withAdmission(WriteAdmission.FLUSH, () ->
                assertThat(store.putIfVersion(memory, diaryKey, KvDocuments.content("diary"), 0L)).isTrue());
        long version = jdbcStore(dataSource, false).get(memory, "MEMORY.md").version();
        store.withAdmission(WriteAdmission.CONSOLIDATE, () -> {
            assertThat(store.putIfVersion(memory, "candidates/job-1.md", KvDocuments.content("candidate"), 0L)).isTrue();
            assertThat(store.putIfVersion(memory, "MEMORY.md", KvDocuments.content("merged"), version)).isTrue();
        });

        store.put(KvNamespaces.memory(other), "MEMORY.md", KvDocuments.content("cross"));
        assertThat(delegate.get(release, "SKILL.md").value()).containsEntry("content", "snapshot");
        assertThat(delegate.get(KvNamespaces.memory(other), "MEMORY.md")).isNull();
        assertThat(store.get(KvNamespaces.memory(other), "MEMORY.md")).isNull();
        assertThat(DiaryKeys.isDiaryInput("candidates/job-1.md")).isFalse();
        assertThat(delegate.get(memory, diaryKey).value()).containsEntry("content", "diary");
        assertThat(delegate.get(memory, "MEMORY.md").value()).containsEntry("content", "merged");
    }

    @Test
    void fixedV204DefaultsOmitGenerationAndEmployeeWhilePlatformRoutesDoNot() throws Exception {
        DataSource dataSource = memoryDatabase();
        JdbcStore store = jdbcStore(dataSource, true);
        SdkLayout layout = SdkLayout.observe(dataSource, store, tempDir, "room-agent", "user-7", "session-7");
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        assertThat(layout.watermarkNamespace()).containsExactly("memory", "consolidation");
        assertThat(layout.diaryNamespace()).containsExactly(
                "agents", "room-agent", "users", "user-7", "memory");
        assertThat(layout.diaryKey()).isEqualTo("/" + today + ".md");
        assertThat(DiaryKeys.isDiaryInput(layout.diaryKey())).isFalse();
        assertThat(DiaryKeys.isDiaryInput("memory/" + today + ".md")).isTrue();
        assertThat(layout.closedNativeEntries()).contains(
                "io.agentscope.harness.agent.middleware.MemoryFlushMiddleware",
                "io.agentscope.harness.agent.middleware.CompactionMiddleware",
                "io.agentscope.harness.agent.memory.compaction.ConversationCompactor",
                "io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware",
                "io.agentscope.harness.agent.tool.MemorySaveTool");
    }

    private static DataSource memoryDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:memoryseam-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static JdbcStore jdbcStore(DataSource dataSource, boolean initializeSchema) {
        return JdbcStore.builder(dataSource).dialect(new H2Dialect()).initializeSchema(initializeSchema).build();
    }

}
