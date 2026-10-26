package com.agentscope.brain.release.internal.application;

import com.agentscope.brain.release.api.ReleaseReadiness;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PresetReleaseReadiness implements ReleaseReadiness {
    private final JdbcTemplate jdbc;
    private final ResourceLoader loader;
    private final Environment environment;
    private final String catalogLocation;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    public PresetReleaseReadiness(JdbcTemplate jdbc, ResourceLoader loader, Environment environment,
            @Value("${brain.bootstrap.reference-catalog:file:config/release-catalog.private.json}") String catalogLocation) {
        this.jdbc = jdbc; this.loader = loader; this.environment = environment; this.catalogLocation = catalogLocation;
    }

    @Override @Transactional(readOnly = true)
    public void verify(String userId) {
        try {
            var batch = one("SELECT * FROM bootstrap_batch WHERE bootstrap_scope='formal-web-phase1'");
            require("APPLIED".equals(batch.get("status")), "BOOTSTRAP_NOT_APPLIED");
            require(((Number) batch.get("manifest_version")).intValue() == 1, "BOOTSTRAP_REVISION");
            var manifest = parse(batch.get("manifest_json"));
            require(Arrays.equals(hash(canonical(manifest)), (byte[]) batch.get("manifest_sha256")), "BOOTSTRAP_MANIFEST_DIGEST");
            var identity = map(manifest.get("identityRefs"));
            require(userId.equals(identity.get("userId")), "BOOTSTRAP_USER_REFERENCE");
            var result = parse(batch.get("result_summary"));
            require(userId.equals(result.get("userId")), "BOOTSTRAP_USER_REFERENCE");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM user_credential WHERE id=? AND user_id=?", Long.class,
                    identity.get("credentialId"), userId) == 1, "BOOTSTRAP_CREDENTIAL_REFERENCE");
            // Current phone may legitimately change; the original reservation must retain its trusted source.
            require(jdbc.queryForObject("SELECT COUNT(*) FROM phone_reservation WHERE id=? AND owner_user_id=? AND ownership_source_ref<>''",
                    Long.class, identity.get("phoneId"), userId) == 1, "BOOTSTRAP_PHONE_REFERENCE");
            var employee = one("SELECT * FROM digital_employee WHERE id=?", result.get("employeeId"));
            var grant = one("SELECT * FROM employee_use_grant WHERE id=?", result.get("grantId"));
            require(userId.equals(String.valueOf(grant.get("user_id"))) && employee.get("id").equals(grant.get("employee_id")), "PRESET_GRANT_REFERENCE");
            // DISABLED/REVOKED are valid policy states; never repair or regrant at startup.
            var release = one("SELECT * FROM employee_release WHERE id=?", employee.get("current_release_id"));
            require(employee.get("id").equals(release.get("employee_id")), "PRESET_RELEASE_REFERENCE");
            var config = parse(release.get("config_json"));
            require(Arrays.equals(hash(canonical(config)), (byte[]) release.get("manifest_sha256")), "RELEASE_MANIFEST_DIGEST");
            var effective = parse(release.get("config_json"));
            for (var skill : list(effective.get("skills"))) { map(skill).remove("snapshotId"); }
            require(Arrays.equals(hash(canonical(effective)), (byte[]) release.get("config_sha256")), "RELEASE_CONFIG_DIGEST");
            Map<String, Object> catalog;
            try (var input = loader.getResource(catalogLocation).getInputStream()) {
                catalog = json.readValue(input, new TypeReference<Map<String, Object>>() { });
            }
            for (var entry : catalog.values()) {
                var item = map(entry);
                require(hex(hash(canonical(item.get("content")))).equals(item.get("sha256")), "REFERENCE_CATALOG_DIGEST");
            }
            var bindings = new ArrayList<String>();
            for (var item : list(config.get("tools"))) {
                var ref = map(item);
                var binding = one("SELECT * FROM tool_binding_revision WHERE tool_binding_version=?", ref.get("toolBindingVersion"));
                var content = parse(binding.get("binding_json"));
                var digest = hash(canonical(Map.of("schemaVersion", binding.get("binding_schema_version"), "binding", content)));
                require(Arrays.equals(digest, (byte[]) binding.get("binding_sha256")) && hex(digest).equals(ref.get("bindingSha256"))
                        && ref.get("providerKey").equals(binding.get("provider_key")) && ref.get("toolName").equals(binding.get("tool_name")), "BINDING_DIGEST_OR_REFERENCE");
                for (var entry : content.entrySet()) {
                    if (entry.getKey().endsWith("Ref")) { require(catalog.containsKey(entry.getValue()), "BINDING_CONFIG_REFERENCE"); }
                }
                bindings.add(hex(digest));
            }
            var dependencies = new TreeMap<String, Object>();
            for (var key : List.of("model", "workspace", "memory")) { dependencies.put(key, config.get(key)); }
            dependencies.put("tools", bindings);
            if (!list(config.getOrDefault("storageProfiles", List.of())).isEmpty()) { dependencies.put("storageProfiles", config.get("storageProfiles")); }
            if (config.containsKey("images")) { dependencies.put("images", config.get("images")); }
            require(Arrays.equals(hash(canonical(dependencies)), (byte[]) release.get("dependency_sha256")), "RELEASE_DEPENDENCY_DIGEST");
            var skills = list(config.get("skills"));
            require(jdbc.queryForObject("SELECT COUNT(*) FROM employee_release_skill WHERE employee_release_id=?", Long.class, release.get("id")) == skills.size(), "SKILL_REFERENCE_COUNT");
            for (var item : skills) { verifySkill(map(item), release.get("id")); }
            verifyReferences(config, catalog);
        } catch (InvalidRelease failure) { throw failure; }
        catch (Exception failure) { throw new InvalidRelease("PRESET_INPUT_UNAVAILABLE:" + failure.getClass().getSimpleName()); }
    }

    private void verifyReferences(Map<String, Object> config, Map<String, Object> catalog) {
        for (var key : List.of("model", "workspace", "memory")) {
            var item = map(config.get(key));
            var ref = item.get(key.equals("model") ? "configRef" : "policyRef");
            require(ref != null && catalog.containsKey(ref), "RELEASE_CONFIG_REFERENCE");
        }
        for (var profile : list(config.getOrDefault("storageProfiles", List.of()))) {
            var ref = map(profile);
            require(catalog.containsKey(ref.get("id")) && map(catalog.get(ref.get("id"))).get("sha256").equals(ref.get("sha256")), "STORAGE_REFERENCE");
        }
        // Resolve every declared secret reference locally. This does not contact the service.
        for (var entry : catalog.values()) {
            var item = map(entry);
            if ("credential-reference".equals(item.get("kind"))) {
                var path = String.valueOf(map(item.get("content")).get("configurationPath"));
                if (path.equals("brain.resources.model.api-key")) { requireConfigured(path); }
                else if (path.equals("brain.resources.meeting-mcp")) {
                    requireConfigured(path + ".app-key"); requireConfigured(path + ".secret");
                } else if (path.equals("brain.resources.minio")) {
                    requireConfigured(path + ".access-key"); requireConfigured(path + ".secret-key");
                } else { throw new InvalidRelease("UNKNOWN_SECRET_REFERENCE"); }
            }
        }
        var model = map(map(catalog.get(map(config.get("model")).get("configRef"))).get("content"));
        require(Objects.equals(model.get("modelId"), environment.getProperty("brain.resources.model.model")), "MODEL_CONFIG_MISMATCH");
        var endpoint = map(map(catalog.get(model.get("endpointRef"))).get("content"));
        require(Objects.equals(endpoint.get("baseUrl"), environment.getProperty("brain.resources.model.base-url")), "MODEL_ENDPOINT_MISMATCH");
        for (var entry : catalog.values()) {
            var item = map(entry); var content = map(item.get("content"));
            if ("mcp-target".equals(item.get("kind"))) {
                require(Objects.equals(content.get("endpoint"), environment.getProperty("brain.resources.meeting-mcp.endpoint")), "MCP_CONFIG_MISMATCH");
            }
            if ("storage-profile".equals(item.get("kind"))) {
                for (var pair : Map.of("endpoint", "endpoint", "bucket", "bucket", "objectPrefix", "object-prefix").entrySet()) {
                    require(Objects.equals(content.get(pair.getKey()), environment.getProperty("brain.resources.minio." + pair.getValue())), "STORAGE_CONFIG_MISMATCH");
                }
            }
        }
    }

    private void requireConfigured(String path) { require(!environment.getProperty(path, "").isBlank(), "SECRET_REFERENCE_MISSING:" + path); }

    private void verifySkill(Map<String, Object> ref, Object releaseId) throws Exception {
        var skill = one("SELECT * FROM employee_release_skill WHERE id=?", ref.get("snapshotId"));
        require(releaseId.equals(skill.get("employee_release_id")) && ref.get("name").equals(skill.get("skill_name")), "SKILL_OWNER_REFERENCE");
        var resources = jdbc.queryForList("SELECT * FROM employee_release_skill_resource WHERE release_skill_id=? ORDER BY resource_path COLLATE utf8mb4_bin", skill.get("id"));
        require(((Number) skill.get("resource_count")).intValue() == resources.size(), "SKILL_RESOURCE_COUNT");
        var bytes = new ByteArrayOutputStream(); var frame = new DataOutputStream(bytes);
        framed(frame, "agentscope-skill-package-v1".getBytes(StandardCharsets.UTF_8));
        var markdown = String.valueOf(skill.get("skill_markdown"));
        framed(frame, markdown.getBytes(StandardCharsets.UTF_8));
        var metadata = parse(skill.get("metadata_json"));
        // SDK parses the actual frontmatter, so stored metadata cannot silently drift from the entry.
        var parsed = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()))
                .load(markdown.split("---\\r?\\n", 3)[1]);
        require(Arrays.equals(canonical(parsed), canonical(metadata)), "SKILL_METADATA_MISMATCH");
        framed(frame, canonical(metadata));
        for (var resource : resources) {
            var text = String.valueOf(resource.get("resource_content")).getBytes(StandardCharsets.UTF_8);
            var digest = hash(text);
            require(Arrays.equals(digest, (byte[]) resource.get("content_sha256")), "SKILL_RESOURCE_DIGEST");
            framed(frame, String.valueOf(resource.get("resource_path")).getBytes(StandardCharsets.UTF_8));
            frame.writeLong(text.length); framed(frame, digest);
        }
        var digest = hash(bytes.toByteArray());
        require(Arrays.equals(digest, (byte[]) skill.get("package_sha256")) && hex(digest).equals(ref.get("packageSha256")), "SKILL_PACKAGE_DIGEST");
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args); require(rows.size() == 1, "PRESET_REFERENCE_MISSING"); return rows.get(0);
    }
    private Map<String, Object> parse(Object input) throws Exception { return json.readValue(String.valueOf(input), new TypeReference<Map<String, Object>>() { }); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object input) { return (Map<String, Object>) input; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object input) { return (List<Object>) input; }
    private byte[] canonical(Object input) {
        try { return json.writeValueAsBytes(sorted(input)); } catch (Exception failure) { throw new InvalidRelease("INVALID_CANONICAL_JSON"); }
    }
    private Object sorted(Object input) {
        if (input instanceof Map<?, ?> value) {
            var output = new TreeMap<String, Object>(); value.forEach((k, v) -> output.put(String.valueOf(k), sorted(v))); return output;
        }
        if (input instanceof List<?> value) { return value.stream().map(this::sorted).toList(); }
        if (input instanceof BigDecimal value) { return value.stripTrailingZeros(); }
        return input;
    }
    private static byte[] hash(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static void framed(DataOutputStream output, byte[] bytes) throws Exception { output.writeLong(bytes.length); output.write(bytes); }
    private static void require(boolean valid, String code) { if (!valid) { throw new InvalidRelease(code); } }
}
