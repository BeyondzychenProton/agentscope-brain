package com.agentscope.brain.runtime.internal.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrdinaryToolLoopTest {
    @Test void rejectsToolMarkupAsFinalAnswer() {
        assertThat(OrdinaryToolLoop.looksLikeToolMarkup("<tool_call><function=identity.resolve>")).isTrue();
        assertThat(OrdinaryToolLoop.looksLikeToolMarkup("董佳怡的手机号是 13800000000")).isFalse();
    }
}
