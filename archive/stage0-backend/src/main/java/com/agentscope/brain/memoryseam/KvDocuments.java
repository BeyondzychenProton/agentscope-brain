package com.agentscope.brain.memoryseam;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;

public final class KvDocuments {

    private KvDocuments() {
    }

    public static Map<String, Object> content(String text) {
        return Map.of("content", text);
    }

    public static DocumentPresence presence(BaseStore store, List<String> namespace, String key) {
        StoreItem item = store.get(namespace, key);
        if (item == null) {
            return DocumentPresence.MISSING;
        }
        Object text = item.value() == null ? null : item.value().get("content");
        if ("".equals(text)) {
            return DocumentPresence.EMPTY;
        }
        return DocumentPresence.PRESENT;
    }
}
