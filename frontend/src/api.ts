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
  constructor(readonly status: number, message: string, readonly fieldErrors?: Record<string, string>) {
    super(message)
  }
}

export async function loadSession(signal?: AbortSignal): Promise<SessionState> {
  return requestJson<SessionState>('/api/v1/auth/session', { signal })
}

export async function roleLogin(
  loginName: string,
  password: string,
  portal: 'REPORTER' | 'ADMINISTRATOR',
  csrfToken: string,
): Promise<void> {
  const rolePath = portal === 'REPORTER' ? 'reporter' : 'administrator'
  const body = new URLSearchParams({ username: loginName, password, _csrf: csrfToken })
  const response = await fetch(`/api/v1/auth/${rolePath}/login`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!response.ok) throw new ApiError(response.status, 'Sign-in failed')
}

export async function registerAccount(
  loginName: string,
  password: string,
  passwordConfirmation: string,
  session: SessionState,
): Promise<void> {
  const response = await fetch('/api/v1/auth/reporter/register', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      [session.csrfHeader]: session.csrfToken,
    },
    body: JSON.stringify({ loginName, password, passwordConfirmation }),
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

export type ReporterIncident = {
  id: string
  title: string
  category: string
  status: string
  anonymous: boolean
  createdAt: string
  submittedAt: string | null
  assigneeLabel: string | null
  version: number
}

export type ReporterIncidentPage = {
  incidents: ReporterIncident[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export async function loadReporterIncidents(
  search: string,
  page: number,
  signal: AbortSignal,
): Promise<ReporterIncidentPage> {
  const parameters = new URLSearchParams({ search: search.trim(), page: String(page), size: '25' })
  return requestJson<ReporterIncidentPage>(`/api/v1/reporter/incidents?${parameters}`, { signal })
}

export type IncidentSubmission = {
  title: string
  description: string
  category: 'IT' | 'HUMAN_RELATIONS' | 'FACILITIES'
  anonymous: boolean
  submissionKey: string
}

export async function submitReporterIncident(
  submission: IncidentSubmission,
  session: SessionState,
): Promise<ReporterIncident> {
  const response = await fetch('/api/v1/reporter/incidents', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      [session.csrfHeader]: session.csrfToken,
    },
    body: JSON.stringify(submission),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null) as {
      message?: unknown
      fieldErrors?: unknown
    } | null
    const message = typeof body?.message === 'string'
      ? body.message
      : 'The incident could not be submitted. Try again.'
    const fieldErrors = isFieldErrors(body?.fieldErrors) ? body.fieldErrors : undefined
    throw new ApiError(response.status, message, fieldErrors)
  }
  return response.json() as Promise<ReporterIncident>
}

function isFieldErrors(value: unknown): value is Record<string, string> {
  return typeof value === 'object' && value !== null
    && Object.values(value).every((message) => typeof message === 'string')
}

async function requestJson<T>(url: string, init: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'same-origin', ...init })
  if (!response.ok) throw new ApiError(response.status, 'The requested data is unavailable')
  return response.json() as Promise<T>
}
