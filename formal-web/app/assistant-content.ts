export type AssistantSegment =
  | { type: "text"; text: string }
  | { type: "tool"; name: string; arguments: Record<string, unknown>; pending: boolean };

const LABELS: Record<string, string> = {
  "identity.resolve": "查找人员",
  "department.search": "查找部门",
  "calendar.find_slots": "查找空闲时段",
  "calendar.get_freebusy": "查询忙闲",
  "calendar.verify": "核对会议",
  "meeting.list_mine": "查询我的会议",
  "room.search": "查找会议室",
};

/** Separates leaked `<tool_call>` tags from the prose before and after them. */
export function splitAssistantContent(content: string): AssistantSegment[] {
  const parts: AssistantSegment[] = [];
  const closed = /<tool_call>\s*([\s\S]*?)\s*<\/tool_call>/gi;
  let cursor = 0;
  for (const match of content.matchAll(closed)) {
    const start = match.index ?? 0;
    pushText(parts, content.slice(cursor, start));
    parts.push(toolSegment(match[1] ?? "", false));
    cursor = start + match[0].length;
  }
  const rest = content.slice(cursor);
  const openAt = rest.search(/<tool_call>/i);
  if (openAt >= 0) {
    pushText(parts, rest.slice(0, openAt));
    const body = rest.slice(openAt).replace(/^<tool_call>\s*/i, "");
    const parsed = takeCall(body);
    if (parsed) {
      parts.push({ type: "tool", name: parsed.name, arguments: parsed.arguments, pending: false });
      pushText(parts, parsed.rest);
    } else {
      parts.push({ type: "tool", name: "", arguments: {}, pending: true });
    }
  } else {
    pushText(parts, rest);
  }
  return parts;
}

export function toolLabel(name: string, args: Record<string, unknown>): string {
  const dotted = name.replaceAll("_", ".");
  const label = LABELS[name] ?? LABELS[dotted] ?? (name || "工具");
  const subject = typeof args.name === "string" ? args.name.trim() : "";
  return subject ? `${label}：${subject}` : label;
}

function pushText(parts: AssistantSegment[], text: string) {
  if (text.trim()) parts.push({ type: "text", text });
}

function toolSegment(body: string, pending: boolean): AssistantSegment {
  const parsed = takeCall(body);
  if (!parsed) return { type: "tool", name: "", arguments: {}, pending };
  return { type: "tool", name: parsed.name, arguments: parsed.arguments, pending: false };
}

function takeCall(body: string): { name: string; arguments: Record<string, unknown>; rest: string } | null {
  const json = takeJsonObject(body);
  if (!json) return null;
  try {
    const value = JSON.parse(json.json) as { name?: unknown; function?: { name?: unknown }; arguments?: unknown; parameters?: unknown };
    const name = typeof value.name === "string" ? value.name
      : typeof value.function?.name === "string" ? value.function.name : "";
    if (!name.trim()) return null;
    return { name: name.trim(), arguments: objectArgs(value.arguments ?? value.parameters), rest: json.rest };
  } catch {
    return null;
  }
}

function objectArgs(value: unknown): Record<string, unknown> {
  if (typeof value === "string") {
    try {
      return objectArgs(JSON.parse(value));
    } catch {
      return {};
    }
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return value as Record<string, unknown>;
}

function takeJsonObject(body: string): { json: string; rest: string } | null {
  const start = body.indexOf("{");
  if (start < 0) return null;
  let depth = 0;
  let inString = false;
  let escape = false;
  for (let i = start; i < body.length; i++) {
    const ch = body[i];
    if (inString) {
      if (escape) escape = false;
      else if (ch === "\\") escape = true;
      else if (ch === "\"") inString = false;
      continue;
    }
    if (ch === "\"") inString = true;
    else if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return { json: body.slice(start, i + 1), rest: body.slice(i + 1) };
    }
  }
  return null;
}
