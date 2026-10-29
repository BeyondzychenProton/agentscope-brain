"use client";

import { Streamdown } from "streamdown";
import { visibleAssistantText } from "./assistant-content";

export function AssistantMarkdown({ content, className }: { content?: string; className?: string }) {
  return <Streamdown className={["assistant-prose", className].filter(Boolean).join(" ")}>{visibleAssistantText(content ?? "")}</Streamdown>;
}
