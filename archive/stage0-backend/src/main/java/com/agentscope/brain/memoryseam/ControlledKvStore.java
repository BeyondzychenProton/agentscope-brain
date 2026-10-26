package com.agentscope.brain.memoryseam;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;

/**
 * 接管固定 v2.0.4 的全局水位键，并默认拒绝五条原生写入口。拒绝不抛出，避免打断仍允许的压缩或业务运行。
 */
public final class ControlledKvStore implements BaseStore {

    static final String WATERMARK_KEY = "watermark";
    static final String WATERMARK_FILE = "memory/.consolidation_state";
    static final String MEMORY_DOCUMENT = "MEMORY.md";

    private final BaseStore delegate;
    private final TrustedBinding binding;
    private final ThreadLocal<WriteAdmission> admission = new ThreadLocal<>();

    public ControlledKvStore(BaseStore delegate, TrustedBinding binding) {
        this.delegate = delegate;
        this.binding = binding;
    }

    public void withAdmission(WriteAdmission writeAdmission, Runnable action) {
        WriteAdmission previous = admission.get();
        admission.set(writeAdmission);
        try {
            action.run();
        } finally {
            if (previous == null) {
                admission.remove();
            } else {
                admission.set(previous);
            }
        }
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        Location location = locate(namespace, key);
        if (!readable(location.namespace())) {
            return null;
        }
        return delegate.get(location.namespace(), location.key());
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        Location location = locate(namespace, key);
        if (writable(location)) {
            delegate.put(location.namespace(), location.key(), value);
        }
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        Location location = locate(namespace, key);
        if (!writable(location)) {
            return false;
        }
        return delegate.putIfVersion(location.namespace(), location.key(), value, expectedVersion);
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int offset, int limit) {
        if (KvNamespaces.nativeWatermark().equals(namespace) || !readable(namespace)) {
            return List.of();
        }
        return delegate.search(namespace, offset, limit);
    }

    @Override
    public void delete(List<String> namespace, String key) {
        Location location = locate(namespace, key);
        if (writable(location)) {
            delegate.delete(location.namespace(), location.key());
        }
    }

    private Location locate(List<String> namespace, String key) {
        if (KvNamespaces.nativeWatermark().equals(namespace)
                && (WATERMARK_KEY.equals(key) || WATERMARK_FILE.equals(key))) {
            return new Location(KvNamespaces.memory(binding), key);
        }
        return new Location(namespace, key);
    }

    private boolean readable(List<String> namespace) {
        return KvNamespaces.memory(binding).equals(namespace)
                || KvNamespaces.runtime(binding).equals(namespace)
                || KvNamespaces.release(binding).equals(namespace);
    }

    private boolean writable(Location location) {
        if (location.key() != null && location.key().indexOf('\u001f') >= 0) {
            return false;
        }
        if (!KvNamespaces.memory(binding).equals(location.namespace())) {
            return false;
        }
        if (WATERMARK_KEY.equals(location.key()) || WATERMARK_FILE.equals(location.key())) {
            return true;
        }
        WriteAdmission current = admission.get();
        if (current == null) {
            return false;
        }
        return switch (current) {
            case FLUSH -> DiaryKeys.isDiaryInput(location.key());
            case CONSOLIDATE -> MEMORY_DOCUMENT.equals(location.key()) || DiaryKeys.isCandidate(location.key());
            case MANUAL_SAVE -> MEMORY_DOCUMENT.equals(location.key());
        };
    }

    private record Location(List<String> namespace, String key) {
    }
}
