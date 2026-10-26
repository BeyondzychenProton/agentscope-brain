package com.agentscope.brain.interaction.internal.validation;

import com.networknt.schema.InputFormat;
import com.networknt.schema.path.PathType;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Draft7;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 应用侧 draft-07 兼容准入入口。只接纳封闭 Schema，不开放引用或方言替换。
 * form-v1 白名单、资源预算、严格时间表示和业务校验由后续交互切片接入。
 */
public final class ClosedDraft7Validator {
    private static final String DIALECT = "http://json-schema.org/draft-07/schema#";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SchemaRegistry registry = SchemaRegistry.withDialects(
            List.of(Draft7.getInstance()), builder -> builder
                    .schemaRegistryConfig(SchemaRegistryConfig.builder()
                            .formatAssertionsEnabled(true)
                            .failFast(false)
                            .pathType(PathType.JSON_POINTER)
                            .typeLoose(false)
                            .build())
                    .schemaLoader(loader -> loader.fetchRemoteResources(false)
                            .allow(iri -> iri.toString().equals("http://json-schema.org/draft-07/schema"))));

    public List<Violation> validate(String schemaJson, String inputJson) {
        Object document = mapper.readValue(schemaJson, Object.class);
        if (!(document instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Schema must be an object");
        }
        rejectReferences(document, true);
        return registry.getSchema(schemaJson).validate(inputJson, InputFormat.JSON).stream()
                .map(error -> {
                    String path = error.getInstanceLocation().toString();
                    if (error.getProperty() != null && (error.getKeyword().equals("required")
                            || error.getKeyword().equals("additionalProperties"))) {
                        path += "/" + error.getProperty().replace("~", "~0").replace("/", "~1");
                    }
                    return new Violation(error.getKeyword(), path, error.getMessage());
                }).toList();
    }

    private static void rejectReferences(Object value, boolean root) {
        if (value instanceof Map<?, ?> object) {
            for (var entry : object.entrySet()) {
                String key = entry.getKey().toString();
                if (key.equals("$ref") || key.equals("$id") || key.equals("$anchor")
                        || key.equals("$dynamicRef") || key.equals("$dynamicAnchor")
                        || key.equals("$recursiveRef") || key.equals("$recursiveAnchor")) {
                    throw new IllegalArgumentException("Schema references and identifiers are forbidden: " + key);
                }
                if (key.equals("$schema") && (!root || !DIALECT.equals(entry.getValue()))) {
                    throw new IllegalArgumentException("Only the built-in draft-07 dialect is allowed");
                }
                rejectReferences(entry.getValue(), false);
            }
        } else if (value instanceof List<?> array) {
            array.forEach(item -> rejectReferences(item, false));
        }
    }

    public record Violation(String keyword, String path, String message) { }
}
