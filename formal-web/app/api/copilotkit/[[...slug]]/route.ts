import { HttpAgent, type BaseEvent } from "@ag-ui/client";
import { AgentRunner, CopilotRuntime, createCopilotRuntimeHandler,
  type AgentRunnerConnectRequest, type AgentRunnerIsRunningRequest,
  type AgentRunnerRunRequest, type AgentRunnerStopRequest } from "@copilotkit/runtime/v2";
import { Observable } from "rxjs";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const javaOrigin = process.env.BRAIN_JAVA_ORIGIN || "http://127.0.0.1:8080";
const runtimeSecret = process.env.BRAIN_COPILOT_RUNTIME_SECRET || "";

class PlatformRunner extends AgentRunner {
  constructor(private cookie: string, private csrfHeader: string, private csrfToken: string,
              private employeeId: string) { super(); }

  private headers(json = false) {
    const headers: Record<string, string> = { Cookie: this.cookie, "X-Brain-Runtime": runtimeSecret,
      [this.csrfHeader]: this.csrfToken };
    if (json) headers["Content-Type"] = "application/json";
    return headers;
  }
  private events(path: string, body?: unknown, missingThread?: string): Observable<BaseEvent> {
    return new Observable(subscriber => {
      const abort = new AbortController();
      void (async () => {
        try {
          const response = await fetch(`${javaOrigin}${path}`, { method: body ? "POST" : "GET",
            headers: this.headers(Boolean(body)), body: body ? JSON.stringify(body) : undefined,
            cache: "no-store", signal: abort.signal });
          if (response.status === 404 && missingThread) {
            const runId = crypto.randomUUID();
            subscriber.next({ type: "RUN_STARTED", threadId: missingThread, runId } as BaseEvent);
            subscriber.next({ type: "RUN_FINISHED", threadId: missingThread, runId } as BaseEvent);
            subscriber.complete(); return;
          }
          if (!response.ok || !response.body) {
            const detail = response.body ? (await response.text()).replace(/\s+/g, " ").slice(0, 400) : "";
            console.error(`Copilot runtime upstream failed status=${response.status} path=${path} body=${detail}`);
            throw new Error(`Thread service returned ${response.status}`);
          }
          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          let buffer = "";
          for (;;) {
            const part = await reader.read();
            if (part.done) break;
            buffer += decoder.decode(part.value, { stream: true }).replace(/\r\n/g, "\n");
            let end: number;
            while ((end = buffer.indexOf("\n\n")) >= 0) {
              const frame = buffer.slice(0, end); buffer = buffer.slice(end + 2);
              const data = frame.split("\n").filter(line => line.startsWith("data:"))
                .map(line => line.slice(5).trimStart()).join("\n");
              if (data) subscriber.next(JSON.parse(data) as BaseEvent);
            }
          }
          subscriber.complete();
        } catch (error) {
          if (!abort.signal.aborted) {
            console.error(`Copilot runtime stream failed path=${path}`, error instanceof Error ? error.stack ?? error.message : error);
            subscriber.error(error);
          }
        }
      })();
      // The Java run owns its subscription; browser disconnect only ends this observer.
      return () => abort.abort();
    });
  }
  run(request: AgentRunnerRunRequest): Observable<BaseEvent> {
    const last = request.input.messages.at(-1);
    const resume = (request.input.resume ?? []).map(entry => ({
      interruptId: entry.interruptId, status: entry.status, payload: entry.payload }));
    // A resume answers a pending confirmation; it never carries a new user text.
    const text = resume.length || !last || last.role !== "user" || typeof last.content !== "string"
      ? "" : last.content;
    if (request.input.tools.length || (!text && !resume.length)) {
      console.error(`Copilot runtime rejected unsupported run threadId=${request.threadId}`);
      return new Observable(subscriber => subscriber.error(new Error("Only text messages and interrupt resumes are supported")));
    }
    return this.events("/api/copilot/runtime/run", { threadId: request.threadId,
      runId: request.input.runId, employeeId: this.employeeId, text, resume });
  }
  connect(request: AgentRunnerConnectRequest): Observable<BaseEvent> {
    return this.events(`/api/copilot/runtime/connect/${encodeURIComponent(request.threadId)}`, undefined, request.threadId);
  }
  async isRunning(request: AgentRunnerIsRunningRequest): Promise<boolean> {
    const response = await fetch(`${javaOrigin}/api/copilot/threads/${encodeURIComponent(request.threadId)}`,
      { headers: this.headers(), cache: "no-store" });
    if (response.status === 404) return false;
    if (!response.ok) throw new Error("Thread service unavailable");
    return Boolean((await response.json() as { activeRunId: string | null }).activeRunId);
  }
  async stop(request: AgentRunnerStopRequest): Promise<boolean> {
    let runId = request.runId;
    if (!runId) {
      const response = await fetch(`${javaOrigin}/api/copilot/threads/${encodeURIComponent(request.threadId)}`,
        { headers: this.headers(), cache: "no-store" });
      if (!response.ok) return false;
      runId = (await response.json() as { activeRunId: string | null }).activeRunId || undefined;
    }
    if (!runId) return false;
    const response = await fetch(`${javaOrigin}/api/copilot/runtime/stop`, { method: "POST", headers: this.headers(true),
      body: JSON.stringify({ threadId: request.threadId, runId }), cache: "no-store" });
    if (!response.ok) throw new Error("Thread service unavailable");
    return (await response.json() as { stopped: boolean }).stopped;
  }
}

