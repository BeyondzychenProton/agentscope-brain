export class ApiFailure extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

export type Employee = { employeeId: string; displayName: string; canStartRun: boolean;
  readOnly: boolean; unavailableReason: string | null };
export type Identity = { authStage: "PASSWORD_CHANGE_REQUIRED" | "READY"; userId: string;
  displayName: string; maskedPhone?: string; employees?: Employee[] };

async function request<T>(path: string, options: RequestInit): Promise<T> {
  let response: Response;
  try { response = await fetch(path, { ...options, credentials: "same-origin", cache: "no-store", redirect: "error" }); }
  catch { throw new ApiFailure(0, "NETWORK_UNAVAILABLE"); }
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { code?: string };
    throw new ApiFailure(response.status, body.code ?? "SERVICE_UNAVAILABLE");
  }
  return response.status === 204 ? undefined as T : await response.json() as T;
}

export function read<T>(path: string): Promise<T> { return request<T>(path, { method: "GET" }); }
export async function mutate<T>(method: "POST" | "PATCH", path: string, input?: unknown): Promise<T> {
  const csrf = await read<{ headerName: string; token: string }>("/api/auth/csrf");
  return request<T>(path, { method, headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token },
    body: input === undefined ? undefined : JSON.stringify(input) });
}
