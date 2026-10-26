package com.agentscope.brain.memoryseam;

/**
 * 服务端可信绑定。身份段只能来自已认证的平台用户、数字员工、业务会话和员工发布版本。
 */
public record TrustedBinding(
        String employeeId,
        String userId,
        long generation,
        String businessSessionId,
        String releaseId) {

    public TrustedBinding {
        segment(employeeId, "employeeId");
        segment(userId, "userId");
        segment(businessSessionId, "businessSessionId");
        segment(releaseId, "releaseId");
        if (generation <= 0) {
            throw new IllegalArgumentException("generation");
        }
    }

    public static TrustedBinding of(
            String employeeId,
            String userId,
            long generation,
            String businessSessionId,
            String releaseId) {
        return new TrustedBinding(employeeId, userId, generation, businessSessionId, releaseId);
    }

    private static void segment(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('\u001f') >= 0) {
            throw new IllegalArgumentException(name);
        }
    }
}
