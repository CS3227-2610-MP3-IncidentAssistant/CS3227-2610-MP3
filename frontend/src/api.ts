export type SessionState = {
  authenticated: boolean
  loginName?: string
  role?: string
  csrfHeader: string
  csrfToken: string
}

export type SloTarget = {
  category: string
  timeToClaimMinutes: number | null
  timeInProgressMinutes: number | null
  reopenRatePercent: number | null
  effectiveAt: string | null
}

export type IncidentRow = {
  id: string
  title: string
  category: string
  status: string
  createdAt: string
  submittedAt: string | null
  reporterLabel: string
  assigneeId: string | null
  assigneeLabel: string | null
  version: number
}

export type DashboardPage = {
  incidents: IncidentRow[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  sloTargets: SloTarget[]
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

export async function loadSession(signal?: AbortSignal): Promise<SessionState> {
  return requestJson<SessionState>('/api/v1/auth/session', { signal })
}

export async function administratorLogin(
  loginName: string,
  password: string,
  csrfToken: string,
): Promise<void> {
  const body = new URLSearchParams({ username: loginName, password, _csrf: csrfToken })
  const response = await fetch('/api/v1/auth/administrator/login', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
  })
  if (!response.ok) throw new ApiError(response.status, 'Sign-in failed')
}

export type RegistrationRole = 'REPORTER' | 'RESPONDER' | 'ADMINISTRATOR'

export async function registerAccount(
  loginName: string,
  password: string,
  passwordConfirmation: string,
  role: RegistrationRole,
  session: SessionState,
): Promise<void> {
  const response = await fetch('/api/v1/auth/register', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      [session.csrfHeader]: session.csrfToken,
    },
    body: JSON.stringify({ loginName, password, passwordConfirmation, role }),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: unknown } | null
    const message = typeof body?.message === 'string' ? body.message : 'The account could not be created. Try again.'
    throw new ApiError(response.status, message)
  }
}

export async function logout(session: SessionState): Promise<void> {
  const response = await fetch('/api/v1/auth/logout', {
    method: 'POST', credentials: 'same-origin',
    headers: { [session.csrfHeader]: session.csrfToken },
  })
  if (!response.ok) throw new ApiError(response.status, 'Sign-out failed')
}

export async function loadDashboard(search: string, page: number, signal: AbortSignal): Promise<DashboardPage> {
  const parameters = new URLSearchParams({ search: search.trim(), page: String(page), size: '25' })
  return requestJson<DashboardPage>(`/api/v1/admin/dashboard?${parameters}`, { signal })
}

async function requestJson<T>(url: string, init: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'same-origin', ...init })
  if (!response.ok) throw new ApiError(response.status, 'The requested data is unavailable')
  return response.json() as Promise<T>
}
