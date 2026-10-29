"use client";

import { useEffect, useId, useRef, useState, type ComponentProps } from "react";
import { CopilotChatReasoningMessage } from "@copilotkit/react-core/v2";

type Props = ComponentProps<typeof CopilotChatReasoningMessage>;

/** Render provider reasoning verbatim, independently of answer Markdown and tool execution facts. */
export function ReasoningMessage({ message, messages = [], isRunning, isLatest }: Props) {
  const index = messages.findIndex(item => item.id === message.id);
  const answerStarted = index >= 0 && messages.slice(index + 1).some(item =>
    item.role === "assistant" && typeof item.content === "string" && item.content.length > 0);
  const streaming = Boolean(isRunning && (isLatest ?? messages.at(-1)?.id === message.id));
  const [open, setOpen] = useState(Boolean(isRunning && !answerStarted));
  const panelId = useId();
  const panel = useRef<HTMLPreElement>(null);
  const collapsedForAnswer = useRef(answerStarted);

  useEffect(() => {
    if (answerStarted && !collapsedForAnswer.current) {
      collapsedForAnswer.current = true;
      setOpen(false);
    }
  }, [answerStarted]);
  useEffect(() => {
    if (open && panel.current) panel.current.scrollTop = panel.current.scrollHeight;
  }, [message.content, open]);

  return <section className="reasoning-message" data-message-id={message.id} aria-label="模型思考过程">
    <button type="button" className="reasoning-toggle" aria-expanded={open} aria-controls={panelId}
      onClick={() => setOpen(value => !value)}>
      <span>{streaming ? "正在思考…" : "思考过程"}</span><span aria-hidden="true">{open ? "▾" : "▸"}</span>
    </button>
    <pre ref={panel} id={panelId} className="reasoning-content" hidden={!open}>{message.content}</pre>
  </section>;
}
