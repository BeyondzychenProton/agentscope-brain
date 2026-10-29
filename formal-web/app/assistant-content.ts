const LABELS: Record<string, string> = {
  "identity.resolve": "查找人员",
  "department.search": "查找部门",
  "calendar.find_slots": "查找空闲时段",
  "calendar.get_freebusy": "查询忙闲",
  "calendar.verify": "核对会议",
  "meeting.list_mine": "查询我的会议",
  "room.search": "查找会议室",
  "room.reserve": "预约会议室",
  "meeting.cancel": "取消会议",
};

const ALIASES = Object.fromEntries(Object.keys(LABELS).map(name => [name.replaceAll(".", "_"), name]));

export function toolLabel(name: string, args: Record<string, unknown>): string {
  const label = LABELS[ALIASES[name] ?? name] ?? (name || "工具");
  const subject = typeof args.name === "string" ? args.name.trim() : "";
  return subject ? `${label}：${subject}` : label;
}

/** Display-only cleanup for legacy stored prose. Never creates tool calls or execution status. */
export function visibleAssistantText(content: string): string {
  return content
    .replace(/<tool(?:_call)?>[\s\S]*?<\/tool(?:_call)?>/gi, "")
    .replace(/<tool(?:_call)?>[\s\S]*$/gi, "")
    .replace(/<[a-z_]*$/gi, fragment => ["<tool_call>", "<tool>"].some(tag => tag.startsWith(fragment.toLowerCase())) ? "" : fragment);
}
