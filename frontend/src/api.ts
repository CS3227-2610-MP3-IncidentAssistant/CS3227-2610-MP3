import { readCsrfToken } from './shared/csrf'
import type {
  IncidentAttachmentEntry,
  IncidentCommentEntry,
  IncidentResolutionEntry,
  IncidentSloProgress,
} from './shared/incident/incidentDetail'

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

export type SloTargetVersion = {
  id: string
  category: IncidentCategory
  timeToClaimMinutes: number
  timeInProgressMinutes: number
  reopenRatePercent: number
  effectiveAt: string
}

export type SloCategoryConfiguration = {
  category: IncidentCategory
  current: SloTargetVersion | null
  history: SloTargetVersion[]
}

export type SloConfiguration = {
  outcome: 'AVAILABLE'
  categories: SloCategoryConfiguration[]
}

export type StatisticsIdentityOption = {
  id: string
  displayName: string
}

export type AdministratorStatistics = {
  outcome: 'AVAILABLE'
  summary: {
    resolvedIncidentCount: number
    averageTimeToClaimMillis: number | null
    claimedCycleCount: number
    averageTimeInProgressMillis: number | null
    completedCycleCount: number
    reopenedIncidentCount: number
    reopenRate: number | null
    reopenEventCount: number
    administratorResolvedCount: number
  }
  responders: Array<{
    responderId: string
    displayName: string
    resolvedCycleCount: number
    averageTimeInProgressMillis: number | null
    reopenedCycleCount: number
    reopenRate: number | null
    administratorResolvedCount: number
  }>
  filterOptions: {
    responders: StatisticsIdentityOption[]
    reporters: StatisticsIdentityOption[]
  }
}

export type AdministratorStatisticsFilters = {
  categories: IncidentCategory[]
  responderId: string
  reporterId: string
  periodFrom: string
  periodThrough: string
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

export type AdministratorAccount = {
  id: string
  loginName: string
  role: 'REPORTER' | 'RESPONDER' | 'ADMINISTRATOR'
  status: 'ENABLED' | 'DISABLED'
  version: number
  categories: IncidentCategory[]
}

export type AdministratorAccountPage = {
  accounts: AdministratorAccount[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export async function loadAdministratorAccounts(
  page: number,
  signal?: AbortSignal,
): Promise<AdministratorAccountPage> {
  const parameters = new URLSearchParams({ page: String(page), size: '25' })
  return requestJson<AdministratorAccountPage>(`/api/v1/admin/accounts?${parameters}`, { signal })
}

export async function tombstoneAdministratorAccount(
  accountId: string,
  refreshSession: SessionRefresher,
): Promise<void> {
  await csrfRequest(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/tombstone`, {
    method: 'POST',
    credentials: 'same-origin',
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'The account could not be deleted. Refresh and try again.',
  })
}

export async function replaceResponderCategories(
  accountId: string,
  expectedVersion: number,
  categories: IncidentCategory[],
  refreshSession: SessionRefresher,
): Promise<void> {
  await csrfRequest(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/responder-categories`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ expectedVersion, categories }),
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'Responder categories could not be updated. Refresh and try again.',
  })
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

export async function loadSloConfiguration(signal?: AbortSignal): Promise<SloConfiguration> {
  return requestJson<SloConfiguration>('/api/v1/admin/slo', { signal })
}

export type AdministratorIncidentDetail = {
  id: string
  version: number
  title: string
  description: string
  category: IncidentCategory
  status: string
  anonymous: boolean
  reporterLabel: string
  assigneeLabel: string | null
  createdAt: string | null
  submittedAt: string | null
  withdrawnAt: string | null
  queueEnteredAt: string | null
  firstAssignedAt: string | null
  latestAssignedAt: string | null
  slo: IncidentSloProgress | null
  resolutionHistory: IncidentResolutionEntry[]
  comments: IncidentCommentEntry[]
  attachments: IncidentAttachmentEntry[]
  eligibleResponders: EligibleResponder[]
}

export type EligibleResponder = {
  id: string
  loginName: string
}

export async function loadAdministratorIncidentDetail(
  incidentId: string,
  signal?: AbortSignal,
): Promise<AdministratorIncidentDetail> {
  return requestJson<AdministratorIncidentDetail>(
    `/api/v1/admin/incidents/${encodeURIComponent(incidentId)}`,
    { signal, cache: 'no-store' },
  )
}

/**
 * The mutation helpers below send an `expectedVersion` the server rechecks
 * under lock, plus a caller-generated `requestId` the server uses as an
 * idempotency key. A repeated request with the same id adds no second record.
 */
export async function addAdministratorIncidentComment(
  incidentId: string,
  requestId: string,
  text: string,
  refreshSession: SessionRefresher,
): Promise<void> {
  await csrfRequest(`/api/v1/admin/incidents/${encodeURIComponent(incidentId)}/comments`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId, text }),
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'The comment could not be added. Refresh and try again.',
  })
}

export async function assignAdministratorIncident(
  incidentId: string,
  expectedVersion: number,
  responderId: string,
  refreshSession: SessionRefresher,
): Promise<void> {
  await csrfRequest(`/api/v1/admin/incidents/${encodeURIComponent(incidentId)}/assignment`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedVersion, responderId }),
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'The assignment could not be saved. Refresh and try again.',
  })
}

