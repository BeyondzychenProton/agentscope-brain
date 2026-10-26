package com.agentscope.brain.release;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/** 固定 SDK 的离线输入接缝；不读取真实配置或创建业务发布。 */
class ReleaseInputCompatibilityTest {
    @Test
    void openAiCompatibleModelCanBeConstructedWithoutSendingRequests() {
        var requests = new AtomicInteger();
        HttpTransport deniedTransport = new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) {
                requests.incrementAndGet();
                throw new AssertionError("Offline construction must not send HTTP");
            }

            @Override
            public Flux<String> stream(HttpRequest request) {
                requests.incrementAndGet();
                return Flux.error(new AssertionError("Offline construction must not stream HTTP"));
            }

            @Override
            public void close() {
            }
        };
        var model = OpenAIChatModel.builder()
                .apiKey("synthetic-not-a-real-api-key")
                .baseUrl("http://127.0.0.1:1/compatible-mode/v1")
                .modelName("qwen3.8-max")
                .httpTransport(deniedTransport)
                .build();
        assertThat(model.getModelName()).isEqualTo("qwen3.8-max");
        assertThat(requests).hasValue(0);
    }

    @Test
    void fixedSkillMetadataAndResourcesLoadThroughReadOnlySdkRepository(@TempDir Path root) throws Exception {
        var directory = Files.createDirectories(root.resolve("meeting-booking/references"));
        var entryPath = directory.getParent().resolve("SKILL.md");
        var resourcePath = directory.resolve("room-directory.md");
        var entry = "---\r\nname: meeting-booking\r\nversion: 1.1.0\r\ndescription: Synthetic meeting skill\r\n"
                + "metadata:\r\n  labels: [meeting, readonly]\r\n---\r\n# Fixed instructions\r\nRead references/room-directory.md.\r\n";
        var resource = "# Synthetic reference\r\nKeep these bytes and line endings.\r\n";
        Files.writeString(entryPath, entry, StandardCharsets.UTF_8);
        Files.writeString(resourcePath, resource, StandardCharsets.UTF_8);
        var parsed = MarkdownSkillParser.parse(entry);
        try (var repository = new FileSystemSkillRepository(root, false)) {
            assertThat(repository.isWriteable()).isFalse();
            assertThat(repository.getAllSkillNames()).containsExactly("meeting-booking");
            var skill = repository.getSkill("meeting-booking");
            assertThat(skill.getMetadata()).isEqualTo(parsed.getMetadata());
            assertThat(skill.getMetadataValue("version")).isEqualTo("1.1.0");
            assertThat(skill.getSkillContent()).isEqualTo(parsed.getContent());
            assertThat(skill.getResource("references/room-directory.md")).isEqualTo(resource);
            assertThat(repository.save(List.of(skill), true)).isFalse();
            assertThat(repository.delete("meeting-booking")).isFalse();
        }
        assertThat(Files.readString(entryPath, StandardCharsets.UTF_8)).isEqualTo(entry);
        assertThat(Files.readString(resourcePath, StandardCharsets.UTF_8)).isEqualTo(resource);
    }
}
