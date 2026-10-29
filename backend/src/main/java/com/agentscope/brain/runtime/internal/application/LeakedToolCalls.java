package com.agentscope.brain.runtime.internal.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits model text that leaked a `<tool_call>` tag into prose and read-only calls. */
final class LeakedToolCalls {
    record Call(String rawName, String name, Map<String, Object> arguments) { }
    record Split(String before, List<Call> calls, String after) {
        boolean needsContinuation() {
            return !calls.isEmpty() && after.isBlank();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CLOSED = Pattern.compile("(?i)<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>");
    private static final Pattern OPEN = Pattern.compile("(?i)<tool_call>\\s*([\\s\\S]*)$");

    private LeakedToolCalls() { }

    static Split split(String text) {
        String source = text == null ? "" : text;
        List<Call> calls = new ArrayList<>();
        Matcher closed = CLOSED.matcher(source);
        int first = -1;
        int lastEnd = 0;
        boolean sawTag = false;
        while (closed.find()) {
            sawTag = true;
            if (first < 0) first = closed.start();
            Call call = parse(closed.group(1));
            if (call != null) calls.add(call);
            lastEnd = closed.end();
        }
        if (!sawTag) {
            Matcher open = OPEN.matcher(source);
            if (open.find()) {
                Parsed body = parseBody(open.group(1));
                if (body != null && body.call != null) {
                    return new Split(source.substring(0, open.start()), List.of(body.call), body.trailing);
                }
            }
            return new Split(source, List.of(), "");
        }
        String before = source.substring(0, first);
        String after = source.substring(lastEnd);
        Matcher open = OPEN.matcher(after);
        if (open.find()) {
            Parsed body = parseBody(open.group(1));
            if (body != null && body.call != null) {
                calls.add(body.call);
                after = after.substring(0, open.start()) + body.trailing;
            }
        }
        return new Split(before, List.copyOf(calls), after);
    }

    static Map<String, Object> arguments(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isObject()) return Map.of();
            Map<String, Object> arguments = new LinkedHashMap<>();
            node.fieldNames().forEachRemaining(name -> {
                JsonNode value = node.get(name);
                if (value != null && !value.isNull())
                    arguments.put(name, JSON.convertValue(value, Object.class));
            });
            return java.util.Collections.unmodifiableMap(arguments);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String canonicalName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = raw.trim();
        if (ReadonlyToolCatalog.READ_ONLY.contains(name)) return name;
        String dotted = name.replace('_', '.');
        return ReadonlyToolCatalog.READ_ONLY.contains(dotted) ? dotted : null;
    }

    private static Call parse(String body) {
        Parsed parsed = parseBody(body);
        return parsed == null ? null : parsed.call;
    }

    private static Parsed parseBody(String body) {
        if (body == null) return null;
        int start = body.indexOf('{');
        if (start < 0) return null;
        int end = matchingObjectEnd(body, start);
        if (end < 0) return null;
        try {
            JsonNode node = JSON.readTree(body.substring(start, end + 1));
            if (!node.isObject()) return null;
            String rawName = text(node, "name");
            if (rawName == null && node.path("function").isObject()) rawName = text(node.path("function"), "name");
            if (rawName == null || rawName.isBlank()) return null;
            JsonNode args = node.get("arguments");
            if (args == null || args.isNull()) args = node.get("parameters");
            if (args != null && args.isTextual()) {
                try {
                    args = JSON.readTree(args.asText());
                } catch (Exception ignored) {
                    args = null;
                }
            }
            JsonNode argumentsNode = args;
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (argumentsNode != null && argumentsNode.isObject()) {
                var names = argumentsNode.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    JsonNode value = argumentsNode.get(name);
                    if (value != null && !value.isNull())
                        arguments.put(name, JSON.convertValue(value, Object.class));
                }
            }
            return new Parsed(new Call(rawName.trim(), canonicalName(rawName), java.util.Collections.unmodifiableMap(arguments)), body.substring(end + 1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int matchingObjectEnd(String body, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (ch == '\\') escape = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') inString = true;
            else if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private record Parsed(Call call, String trailing) { }
}
