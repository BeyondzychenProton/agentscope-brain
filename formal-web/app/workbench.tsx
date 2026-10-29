"use client";

import { CopilotChat, CopilotKitProvider, useAgent, useDefaultRenderTool } from "@copilotkit/react-core/v2";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiFailure, Identity, mutate, read } from "./api-client";
import { AssistantMarkdown } from "./assistant-markdown";
import { toolLabel } from "./assistant-content";
import { ReasoningMessage } from "./reasoning-message";

type Summary = { id: string; title: string; version: string; activeRunId: string | null; updatedAt: string };
type Thread = Summary & { employeeId: string; releaseId: string; unavailableReason: string | null;
  lastRunState: string | null; failureCode: string | null;
  messages: { id: string; role: "USER" | "ASSISTANT"; content: string; status: string }[] };
type Page = { items: Summary[]; hasMore: boolean; nextOffset: number };

function ToolCard({ name, parameters, status }: { name: string; parameters?: unknown; status?: string }) {
  const args = parameters && typeof parameters === "object" && !Array.isArray(parameters)
    ? parameters as Record<string, unknown> : {};
  const busy = status === "inProgress" || status === "executing";
  return <p className="tool-call-card">{busy ? "正在调用工具" : toolLabel(name, args)}</p>;
}

function Observer({ changed }: { changed: (running: boolean, failed?: boolean, runId?: string) => void }) {
  const { agent, isReady } = useAgent({ agentId: "default" });
  useDefaultRenderTool({ render: ToolCard }, []);
  useEffect(() => {
    if (!isReady) return;
    const subscription = agent.subscribe({ onRunStartedEvent: ({ event }) => changed(true, false, event.runId), onRunFinishedEvent: () => changed(false),
      onRunErrorEvent: () => changed(false, true), onRunFailed: () => changed(false, true) });
    return () => subscription.unsubscribe();
  }, [agent, isReady, changed]);
  return null;
}

