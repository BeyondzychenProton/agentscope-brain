"use client";

import { Streamdown } from "streamdown";
import { visibleAssistantText } from "./assistant-content";

export function AssistantMarkdown({ content, className }: { content?: string; className?: string }) {
  return <Streamdown className={className}>{visibleAssistantText(content ?? "")}</Streamdown>;
}
