export class ApiError extends Error {
  constructor(public code: string, message: string, public correlationId = '') { super(message) }
}

export async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const binary = body instanceof Blob
  let response: Response
  try {
    response = await fetch(`/api/v1/${path}`, {
      method, credentials: 'same-origin', cache: 'no-store',
      headers: method === 'GET' ? {} : { 'Content-Type': binary ? 'application/octet-stream' : 'application/json', 'X-W2L-Client': 'web' },
      body: method === 'GET' ? undefined : binary ? body : JSON.stringify(body ?? {}),
    })
  } catch { throw new ApiError('CONNECTION_FAILED', 'Connection failed') }
  const value = await response.json().catch(() => null)
  if (!response.ok || value === null) throw new ApiError(value?.code ?? 'CONNECTION_FAILED', value?.message ?? 'Connection failed', value?.correlationId)
  return value as T
}

export const submitTask = (kind: string, input: unknown) => request<{id: string}>('tasks', 'POST', { kind, input })
export const terminal = (state: string) => ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'REVALIDATION_REQUIRED'].includes(state)

export interface ServerProfile {
  id: string; name: string; host: string; port: number; username: string; version: number
  fingerprint: string | null; credentialConfigured: boolean
  observation: { connected?: boolean; observedAt?: string; operatingSystem?: string; architecture?: string }
}
export interface SourceProfile { id: string; name: string; state: string; kind: string; digest: string | null; byteCount: number }
export interface TaskSnapshot {
  id: string; kind: string; state: string; createdAt: string; updatedAt: string; errorCode: string | null
  result: Record<string, unknown> | null
  decision?: { id: string; kind: string; expiresAt: string; prompt: Record<string, unknown> }
}
export interface TaskEvent { sequence: number; kind: string; message: string; details: Record<string, unknown>; createdAt: string }
