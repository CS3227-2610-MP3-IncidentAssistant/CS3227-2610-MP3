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
  constructor(
    readonly status: number,
    message: string,
    readonly fieldErrors?: Record<string, string>,
    readonly code?: string,
  ) {
    super(message)
  }
}

export function isSecuritySessionExpired(error: unknown): error is ApiError {
  return error instanceof ApiError
    && error.status === 403
    && error.code === 'SECURITY_SESSION_EXPIRED'
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
    const body = await response.json().catch(() => null) as { code?: unknown, message?: unknown } | null
    const message = typeof body?.message === 'string' ? body.message : 'The account could not be created. Try again.'
    const code = typeof body?.code === 'string' ? body.code : undefined
    throw new ApiError(response.status, message, undefined, code)
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

export type ReporterDescriptionDraft = Pick<IncidentSubmission, 'title' | 'category' | 'description'>

const aiMessages: Record<string, string> = {
  AI_RATE_LIMITED: 'Description assistance is busy. Try again later or submit your own description.',
  AI_TIMEOUT: 'Description assistance timed out. You can still submit your own description.',
  AI_UNAVAILABLE: 'Description assistance is unavailable. You can still submit your own description.',
  AI_REJECTED: 'Description assistance could not process this draft. Review the fields or submit your own description.',
  AI_INVALID_RESPONSE: 'Description assistance did not return a valid suggestion. Your draft is unchanged.',
  AI_OUTPUT_TOO_LARGE: 'Description assistance did not return a valid suggestion. Your draft is unchanged.',
  SECURITY_SESSION_EXPIRED: 'Your session has expired. Sign in again to use description assistance.',
}

export async function improveReporterDescription(
  draft: ReporterDescriptionDraft,
  session: SessionState,
  signal: AbortSignal,
): Promise<string> {
  const response = await fetch('/api/v1/reporter/ai/description', {
    method: 'POST', credentials: 'same-origin', signal,
    headers: { 'Content-Type': 'application/json', [session.csrfHeader]: session.csrfToken },
    body: JSON.stringify({ title: draft.title, category: draft.category, description: draft.description }),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { code?: unknown } | null
    const code = typeof body?.code === 'string' && Object.hasOwn(aiMessages, body.code) ? body.code : 'AI_UNAVAILABLE'
    throw new ApiError(response.status, aiMessages[code], undefined, code)
  }
  const body = await response.json().catch(() => null) as { description?: unknown } | null
  if (typeof body?.description !== 'string' || !body.description.trim()
    || body.description.length > 10000 || new TextEncoder().encode(body.description).length > 16384) {
    throw new ApiError(502, aiMessages.AI_INVALID_RESPONSE, undefined, 'AI_INVALID_RESPONSE')
  }
  return body.description
}
