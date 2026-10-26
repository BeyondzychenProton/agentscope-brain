package com.agentscope.brain.validation;

import io.agentscope.core.tool.ToolValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 直接调用已发布 SDK 的原生入口，不复制或替换 ToolValidator。 */
class SdkToolValidatorTest {
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("count", Map.of("type", "integer", "minimum", 1)),
            "required", List.of("count"),
            "additionalProperties", false);

    @Test
    void sdkNativeStringInputAcceptsValidArguments() {
        assertThat(ToolValidator.validateInput("{\"count\":2}", SCHEMA)).isNull();
    }

    @Test
    void sdkNativeStringInputRejectsInvalidArguments() {
        assertThat(ToolValidator.validateInput("{\"count\":0}", SCHEMA))
                .isNotBlank().contains("count");
    }

    @Test
    void sdkNativeDefaultDialectRemains202012() {
        Map<String, Object> schema = Map.of(
                "type", "array",
                "prefixItems", List.of(Map.of("type", "integer")),
                "items", false);
        assertThat(ToolValidator.validateInput("[1]", schema)).isNull();
        assertThat(ToolValidator.validateInput("[\"bad\"]", schema)).isNotBlank();
        assertThat(ToolValidator.validateInput("[1,2]", schema)).isNotBlank();
    }
}
