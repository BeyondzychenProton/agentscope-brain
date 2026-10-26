package com.agentscope.brain.memoryseam;

import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;

public final class PlatformNamespaceFactory {

    private PlatformNamespaceFactory() {
    }

    public static NamespaceFactory memory(TrustedBinding binding) {
        return context -> KvNamespaces.memory(binding);
    }

    public static NamespaceFactory runtime(TrustedBinding binding) {
        return context -> KvNamespaces.runtime(binding);
    }

    public static NamespaceFactory release(TrustedBinding binding) {
        return context -> KvNamespaces.release(binding);
    }
}