async function handle(request: Request): Promise<Response> {
  if (runtimeSecret.length < 32) return Response.json({ code: "RUNTIME_UNAVAILABLE" }, { status: 503 });
  const cookie = request.headers.get("cookie") || "";
  const me = await fetch(`${javaOrigin}/api/me`, { headers: { Cookie: cookie }, cache: "no-store" }).catch(() => null);
  if (!me || !me.ok) return Response.json({ code: "AUTHENTICATION_REQUIRED" }, { status: me?.status || 503 });
  const identity = await me.json() as { authStage: string; employees?: { employeeId: string }[] };
  if (identity.authStage !== "READY") return Response.json({ code: "PASSWORD_CHANGE_REQUIRED" }, { status: 403 });
  if (identity.employees?.length !== 1) return Response.json({ code: "EMPLOYEE_UNAVAILABLE" }, { status: 503 });
  const csrfResponse = await fetch(`${javaOrigin}/api/auth/csrf`, { headers: { Cookie: cookie }, cache: "no-store" });
  if (!csrfResponse.ok) return Response.json({ code: "CSRF_UNAVAILABLE" }, { status: 503 });
  const csrf = await csrfResponse.json() as { headerName: string; token: string };
  const received = request.headers.get(csrf.headerName) || "";
  if (request.method !== "GET") {
    if (!received) return Response.json({ code: "CSRF_REJECTED" }, { status: 403 });
    const check = await fetch(`${javaOrigin}/api/copilot/runtime/csrf-check`, { method: "POST", cache: "no-store",
      headers: { Cookie: cookie, "X-Brain-Runtime": runtimeSecret, [csrf.headerName]: received } });
    if (!check.ok) return Response.json({ code: "CSRF_REJECTED" }, { status: check.status });
  }
  const runner = new PlatformRunner(cookie, csrf.headerName, received || csrf.token, identity.employees[0].employeeId);
  if (new URL(request.url).pathname === "/api/copilotkit/control/stop") {
    if (request.method !== "POST") return Response.json({ code: "REQUEST_INVALID" }, { status: 405 });
    const input = await request.json().catch(() => null) as { threadId?: unknown; runId?: unknown } | null;
    if (!input || typeof input.threadId !== "string" || typeof input.runId !== "string")
      return Response.json({ code: "REQUEST_INVALID" }, { status: 400 });
    const stopped = await runner.stop({ threadId: input.threadId, runId: input.runId });
    return Response.json({ stopped });
  }
  const copilot = new CopilotRuntime({ runner, agents: { default: new HttpAgent({ url: `${javaOrigin}/api/copilot/runtime/run` }) } });
  return createCopilotRuntimeHandler({ runtime: copilot, basePath: "/api/copilotkit" })(request);
}

export { handle as GET, handle as POST, handle as PATCH, handle as DELETE };
