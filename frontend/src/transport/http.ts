export class ApiFailure extends Error {
  constructor(readonly status: number, readonly code: string) { super(code) }
}

export async function read<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'GET' })
}

export async function write<T>(path: string, input?: unknown): Promise<T> {
  // A fresh token is required after login/logout rotates the authentication context.
  const csrf = await read<{ headerName: string; token: string }>('/api/auth/csrf')
  return request<T>(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: input === undefined ? undefined : JSON.stringify(input),
  })
}

async function request<T>(path: string, options: RequestInit): Promise<T> {
  let response: Response
  try { response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store', redirect: 'error' }) }
  catch { throw new ApiFailure(0, 'NETWORK_UNAVAILABLE') }
  if (!response.ok) {
    const error = await response.json().catch(() => ({ code: 'SERVICE_UNAVAILABLE' })) as { code?: string }
    throw new ApiFailure(response.status, error.code ?? 'SERVICE_UNAVAILABLE')
  }
  return response.status === 204 ? undefined as T : await response.json() as T
}
