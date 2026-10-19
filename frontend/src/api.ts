export type SessionState = {
  authenticated: boolean
  loginName?: string
  role?: string
  responderCategories?: IncidentCategory[]
}

export type SessionRefresher = () => Promise<SessionState>
export type IncidentCategory = 'IT' | 'HUMAN_RELATIONS' | 'FACILITIES'
export type PromotionStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export type PromotionRequestRecord = {
  requestId: string
  requesterLabel?: string
  requestedCategories: IncidentCategory[]
  approvedCategories: IncidentCategory[] | null
  comments: string | null
  requestedAt: string
  status: PromotionStatus
  decidedAt: string | null
}

export type PromotionRequestHistory = { requests: PromotionRequestRecord[] }
export type PromotionRequestPage = PromotionRequestHistory & {
  page: number
  size: number
  totalElements: number
  totalPages: number
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

export async function loadSession(signal?: AbortSignal): Promise<SessionState> {
  return requestJson<SessionState>('/api/v1/auth/session', { signal })
}

export async function roleLogin(
  loginName: string,
  password: string,
  portal: 'REPORTER' | 'RESPONDER' | 'ADMINISTRATOR',
  refreshSession: SessionRefresher,
): Promise<void> {
  const rolePath = portal.toLowerCase()
  const body = new URLSearchParams({ username: loginName, password })
  await csrfRequest(`/api/v1/auth/${rolePath}/login`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  }, refreshSession, { fallbackMessage: 'Sign-in failed' })
}

export async function loadPromotionHistory(signal?: AbortSignal): Promise<PromotionRequestHistory> {
  return requestJson<PromotionRequestHistory>('/api/v1/reporter/promotion-request', { signal })
}

export async function submitPromotionRequest(
  requestedCategories: IncidentCategory[],
  comments: string,
  refreshSession: SessionRefresher,
): Promise<PromotionRequestRecord> {
  const response = await csrfRequest('/api/v1/reporter/promotion-request', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestedCategories, comments: comments || null }),
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'The promotion request could not be submitted. Try again.',
  })
  if (!response) throw new ApiError(401, 'Authentication is required')
  return response.json() as Promise<PromotionRequestRecord>
}

export async function loadPromotionRequests(
  page: number,
  signal?: AbortSignal,
): Promise<PromotionRequestPage> {
  const parameters = new URLSearchParams({ page: String(page), size: '25' })
  return requestJson<PromotionRequestPage>(`/api/v1/admin/promotion-requests?${parameters}`, { signal })
}

export async function decidePromotionRequest(
  requestId: string,
  decision: 'APPROVE' | 'REJECT',
  approvedCategories: IncidentCategory[],
  refreshSession: SessionRefresher,
): Promise<PromotionRequestRecord> {
  const response = await csrfRequest(
    `/api/v1/admin/promotion-requests/${encodeURIComponent(requestId)}/decision`,
    {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        decision,
        ...(decision === 'APPROVE' ? { approvedCategories } : {}),
      }),
    },
    refreshSession,
    {
      authenticationRequired: true,
      fallbackMessage: 'The promotion decision could not be saved. Refresh and try again.',
    },
  )
  if (!response) throw new ApiError(401, 'Authentication is required')
  return response.json() as Promise<PromotionRequestRecord>
}

export async function registerAccount(
  loginName: string,
  password: string,
  passwordConfirmation: string,
  refreshSession: SessionRefresher,
): Promise<void> {
  await csrfRequest('/api/v1/auth/reporter/register', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ loginName, password, passwordConfirmation }),
  }, refreshSession, { fallbackMessage: 'The account could not be created. Try again.' })
}

export async function logout(refreshSession: SessionRefresher): Promise<void> {
  await csrfRequest('/api/v1/auth/logout', {
    method: 'POST', credentials: 'same-origin',
  }, refreshSession, {
    anonymousRefreshCompletesRequest: true,
    fallbackMessage: 'Sign-out failed',
  })
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
  category: IncidentCategory
  anonymous: boolean
  submissionKey: string
}

export async function submitReporterIncident(
  submission: IncidentSubmission,
  refreshSession: SessionRefresher,
): Promise<ReporterIncident> {
  const response = await csrfRequest('/api/v1/reporter/incidents', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(submission),
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'The incident could not be submitted. Try again.',
  })
  if (!response) throw new ApiError(401, 'Authentication is required')
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

type CsrfRequestOptions = {
  authenticationRequired?: boolean
  anonymousRefreshCompletesRequest?: boolean
  fallbackMessage: string
}

type ErrorBody = {
  code?: unknown
  fieldErrors?: unknown
  message?: unknown
}

async function csrfRequest(
  url: string,
  init: RequestInit,
  refreshSession: SessionRefresher,
  options: CsrfRequestOptions,
): Promise<Response | null> {
  let response = await sendCsrfRequest(url, init)
  let errorBody = response.ok ? null : await readErrorBody(response)

  if (isSecuritySessionResponse(response, errorBody)) {
    const refreshedSession = await refreshSession()
    if (!refreshedSession.authenticated) {
      if (options.anonymousRefreshCompletesRequest) return null
      if (options.authenticationRequired) {
        throw new ApiError(401, 'Authentication is required', undefined, 'UNAUTHENTICATED')
      }
    }
    response = await sendCsrfRequest(url, init)
    errorBody = response.ok ? null : await readErrorBody(response)
  }

  if (!response.ok) {
    const message = typeof errorBody?.message === 'string'
      ? errorBody.message
      : options.fallbackMessage
    const code = typeof errorBody?.code === 'string' ? errorBody.code : undefined
    const fieldErrors = isFieldErrors(errorBody?.fieldErrors) ? errorBody.fieldErrors : undefined
    throw new ApiError(response.status, message, fieldErrors, code)
  }
  return response
}

function sendCsrfRequest(url: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers)
  const token = readCookie('XSRF-TOKEN')
  if (token) headers.set('X-XSRF-TOKEN', token)
  return fetch(url, { ...init, credentials: 'same-origin', headers })
}

function readCookie(name: string): string | undefined {
  const prefix = `${encodeURIComponent(name)}=`
  const cookie = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
  return cookie ? decodeURIComponent(cookie.substring(prefix.length)) : undefined
}

async function readErrorBody(response: Response): Promise<ErrorBody | null> {
  return response.json().catch(() => null) as Promise<ErrorBody | null>
}

function isSecuritySessionResponse(response: Response, body: ErrorBody | null): boolean {
  return response.status === 403 && body?.code === 'SECURITY_SESSION_EXPIRED'
}
