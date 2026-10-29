"use client";

import { CopilotChat, CopilotKitProvider, useAgent, useCopilotKit } from "@copilotkit/react-core/v2";
import { useEffect, useRef, useState } from "react";

function FailureObserver({ onFailure, stopRef }: { onFailure: () => void, stopRef: React.RefObject<(() => void) | null> }) {
  const { agent, isReady } = useAgent({ agentId: "default" });
  const { copilotkit } = useCopilotKit();
  useEffect(() => {
    if (!isReady) return;
    stopRef.current = () => { if (agent.isRunning) copilotkit.stopAgent({ agent }); };
    const subscription = agent.subscribe({ onRunErrorEvent: onFailure, onRunFailed: onFailure });
    return () => { stopRef.current = null; subscription.unsubscribe(); };
  }, [agent, isReady, onFailure, stopRef, copilotkit]);
  return null;
}

export default function Page() {
  const [threadId, setThreadId] = useState<string | null>(null);
  const [interrupted, setInterrupted] = useState(false);
  const [failed, setFailed] = useState(false);
  const stopRef = useRef<(() => void) | null>(null);
  useEffect(() => { setThreadId(crypto.randomUUID()); }, []);
  return (
    <main>
      <header>
        <h1>AgentScope 文本流验证</h1>
        <p>临时验证，刷新后重新开始。聊天记录不会保存到正式工作台。</p>
        <button type="button" onClick={() => { stopRef.current?.(); setInterrupted(false); setFailed(false); setThreadId(crypto.randomUUID()); }}>新建临时聊天</button>
      </header>
      <section className="chat" aria-label="临时聊天">
        {interrupted && <p role="status">已中断，回复未完成</p>}
        {failed && !interrupted && <p role="alert">生成失败，请新建临时聊天后重试</p>}
        <fieldset disabled={interrupted || failed} onClickCapture={(event) => {
          if ((event.target as HTMLElement).closest('button[aria-label="停止生成"]')) setInterrupted(true);
        }}>
        {threadId && <CopilotKitProvider key={threadId} runtimeUrl="/api/copilotkit" agentId="default">
          <FailureObserver onFailure={() => setFailed(true)} stopRef={stopRef} />
          <CopilotChat
            key={threadId}
            agentId="default"
            threadId={threadId}
            labels={{
              chatInputPlaceholder: "发送消息",
              chatInputToolbarSendButtonLabel: "发送",
              chatInputToolbarStopButtonLabel: "停止生成"
            }}
            input={{ textArea: { "aria-label": "发送消息" } }}
          />
        </CopilotKitProvider>}
        </fieldset>
      </section>
    </main>
  );
}
