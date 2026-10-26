package com.agentscope.brain.memoryseam;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 固定 v2.0.4 的默认水位、日记键和 namespace。只观察已发布类，不改 SDK。
 */
public final class SdkLayout {

    private static final String DIARY_RECIPE = "memory/\u0001.md";
    private static final List<String> CLOSED_NATIVE_ENTRIES = List.of(
            "io.agentscope.harness.agent.middleware.MemoryFlushMiddleware",
            "io.agentscope.harness.agent.middleware.CompactionMiddleware",
            "io.agentscope.harness.agent.memory.compaction.ConversationCompactor",
            "io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware",
            "io.agentscope.harness.agent.tool.MemorySaveTool");

    private final List<String> watermarkNamespace;
    private final String diaryKey;
    private final String storedKey;
    private final List<String> diaryNamespace;
    private final List<String> closedNativeEntries;

    private SdkLayout(
            List<String> watermarkNamespace,
            String diaryKey,
            String storedKey,
            List<String> diaryNamespace,
            List<String> closedNativeEntries) {
        this.watermarkNamespace = watermarkNamespace;
        this.diaryKey = diaryKey;
        this.storedKey = storedKey;
        this.diaryNamespace = diaryNamespace;
        this.closedNativeEntries = closedNativeEntries;
    }

    public static SdkLayout observe(
            DataSource dataSource,
            JdbcStore store,
            Path workspace,
            String agentId,
            String userId,
            String sessionId) {
        if (dataSource == null || store == null || workspace == null) {
            throw new IllegalArgumentException("sdk layout observation needs a store and workspace");
        }
        String sourcePath = diaryKeyFromFixedClass();
        RecordingStore recording = new RecordingStore(store, sourcePath.substring(sourcePath.length() - 10));
        AbstractFilesystem filesystem = new RemoteFilesystemSpec(recording)
                .toFilesystem(workspace, agentId, null);
        RuntimeContext context = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        WriteResult written = filesystem.write(context, sourcePath, "probe");
        if (!written.isSuccess() || recording.namespace == null || recording.key == null) {
            throw new IllegalStateException("v2.0.4 default filesystem did not store " + sourcePath
                    + " success=" + written.isSuccess()
                    + " error=" + written.error()
                    + " capturedKey=" + recording.key
                    + " capturedNamespace=" + recording.namespace);
        }
        return new SdkLayout(
                readWatermarkNamespace(), recording.key, sourcePath, recording.namespace, loadClosedNativeEntries());
    }

    public List<String> watermarkNamespace() {
        return watermarkNamespace;
    }

    public String diaryKey() {
        return diaryKey;
    }

    public String storedKey() {
        return storedKey;
    }

    public List<String> diaryNamespace() {
        return diaryNamespace;
    }

    public List<String> closedNativeEntries() {
        return closedNativeEntries;
    }

    private static String diaryKeyFromFixedClass() {
        String classBytes = readClassBytes(MemoryFlushManager.class);
        if (!classBytes.contains(DIARY_RECIPE)) {
            throw new IllegalStateException("v2.0.4 MemoryFlushManager no longer builds memory/{date}.md");
        }
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return DIARY_RECIPE.replace("\u0001", today);
    }

    private static List<String> readWatermarkNamespace() {
        try {
            Field field = MemoryConsolidator.class.getDeclaredField("WATERMARK_NAMESPACE");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> namespace = (List<String>) field.get(null);
            return List.copyOf(namespace);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("v2.0.4 watermark namespace is not readable", exception);
        }
    }

    private static List<String> loadClosedNativeEntries() {
        List<String> present = new ArrayList<>();
        for (String name : CLOSED_NATIVE_ENTRIES) {
            try {
                present.add(Class.forName(name).getName());
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException(name, exception);
            }
        }
        return List.copyOf(present);
    }

    private static String readClassBytes(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException exception) {
            throw new IllegalStateException(resource, exception);
        }
    }

    private static final class RecordingStore implements BaseStore {
        private final BaseStore delegate;
        private final String date;
        private List<String> namespace;
        private String key;

        private RecordingStore(BaseStore delegate, String date) {
            this.delegate = delegate;
            this.date = date;
        }

        @Override
        public StoreItem get(List<String> namespace, String key) {
            return delegate.get(namespace, key);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            capture(namespace, key);
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
            capture(namespace, key);
            return delegate.putIfVersion(namespace, key, value, expectedVersion);
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int offset, int limit) {
            return delegate.search(namespace, offset, limit);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }

        private void capture(List<String> namespace, String key) {
            if (key == null || !key.contains(date)) {
                return;
            }
            this.namespace = namespace == null ? List.of() : List.copyOf(namespace);
            this.key = key;
        }
    }
}
