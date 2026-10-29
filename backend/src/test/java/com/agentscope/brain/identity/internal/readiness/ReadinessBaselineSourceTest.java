package com.agentscope.brain.identity.internal.readiness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** Catch DDL additions or edits whose exact startup metadata baseline was not regenerated. */
class ReadinessBaselineSourceTest {
    @Test
    void workbenchBaselineCoversCurrentPlatformAndVendorDdl() throws Exception {
        var root = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().getParent();
        var expected = new TreeMap<String, String>();
        for (var directory : new String[] {"db/schema", "db/vendor"}) {
            try (var files = Files.list(root.resolve(directory))) {
                for (var file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                    expected.put(root.relativize(file).toString().replace('\\', '/'),
                            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                }
            }
        }
        var recorded = new TreeMap<String, String>();
        try (var stream = new ClassPathResource("readiness/structure-workbench-v1.tsv").getInputStream()) {
            for (var line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.startsWith("# db/")) {
                    var parts = line.substring(2).split(" sha256=", 2);
                    recorded.put(parts[0], parts[1]);
                }
            }
        }
        assertThat(recorded)
                .as("Startup baseline must cover current DDL; regenerate with python db/tests/生成工作台结构期望.py in the repository root")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }
}
