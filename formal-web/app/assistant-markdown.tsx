"use client";

import { Streamdown } from "streamdown";
import { splitAssistantContent, toolLabel } from "./assistant-content";

export function AssistantMarkdown({ content, className }: { content?: string; className?: string }) {
  const source = content ?? "";
  const parts = splitAssistantContent(source);
  if (!parts.some(part => part.type === "tool")) return <Streamdown className={className}>{source}</Streamdown>;
  return <div className={className}>{parts.map((part, index) => part.type === "text"
    ? <Streamdown key={index}>{part.text}</Streamdown>
    : <p key={index} className="tool-call-card">{part.pending ? "正在调用工具" : toolLabel(part.name, part.arguments)}</p>)}</div>;
}
