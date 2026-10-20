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

export type ResponderQueueErrorKind = 'session' | 'denied' | 'invalid' | 'service'

export class ResponderQueueError extends Error {
  constructor(readonly kind: ResponderQueueErrorKind) {
    super(kind)
  }
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
