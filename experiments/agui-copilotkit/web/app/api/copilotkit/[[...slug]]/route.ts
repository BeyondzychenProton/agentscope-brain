import { HttpAgent } from "@ag-ui/client";
import { CopilotRuntime, createCopilotRuntimeHandler, InMemoryAgentRunner, type AgentRunnerRunRequest } from "@copilotkit/runtime/v2";
import { Observable } from "rxjs";

class TemporaryRunner extends InMemoryAgentRunner {
  run(request: AgentRunnerRunRequest) {
    const events = super.run(request);
    return new Observable<import("@ag-ui/client").BaseEvent>(subscriber => {
      let completed = false;
      const subscription = events.subscribe({
        next: event => subscriber.next(event),
        error: error => { completed = true; subscriber.error(error); },
        complete: () => { completed = true; subscriber.complete(); },
      });
      return () => {
        subscription.unsubscribe();
        if (!completed) void this.stop({ threadId: request.threadId, runId: request.input.runId });
      };
    });
  }
}

const runtime = new CopilotRuntime({
  runner: new TemporaryRunner(),
  agents: { default: new HttpAgent({ url: "http://127.0.0.1:8081/agui" }) }
});
const handler = createCopilotRuntimeHandler({ runtime, basePath: "/api/copilotkit" });

export { handler as GET, handler as POST, handler as PATCH, handler as DELETE };