export function Workbench({ identity, busy, onLogout, onAuthLost }: {
  identity: Identity; busy: boolean; onLogout: () => void; onAuthLost: () => void;
}) {
  const employee = identity.employees?.[0];
  const [threadId, setThreadId] = useState("");
  const [saved, setSaved] = useState(false);
  const [thread, setThread] = useState<Thread | null>(null);
  const [items, setItems] = useState<Summary[]>([]);
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [nextOffset, setNextOffset] = useState(-1);
  const [notice, setNotice] = useState("");
  const [rename, setRename] = useState<Summary | null>(null);
  const [newTitle, setNewTitle] = useState("");
  const generation = useRef(0);
  // The first connection must settle before accepting input, so CopilotChat cannot queue it behind a run.
  const submissionPending = useRef(true);
  const activeRun = useRef<string | null>(null);
  const currentThread = useRef("");

  useEffect(() => {
    const fromUrl = new URLSearchParams(window.location.search).get("threadId");
    currentThread.current = fromUrl || crypto.randomUUID();
    setThreadId(currentThread.current); setSaved(Boolean(fromUrl));
    const pop = () => { const id = new URLSearchParams(window.location.search).get("threadId");
      submissionPending.current = true;
      activeRun.current = null;
      currentThread.current = id || crypto.randomUUID();
      setThreadId(currentThread.current); setSaved(Boolean(id)); setThread(null); };
    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, []);

  const authFailure = useCallback((error: unknown) => {
    if (error instanceof ApiFailure && error.status === 401) onAuthLost();
    else setNotice("工作台暂时不可用，请稍后重试。");
  }, [onAuthLost]);

  const loadList = useCallback(async (offset = 0) => {
    if (!employee) return;
    const serial = ++generation.current;
    const params = new URLSearchParams({ employeeId: employee.employeeId, q: search, limit: "50", offset: String(offset) });
    try {
      const page = await read<Page>(`/api/copilot/threads?${params}`);
      if (serial !== generation.current) return;
      setItems(current => offset ? [...current, ...page.items] : page.items);
      setNextOffset(page.hasMore ? page.nextOffset : -1);
    } catch (error) { authFailure(error); }
  }, [employee, search, authFailure]);

  const refreshThread = useCallback(async () => {
    if (!threadId) return;
    try {
      const result = await read<Thread>(`/api/copilot/threads/${encodeURIComponent(threadId)}`);
      if (currentThread.current !== threadId) return;
      setThread(result); setSaved(true);
      const target = `/workbench?threadId=${encodeURIComponent(threadId)}`;
      if (window.location.pathname + window.location.search !== target) window.history.replaceState(null, "", target);
      await loadList();
    } catch (error) {
      if (currentThread.current !== threadId) return;
      if (error instanceof ApiFailure && error.status === 404 && !saved) return;
      if (error instanceof ApiFailure && error.status === 404) setNotice("无法读取此对话。");
      else authFailure(error);
    }
  }, [threadId, saved, loadList, authFailure]);

  useEffect(() => { void loadList(); }, [loadList]);
  useEffect(() => { if (saved) void refreshThread(); }, [threadId]);
  useEffect(() => {
    const check = () => { void read<Identity>("/api/me").catch(authFailure); };
    window.addEventListener("focus", check);
    const timer = window.setInterval(check, 15000);
    return () => { window.removeEventListener("focus", check); window.clearInterval(timer); };
  }, [authFailure]);

  function open(id: string | null) {
    const next = id || crypto.randomUUID();
    currentThread.current = next;
    window.history.pushState(null, "", id ? `/workbench?threadId=${encodeURIComponent(id)}` : "/workbench");
    submissionPending.current = true;
    activeRun.current = null;
    setThreadId(next); setSaved(Boolean(id)); setThread(null); setNotice("");
  }
  async function saveTitle(event: React.FormEvent) {
    event.preventDefault();
    if (!rename) return;
    const title = newTitle.trim();
    if (!title || [...title].length > 200 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(title)) {
      setNotice("标题不能为空、超过 200 字或包含不可保存字符。"); return;
    }
    try {
      await mutate<void>("PATCH", `/api/copilot/threads/${encodeURIComponent(rename.id)}/title`,
        { expectedVersion: rename.version, title });
      setRename(null); setNotice(""); await loadList();
      if (rename.id === threadId) await refreshThread();
    } catch (error) { authFailure(error); }
  }
  const changed = useCallback((running: boolean, failed = false, runId?: string) => {
    submissionPending.current = running;
    activeRun.current = running ? runId || null : null;
    if (failed) setNotice("回复未完成或发送未被接纳，请检查登录状态和员工授权后继续。");
    else if (running) setNotice("");
    void refreshThread();
  }, [refreshThread]);
  const csrfHeaders = useCallback(async () => {
    const csrf = await read<{ headerName: string; token: string }>("/api/auth/csrf");
    return { [csrf.headerName]: csrf.token };
  }, []);
  const readonly = Boolean(thread?.unavailableReason || (!saved && employee?.readOnly));
  const reason = thread?.unavailableReason || (!saved ? employee?.unavailableReason : null);
  const unfinished = thread?.lastRunState && !["RUNNING", "SUCCEEDED"].includes(thread.lastRunState);

  return <main className="app-shell"><aside className="sidebar"><div className="brand"><span className="brand-mark">H</span><span>海卓智慧大脑</span></div>
    <button className="new-chat" onClick={() => open(null)}>＋ 新建对话</button>
    <p className="employee-name">{employee?.displayName || "数字员工"}</p>
    <form onSubmit={event => { event.preventDefault(); setSearch(query.trim()); }}><label className="sr-only" htmlFor="thread-search">搜索对话标题</label>
      <input id="thread-search" placeholder="搜索对话标题" value={query} onChange={event => setQuery(event.target.value)} /></form>
    <p className="section-caption">最近对话</p><nav className="thread-list" aria-label="最近对话">
      {items.map(item => <div className={`thread-row ${saved && item.id === threadId ? "selected" : ""}`} key={item.id}>
        <button className="thread-link" onClick={() => open(item.id)} aria-current={saved && item.id === threadId ? "page" : undefined}>{item.title}</button>
        <button className="rename" aria-label={`重命名 ${item.title}`} onClick={() => { setRename(item); setNewTitle(item.title); }}>⋯</button>
      </div>)}{nextOffset >= 0 && <button onClick={() => void loadList(nextOffset)}>加载更多</button>}</nav>
    <div className="account"><span className="avatar">{identity.displayName.slice(0, 1)}</span><span>{identity.displayName}</span>
      <button disabled={busy} onClick={onLogout}>退出登录</button></div></aside>
    <section className="workbench"><header className="workbench-head"><div><strong>{thread?.title || "新的对话"}</strong>
      <small>{employee?.displayName || "数字员工"}</small></div></header><div className="workbench-content">
      {notice && <p className="alert" role="alert">{notice}</p>}
      {reason && <p className="readonly-note" role="status">{reason === "EMPLOYEE_USE_REVOKED"
        ? "数字员工使用授权已撤销，历史对话仍可查看。" : "当前员工发布版本不可用，历史对话仍可查看。"}</p>}
      {unfinished && <p className="status-line" role="status">回复未完成，你可以发送下一条消息继续。</p>}
      {!saved && <div className="welcome"><div className="welcome-mark">✦</div><h1>你好，我是{employee?.displayName || "数字员工"}</h1><p>有什么可以帮你？</p></div>}
      {threadId && <div className="chat-area"><CopilotKitProvider key={threadId} runtimeUrl="/api/copilotkit" agentId="default"
          headers={csrfHeaders} credentials="same-origin" enableInspector={false}>
        <Observer changed={changed} /><fieldset onKeyDownCapture={event => {
          if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
          if (!(event.target instanceof HTMLTextAreaElement) || !event.target.value.trim()) return;
          if (submissionPending.current || thread?.activeRunId || readonly) { event.preventDefault(); event.stopPropagation(); }
          else submissionPending.current = true;
        }} onClickCapture={event => {
          const stopButton = (event.target as HTMLElement).closest('button[aria-label="停止生成"]');
          if (stopButton) {
            const runId = activeRun.current || thread?.activeRunId;
            if (runId) {
              void mutate<{ stopped: boolean }>("POST", "/api/copilotkit/control/stop", { threadId, runId })
                .then(() => refreshThread()).catch(authFailure);
            } else setNotice("尚未取得当前运行状态，请稍后再试。");
            return;
          }
          const button = (event.target as HTMLElement).closest('button[aria-label="发送"]');
          if (!button) return;
          const input = event.currentTarget.querySelector("textarea");
          if (!input?.value.trim()) return;
          if (submissionPending.current || thread?.activeRunId || readonly) { event.preventDefault(); event.stopPropagation(); }
          else submissionPending.current = true;
        }}>
          <CopilotChat agentId="default" threadId={threadId} inspectorTools={false} welcomeScreen={false}
            labels={{ chatInputPlaceholder: "发送消息", chatInputToolbarSendButtonLabel: "发送", chatInputToolbarStopButtonLabel: "停止生成" }}
            messageView={{ messageFeedback: false, reasoningMessage: ReasoningMessage, assistantMessage: { toolbarVisible: false, markdownRenderer: AssistantMarkdown }, userMessage: { toolbar: () => null } }}
            input={{ textArea: { "aria-label": "发送消息", readOnly: readonly || Boolean(thread?.activeRunId) },
              addMenuButton: () => null, startTranscribeButton: () => null }} />
        </fieldset></CopilotKitProvider></div>}</div></section>
    {rename && <div className="dialog-backdrop"><form className="dialog" onSubmit={saveTitle}>
      <h2>重命名对话</h2><label htmlFor="thread-title">标题</label><input id="thread-title" value={newTitle} onChange={event => setNewTitle(event.target.value)} autoFocus />
      <div><button type="button" onClick={() => setRename(null)}>取消</button><button type="submit">保存</button></div></form></div>}
  </main>;
}
