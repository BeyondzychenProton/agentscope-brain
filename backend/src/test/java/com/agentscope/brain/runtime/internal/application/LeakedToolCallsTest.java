package com.agentscope.brain.runtime.internal.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LeakedToolCallsTest {
    @Test void splitsClosedToolCallAndKeepsTheReplyAfterIt() {
        var split = LeakedToolCalls.split("""
                正在为你查找「张三」的人员信息。

                <tool_call> {"name": "identity_resolve", "arguments": {"name": "张三"}} </tool_call>
                张三可以参加。
                """);

        assertThat(split.calls()).hasSize(1);
        assertThat(split.calls().get(0).rawName()).isEqualTo("identity_resolve");
        assertThat(split.calls().get(0).name()).isEqualTo("identity.resolve");
        assertThat(split.calls().get(0).arguments()).containsEntry("name", "张三");
        assertThat(split.before()).contains("正在为你查找「张三」的人员信息。");
        assertThat(split.after()).contains("张三可以参加。");
        assertThat(split.needsContinuation()).isFalse();
    }

    @Test void toolCallWithoutAFollowingReplyNeedsContinuation() {
        var split = LeakedToolCalls.split(
                "<tool_call>{\"name\":\"identity_resolve\",\"arguments\":{\"name\":\"张三\"}}</tool_call>");

        assertThat(split.needsContinuation()).isTrue();
        assertThat(split.after()).isBlank();
    }

    @Test void proseAfterAnUnclosedToolCallStaysVisible() {
        var split = LeakedToolCalls.split(
                "正在查找。\n<tool_call> {\"name\": \"identity.resolve\", \"arguments\": {\"name\": \"张三\"}}\n可以约明天下午。");

        assertThat(split.calls()).singleElement().extracting(LeakedToolCalls.Call::name).isEqualTo("identity.resolve");
        assertThat(split.before()).contains("正在查找。");
        assertThat(split.after()).contains("可以约明天下午。");
        assertThat(split.needsContinuation()).isFalse();
    }

    @Test void ordinaryProseIsNotAToolCall() {
        var split = LeakedToolCalls.split("董佳怡的手机号是 13800000000");

        assertThat(split.calls()).isEmpty();
        assertThat(split.needsContinuation()).isFalse();
        assertThat(split.before()).isEqualTo("董佳怡的手机号是 13800000000");
    }

    @Test void rejectsToolsOutsideTheReadOnlyCatalog() {
        var split = LeakedToolCalls.split(
                "<tool_call>{\"name\":\"room.reserve\",\"arguments\":{}}</tool_call>");

        assertThat(split.calls()).singleElement().extracting(LeakedToolCalls.Call::name).isNull();
        assertThat(split.needsContinuation()).isTrue();
    }
}
