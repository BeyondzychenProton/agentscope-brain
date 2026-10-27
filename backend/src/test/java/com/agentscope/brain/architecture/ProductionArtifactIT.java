package com.agentscope.brain.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 在 verify 阶段检查实际生产 JAR，而不是凭源码目录推断测试已隔离。 */
class ProductionArtifactIT {
    @Test
    void packagedApplicationContainsNoTestClassesResourcesOrLibraries() throws Exception {
        var applicationJar = Path.of(System.getProperty("applicationJar"));
        var testClassesDirectory = Path.of(System.getProperty("testClassesDirectory"));
        Set<String> testClasses;
        try (var files = Files.walk(testClassesDirectory)) {
            testClasses = new HashSet<>(files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".class"))
                    .map(file -> testClassesDirectory.relativize(file).toString().replace('\\', '/'))
                    .toList());
        }
        assertThat(testClasses).contains("com/agentscope/brain/interaction/internal/validation/ClosedDraft7Validator.class");
        try (var jar = new ZipFile(applicationJar.toFile())) {
            var entries = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(entries).contains("BOOT-INF/classes/com/agentscope/brain/AgentScopeBrainApplication.class");
            var applicationEntries = entries.stream().filter(name -> name.startsWith("BOOT-INF/classes/"))
                    .map(name -> name.substring("BOOT-INF/classes/".length())).toList();
            assertThat(applicationEntries).doesNotContainAnyElementsOf(testClasses);
            assertThat(applicationEntries).noneMatch(name -> name.matches(
                    ".*(?:^|/)(?:application-test\\.(?:yaml|yml|properties)|resource\\.txt|\\.env(?:\\..*)?)$")
                    || name.matches(".*(?:^|/)(?:tests?|fixtures?|probes?|mocks?|stubs?)/.*")
                    || name.contains(".private."));
            assertThat(entries.stream().filter(name -> name.startsWith("BOOT-INF/lib/")).toList())
                    .noneMatch(name -> name.matches("BOOT-INF/lib/(?:junit[^/]*|mockito[^/]*|assertj[^/]*|hamcrest[^/]*|h2-[^/]*|spring-(?:boot-)?test[^/]*)\\.jar"));
        }
    }
}
