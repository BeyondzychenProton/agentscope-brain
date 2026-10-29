export const runtime = "nodejs";
export const dynamic = "force-dynamic";

async function proxy(request: Request): Promise<Response> {
  const url = new URL(request.url);
  if (url.pathname === "/api/copilot/runtime" || url.pathname.startsWith("/api/copilot/runtime/"))
    return Response.json({ code: "NOT_FOUND" }, { status: 404 });
  const origin = process.env.BRAIN_JAVA_ORIGIN || "http://127.0.0.1:8080";
  const headers = new Headers(request.headers);
  for (const name of ["host", "connection", "content-length", "x-brain-runtime"]) headers.delete(name);
  const upstream = await fetch(`${origin}${url.pathname}${url.search}`, {
    method: request.method, headers, cache: "no-store", redirect: "manual",
    body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer(),
  }).catch(() => null);
  if (!upstream) return Response.json({ code: "SERVICE_UNAVAILABLE" }, { status: 503 });
  const responseHeaders = new Headers();
  upstream.headers.forEach((value, name) => {
    if (!["set-cookie", "content-length", "content-encoding", "transfer-encoding", "connection"].includes(name.toLowerCase()))
      responseHeaders.append(name, value);
  });
  for (const cookie of upstream.headers.getSetCookie()) responseHeaders.append("set-cookie", cookie);
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

export { proxy as GET, proxy as POST, proxy as PATCH, proxy as DELETE };
