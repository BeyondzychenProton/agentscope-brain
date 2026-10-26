package com.agentscope.brain.memoryseam;

import java.util.List;

/**
 * 记忆、会话运行资料和只读发布资产的 namespace。段只用于路由，不表示调用方已经获准访问。
 */
public final class KvNamespaces {

    private KvNamespaces() {
    }

    public static List<String> memory(TrustedBinding binding) {
        return List.of(
                "memory", "employees", binding.employeeId(), "users", binding.userId(),
                "generations", Long.toString(binding.generation()));
    }

    public static List<String> runtime(TrustedBinding binding) {
        return List.of(
                "runtime", "employees", binding.employeeId(), "users", binding.userId(),
                "sessions", binding.businessSessionId());
    }

    public static List<String> release(TrustedBinding binding) {
        return List.of("employees", binding.employeeId(), "releases", binding.releaseId());
    }

    public static List<String> nativeWatermark() {
        return List.of("memory", "consolidation");
    }
}
