package com.agentscope.brain.runtime.internal.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Loads OpenAI-compatible tool definitions for the release's meeting MCP tools. */
@Component
public class AgentToolCatalog {
    private static final Logger LOG = LoggerFactory.getLogger(AgentToolCatalog.class);
    static final Set<String> READ_ONLY = Set.of(
            "identity.resolve",
            "department.search",
            "calendar.find_slots",
            "calendar.get_freebusy",
            "calendar.verify",
            "meeting.list_mine",
            "room.search");
    /** Mutating tools run only after a one-time platform confirmation. */
    static final Set<String> MUTATING = Set.of(
            "room.reserve",
            "meeting.cancel");

    /**
     * SDK tool names replace dots with underscores because model function names must match
     * {@code [A-Za-z0-9_-]}. Interrupt metadata carries that registered form.
     */
    public static String catalogName(String registered) {
        return switch (registered) {
            case "room_reserve" -> "room.reserve";
            case "meeting_cancel" -> "meeting.cancel";
            default -> registered;
        };
    }

    public record Tool(String name, String description, Map<String, Object> parameters, boolean readOnly) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final Path catalogPath;

    public AgentToolCatalog(
            JdbcTemplate jdbc,
            @Value("${brain.bootstrap.reference-catalog:config/release-catalog.private.json}") String catalog) {
        this.jdbc = jdbc;
        this.catalogPath = catalog.startsWith("file:") ? Path.of(java.net.URI.create(catalog)) : Path.of(catalog);
    }

    public List<Tool> forRelease(String releaseId) {
        try {
            var rows = jdbc.queryForList("SELECT config_json FROM employee_release WHERE id=?", releaseId);
            if (rows.size() != 1) throw new IllegalStateException("RELEASE_TOOLS_UNAVAILABLE");
            Map<String, Object> config = json.readValue(String.valueOf(rows.get(0).get("config_json")), new TypeReference<>() { });
            Object toolsNode = config.get("tools");
            if (!(toolsNode instanceof List<?> listed)) return List.of();
            JsonNode catalog = readCatalog();
            List<Tool> out = new ArrayList<>();
            for (Object item : listed) {
                if (!(item instanceof Map<?, ?> ref)) continue;
                String name = String.valueOf(ref.get("toolName"));
                boolean readOnly = READ_ONLY.contains(name);
                if (!readOnly && !MUTATING.contains(name)) continue;
                String schemaRef = "contract/meeting-mcp/" + name + "/v1";
                JsonNode contract = catalog.path(schemaRef).path("content");
                Map<String, Object> parameters = Map.of("type", "object", "properties", Map.of());
                if (contract.path("inputSchema").isObject()) {
                    parameters = json.convertValue(contract.path("inputSchema"), new TypeReference<>() { });
                } else {
                    // Never silently publish an argument-less definition: the model would guess field names.
                    LOG.warn("MCP tool contract has no input schema; publishing an empty object schema tool={} ref={}",
                            name, schemaRef);
                }
                out.add(new Tool(name, description(name), parameters, readOnly));
            }
            return List.copyOf(out);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("RELEASE_TOOLS_UNAVAILABLE", failure);
        }
    }

    /** Only read-only tools are exposed to the ordinary loop, which has no confirmation path. */
    public List<Map<String, Object>> openaiTools(List<Tool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool tool : tools) {
            if (!tool.readOnly()) continue;
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.put("parameters", tool.parameters());
            out.add(Map.of("type", "function", "function", fn));
        }
        return out;
    }

    private JsonNode readCatalog() throws Exception {
        Path path = catalogPath;
        if (!path.isAbsolute()) path = Path.of("").toAbsolutePath().resolve(path);
        if (!Files.isRegularFile(path)) {
            Path alt = Path.of("config/release-catalog.private.json");
            if (Files.isRegularFile(alt)) path = alt;
            else throw new IllegalStateException("RELEASE_CATALOG_UNAVAILABLE");
        }
        return json.readTree(Files.readString(path));
    }

    private static String description(String name) {
        return switch (name) {
            case "identity.resolve" -> "按姓名片段、部门或岗位查询员工通讯录候选人；不能用手机号作为查询条件。";
            case "department.search" -> "查询部门与岗位组织树，可选中文名称关键词。";
            case "calendar.get_freebusy" -> "查询若干手机号在时间范围内的忙闲。";
            case "calendar.find_slots" -> "在共同空闲中查找可容纳指定时长的会议时段。";
            case "calendar.verify" -> "核对会议与参会人忙闲。";
            case "meeting.list_mine" -> "列出当前登录用户相关的会议。";
            case "room.search" -> "查询可预约会议室。";
            case "room.reserve" -> "为当前登录用户预约会议室。执行前平台会要求本人一次性确认；发起人不会自动加入参会人。";
            case "meeting.cancel" -> "取消当前登录用户本人发起的未开始会议。执行前平台会要求本人一次性确认。";
            default -> name;
        };
    }
}
