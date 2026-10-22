import type { IncidentCategory } from '../api'
import { readCsrfToken } from '../shared/csrf'

export type ResponderQueueRow = {
  id: string
  title: string
  category: IncidentCategory
  status: 'SUBMITTED' | 'ASSIGNED'
  submittedAt: string
  queueEnteredAt: string
}

export type ResponderIncidentDetail = {
  id: string
  title: string
  description: string
  reporterLabel: string
  category: IncidentCategory
  status: 'SUBMITTED' | 'ASSIGNED'
  submittedAt: string
  queueEnteredAt: string
  resolutionHistory: ResponderResolutionHistory[]
  attachments: ResponderAttachment[]
  comments: ResponderIncidentComment[]
  lifecycleHistory: ResponderLifecycleEntry[]
}

export type ResponderAttachment = {
  id: string
  mediaType: 'image/png' | 'image/jpeg'
  sizeBytes: number
  createdAt: string
}

export type ResponderIncidentComment = {
  authorLabel: string
  authorRole: 'REPORTER' | 'RESPONDER' | 'ADMINISTRATOR'
  text: string
  createdAt: string
}

export type ResponderLifecycleEntry = {
  action: 'INCIDENT_SUBMITTED' | 'INCIDENT_CLAIMED' | 'INCIDENT_RESOLVED'
    | 'INCIDENT_REOPENED' | 'INCIDENT_HANDED_OFF' | 'INCIDENT_WITHDRAWN'
  occurredAt: string
  fromStatus: string | null
  toStatus: string | null
}

export type ResponderResolutionHistory = {
  cycleNumber: number
  resolvedAt: string
  remarks: string
}

export type ResponderQueuePage = {
  incidents: ResponderQueueRow[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  categoriesConfigured: boolean
}

export type ResponderQueueQuery = {
  search: string
  categories: IncidentCategory[]
  statuses: Array<ResponderQueueRow['status']>
  assignment: 'ALL' | 'ASSIGNED' | 'UNASSIGNED'
  page: number
  size: number
}

export type ResponderQueueErrorKind = 'session' | 'denied' | 'invalid' | 'conflict' | 'service'

export class ResponderQueueError extends Error {
  constructor(readonly kind: ResponderQueueErrorKind) {
    super(kind)
  }
}

export async function requestResponderAdvice(incidentId: string, signal: AbortSignal): Promise<string> {
  signal.throwIfAborted()
  const headers = new Headers({ Accept: 'application/json' })
  const csrfToken = readCsrfToken()
  if (csrfToken) headers.set('X-XSRF-TOKEN', csrfToken)
  const response = await fetch(`/api/v1/responder/incidents/${encodeURIComponent(incidentId)}/ai/advice`, {
    method: 'POST', credentials: 'same-origin', cache: 'no-store', headers, signal,
  })
  signal.throwIfAborted()
  if (response.ok) {
    const result = await response.json() as { advice?: unknown }
    if (typeof result.advice === 'string' && result.advice.length > 0 && result.advice.length <= 16384) {
      return result.advice
    }
    throw new ResponderQueueError('service')
  }
  const error = await response.json().catch(() => null) as { code?: unknown } | null
  if (response.status === 401 || error?.code === 'SECURITY_SESSION_EXPIRED') throw new ResponderQueueError('session')
  if (response.status === 403 || response.status === 404) throw new ResponderQueueError('denied')
  throw new ResponderQueueError('service')
}

export async function loadResponderQueue(
  query: ResponderQueueQuery,
  signal: AbortSignal,
): Promise<ResponderQueuePage> {
  signal.throwIfAborted()
  const headers = new Headers({ 'Content-Type': 'application/json', Accept: 'application/json' })
  const csrfToken = readCsrfToken()
  if (csrfToken) headers.set('X-XSRF-TOKEN', csrfToken)

  const response = await fetch('/api/v1/responder/incidents/query', {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers,
    body: JSON.stringify(query),
    signal,
  })
  signal.throwIfAborted()
  if (response.ok) return response.json() as Promise<ResponderQueuePage>

  const errorBody = await response.json().catch(() => null) as { code?: unknown } | null
  if (response.status === 401 || errorBody?.code === 'SECURITY_SESSION_EXPIRED') {
    throw new ResponderQueueError('session')
  }
  if (response.status === 404 || response.status === 403) throw new ResponderQueueError('denied')
  if (response.status === 400) throw new ResponderQueueError('invalid')
  throw new ResponderQueueError('service')
}

export async function loadResponderIncidentDetail(
  incidentId: string,
  signal: AbortSignal,
): Promise<ResponderIncidentDetail> {
  signal.throwIfAborted()
  const response = await fetch(`/api/v1/responder/incidents/${encodeURIComponent(incidentId)}`, {
    method: 'GET',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { Accept: 'application/json' },
    signal,
  })
  signal.throwIfAborted()
  if (response.ok) return response.json() as Promise<ResponderIncidentDetail>

  const errorBody = await response.json().catch(() => null) as { code?: unknown } | null
  if (response.status === 401 || errorBody?.code === 'SECURITY_SESSION_EXPIRED') {
    throw new ResponderQueueError('session')
  }
  if (response.status === 404 || response.status === 403) throw new ResponderQueueError('denied')
  throw new ResponderQueueError('service')
}

export async function claimResponderIncident(incidentId: string): Promise<void> {
  const headers = new Headers({ Accept: 'application/json' })
  const csrfToken = readCsrfToken()
  if (csrfToken) headers.set('X-XSRF-TOKEN', csrfToken)

  const response = await fetch(`/api/v1/responder/incidents/${encodeURIComponent(incidentId)}/claim`, {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers,
  })
  if (response.ok) return

  const errorBody = await response.json().catch(() => null) as { code?: unknown } | null
  if (response.status === 401 || errorBody?.code === 'SECURITY_SESSION_EXPIRED') {
    throw new ResponderQueueError('session')
  }
  if (response.status === 409) throw new ResponderQueueError('conflict')
  if (response.status === 404 || response.status === 403) throw new ResponderQueueError('denied')
  throw new ResponderQueueError('service')
}

export async function resolveResponderIncident(
  incidentId: string, requestId: string, remarks: string,
): Promise<number> {
  const headers = new Headers({ Accept: 'application/json', 'Content-Type': 'application/json' })
  const csrfToken = readCsrfToken()
  if (csrfToken) headers.set('X-XSRF-TOKEN', csrfToken)

  const response = await fetch(`/api/v1/responder/incidents/${encodeURIComponent(incidentId)}/resolve`, {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers,
    body: JSON.stringify({ requestId, remarks }),
  })
  if (response.ok) {
    const result = await response.json() as { cycleNumber?: unknown }
    if (typeof result.cycleNumber === 'number' && Number.isInteger(result.cycleNumber) && result.cycleNumber > 0) {
      return result.cycleNumber
    }
    throw new ResponderQueueError('service')
  }

  const errorBody = await response.json().catch(() => null) as { code?: unknown } | null
  if (response.status === 401 || errorBody?.code === 'SECURITY_SESSION_EXPIRED') {
    throw new ResponderQueueError('session')
  }
  if (response.status === 409) throw new ResponderQueueError('conflict')
  if (response.status === 404 || response.status === 403) throw new ResponderQueueError('denied')
  if (response.status === 400) throw new ResponderQueueError('invalid')
  throw new ResponderQueueError('service')
}
