package com.agentscope.brain.release.api;

/** Loads a fixed published text configuration after the calling use case has checked its grant. */
public interface TextRelease {
    Configuration textConfiguration(String employeeId, String releaseId);

    /** Credentials remain server-side and are intentionally omitted from diagnostic rendering. */
    final class Configuration {
        private final String instructions;
        private final String baseUrl;
        private final String modelId;
        private final String apiKey;
        public Configuration(String instructions, String baseUrl, String modelId, String apiKey) {
            this.instructions = instructions; this.baseUrl = baseUrl; this.modelId = modelId; this.apiKey = apiKey;
        }
        public String instructions() { return instructions; }
        public String baseUrl() { return baseUrl; }
        public String modelId() { return modelId; }
        public String apiKey() { return apiKey; }
        @Override public String toString() { return "TextRelease.Configuration[redacted]"; }
    }
}
