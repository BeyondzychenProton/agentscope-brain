package com.agentscope.brain.runtime.internal.application;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** The platform owns requestId, so it must never change a confirmation's identity. */
class WriteOperationsTest {
    @Test void requestIdDoesNotChangeTheApprovedOperation() {
        assertThat(WriteOperations.canonical(Map.of("meetingId", 300)))
                .isEqualTo(WriteOperations.canonical(Map.of("meetingId", 300, "requestId", "model-1")))
                .isEqualTo(WriteOperations.canonical(Map.of("requestId", "model-2", "meetingId", 300)));
    }

    @Test void otherArgumentsStillChangeTheOperation() {
        assertThat(WriteOperations.canonical(Map.of("meetingId", 300)))
                .isNotEqualTo(WriteOperations.canonical(Map.of("meetingId", 301)));
        assertThat(WriteOperations.canonical(Map.of("roomId", 9, "attendeePhones", List.of("15738515168"))))
                .isNotEqualTo(WriteOperations.canonical(Map.of("roomId", 9, "attendeePhones", List.of("13800000000"))));
    }
}