export async function unassignAdministratorIncident(
  incidentId: string,
  expectedVersion: number,
  refreshSession: SessionRefresher,
): Promise<void> {
  await csrfRequest(`/api/v1/admin/incidents/${encodeURIComponent(incidentId)}/unassign`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedVersion }),
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'The incident could not be unassigned. Refresh and try again.',
  })
}

export async function resolveAdministratorIncident(
  incidentId: string,
  expectedVersion: number,
  requestId: string,
  remarks: string,
  refreshSession: SessionRefresher,
): Promise<void> {
  await csrfRequest(`/api/v1/admin/incidents/${encodeURIComponent(incidentId)}/resolve`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedVersion, requestId, remarks }),
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'The incident could not be resolved. Refresh and try again.',
  })
}

export async function loadAdministratorStatistics(
  filters: AdministratorStatisticsFilters,
  signal?: AbortSignal,
): Promise<AdministratorStatistics> {
  const parameters = new URLSearchParams()
  filters.categories.forEach((category) => parameters.append('category', category))
  if (filters.responderId) parameters.set('responderId', filters.responderId)
  if (filters.reporterId) parameters.set('reporterId', filters.reporterId)
  if (filters.periodFrom) parameters.set('periodFrom', `${filters.periodFrom}T00:00:00.000Z`)
  if (filters.periodThrough) parameters.set('periodThrough', `${filters.periodThrough}T23:59:59.999Z`)
  const query = parameters.size > 0 ? `?${parameters}` : ''
  return requestJson<AdministratorStatistics>(`/api/v1/admin/statistics${query}`, { signal })
}

export async function saveSloTarget(
  category: IncidentCategory,
  target: {
    timeToClaimMinutes: number
    timeInProgressMinutes: number
    reopenRatePercent: number
    expectedVersionId: string | null
  },
  refreshSession: SessionRefresher,
): Promise<{ changed: boolean }> {
  const response = await csrfRequest(`/api/v1/admin/slo/${encodeURIComponent(category)}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(target),
  }, refreshSession, {
    authenticationRequired: true,
    fallbackMessage: 'The SLO target could not be saved. Refresh and try again.',
  })
  if (!response) throw new ApiError(401, 'Authentication is required')
  return response.json() as Promise<{ changed: boolean }>
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
    init.signal?.throwIfAborted()
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
  init.signal?.throwIfAborted()
  const headers = new Headers(init.headers)
  const token = readCsrfToken()
  if (token) headers.set('X-XSRF-TOKEN', token)
  return fetch(url, { ...init, credentials: 'same-origin', headers })
}

async function readErrorBody(response: Response): Promise<ErrorBody | null> {
  return response.json().catch(() => null) as Promise<ErrorBody | null>
}

function isSecuritySessionResponse(response: Response, body: ErrorBody | null): boolean {
  return response.status === 403 && body?.code === 'SECURITY_SESSION_EXPIRED'
}

export type ReporterDescriptionDraft = Pick<IncidentSubmission, 'title' | 'category' | 'description'>

const aiMessages: Record<string, string> = {
  AI_RATE_LIMITED: 'Description assistance is busy. Try again later or submit your own description.',
  AI_TIMEOUT: 'Description assistance timed out. You can still submit your own description.',
  AI_UNAVAILABLE: 'Description assistance is unavailable. You can still submit your own description.',
  AI_REJECTED: 'Description assistance could not process this draft. Review the fields or submit your own description.',
  AI_INVALID_RESPONSE: 'Description assistance did not return a valid suggestion. Your draft is unchanged.',
  AI_OUTPUT_TOO_LARGE: 'Description assistance did not return a valid suggestion. Your draft is unchanged.',
  UNAUTHENTICATED: 'Your session has expired. Sign in again to use description assistance.',
  SECURITY_SESSION_EXPIRED: 'Your session has expired. Sign in again to use description assistance.',
}

export async function improveReporterDescription(
  draft: ReporterDescriptionDraft,
  refreshSession: SessionRefresher,
  signal: AbortSignal,
): Promise<string> {
  let response: Response | null
  try {
    response = await csrfRequest('/api/v1/reporter/ai/description', {
      method: 'POST', signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: draft.title, category: draft.category, description: draft.description }),
    }, refreshSession, { authenticationRequired: true, fallbackMessage: aiMessages.AI_UNAVAILABLE })
  } catch (error) {
    if (!(error instanceof ApiError)) throw error
    const code = error.code && Object.hasOwn(aiMessages, error.code) ? error.code : 'AI_UNAVAILABLE'
    throw new ApiError(error.status, aiMessages[code], undefined, code)
  }
  if (!response) throw new ApiError(401, aiMessages.UNAUTHENTICATED, undefined, 'UNAUTHENTICATED')
  const body = await response.json().catch(() => null) as { description?: unknown } | null
  if (typeof body?.description !== 'string' || !body.description.trim()
    || body.description.length > 10000 || new TextEncoder().encode(body.description).length > 16384) {
    throw new ApiError(502, aiMessages.AI_INVALID_RESPONSE, undefined, 'AI_INVALID_RESPONSE')
  }
  return body.description
}
