package com.agentscope.brain.validation;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ClosedDraft7ValidatorTest {
    private final ClosedDraft7Validator validator = new ClosedDraft7Validator();

    @Test
    void draft7AcceptsValidInputWithoutChangingIt() {
        String schema = """
                {"$schema":"http://json-schema.org/draft-07/schema#","type":"object",
                 "properties":{"date":{"type":"string","format":"date"},
                               "count":{"type":"integer","default":7}},
                 "required":["date"],"additionalProperties":false}
                """;
        assertThat(validator.validate(schema, "{\"date\":\"2024-02-29\"}")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-02-29", "2026-13-01", "2026-02-30"})
    void formatAssertionsRejectInvalidCalendarDates(String value) {
        assertThat(validator.validate("{\"type\":\"string\",\"format\":\"date\"}",
                "\"" + value + "\""))
                .extracting(ClosedDraft7Validator.Violation::keyword).contains("format");
    }

    @Test
    void formatAssertionsRejectInvalidTimeAndDateTime() {
        assertThat(validator.validate("{\"type\":\"string\",\"format\":\"time\"}",
                "\"25:00:00+08:00\"")).isNotEmpty();
        assertThat(validator.validate("{\"type\":\"string\",\"format\":\"date-time\"}",
                "\"2025-02-29T09:30:00+08:00\"")).isNotEmpty();
    }

    @Test
    void reportsAllKnownFieldErrorsWithoutCoercionOrDefaults() {
        String schema = """
                {"type":"object","required":["title","a/b~c"],"additionalProperties":false,
                 "properties":{"title":{"type":"string","default":"不能代填"},
                               "a/b~c":{"type":"string"},
                               "count":{"type":"integer"},
                               "participants":{"type":"array","items":{"type":"integer"}}}}
                """;
        assertThat(validator.validate(schema,
                "{\"count\":\"2\",\"participants\":[\"bad\"],\"extra\":true}"))
                .extracting(ClosedDraft7Validator.Violation::path)
                .containsExactlyInAnyOrder("/title", "/a~1b~0c", "/count", "/participants/0", "/extra");
    }

    @Test
    void rootTypeErrorStaysAtTheRoot() {
        assertThat(validator.validate("{\"type\":\"object\"}", "[]"))
                .extracting(ClosedDraft7Validator.Violation::path).containsExactly("");
    }

    @Test
    void rejectsRemoteReferencesAndDialectsBeforeAnyHttpRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schema", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"type\":\"string\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/schema";
            for (String key : new String[]{"$ref", "$id", "$schema"}) {
                assertThatIllegalArgumentException().isThrownBy(() ->
                        validator.validate("{\"" + key + "\":\"" + uri + "\"}", "\"value\""));
            }
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsFileClasspathAndInternalReferences(@TempDir Path directory) throws Exception {
        Path local = directory.resolve("schema.json");
        Files.writeString(local, "{\"type\":\"string\"}");
        for (String uri : new String[]{local.toUri().toString(), "classpath:canary.json", "#/$defs/value"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(
                    "{\"type\":\"object\",\"properties\":{\"value\":{\"$ref\":\"" + uri + "\"}}}", "{}"));
        }
    }

    @Test
    void rejectsNestedDialectReplacementAndUnknownDialect() {
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(
                "{\"properties\":{\"value\":{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}}}", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}", "{}"));
    }
}
