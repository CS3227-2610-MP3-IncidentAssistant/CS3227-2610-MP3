import { useEffect, useRef, useState } from 'react'
import type { IncidentCategory } from '../api'
import type { HealthState } from '../health/useServiceHealth'
import { RoleShell, type RoleNavigationItem } from '../shared/RoleShell'
import {
  loadResponderQueue,
  loadResponderIncidentDetail,
  ResponderQueueError,
  type ResponderIncidentDetail,
  type ResponderQueuePage,
  type ResponderQueueQuery,
} from './responderApi'

const PAGE_SIZE = 25
const categoryNames: Record<IncidentCategory, string> = {
  IT: 'IT',
  HUMAN_RELATIONS: 'Human relations',
  FACILITIES: 'Facilities',
}
const statusOptions = ['SUBMITTED', 'ASSIGNED'] as const

type DraftFilters = Pick<ResponderQueueQuery, 'search' | 'categories' | 'statuses' | 'assignment'>
type QueueState =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: ResponderQueuePage }
  | { kind: 'session-expired' }
  | { kind: 'denied' }
  | { kind: 'invalid' }
  | { kind: 'service-error' }
  | { kind: 'signing-out' }
type DetailState =
  | { kind: 'loading'; incidentId: string; loginName: string }
  | { kind: 'loaded'; incidentId: string; loginName: string; detail: ResponderIncidentDetail }
  | { kind: 'session-expired'; incidentId: string; loginName: string }
  | { kind: 'denied'; incidentId: string; loginName: string }
  | { kind: 'service-error'; incidentId: string; loginName: string }

const initialFilters: DraftFilters = {
  search: '',
  categories: [],
  statuses: [],
  assignment: 'ALL',
}

export function ResponderPortal({ categories, loginName, onLogout, serviceHealth }: {
  categories: IncidentCategory[]
  loginName: string
  onLogout: () => Promise<void>
  serviceHealth: HealthState
}) {
  const [draft, setDraft] = useState<DraftFilters>(initialFilters)
  const [query, setQuery] = useState<ResponderQueueQuery>({ ...initialFilters, page: 0, size: PAGE_SIZE })
  const [queue, setQueue] = useState<QueueState>({ kind: 'loading' })
  const [selectedIncidentId, setSelectedIncidentId] = useState<string | null>(null)
  const [detailRequestKey, setDetailRequestKey] = useState(0)
  const [detail, setDetail] = useState<DetailState | null>(null)
  const pageHeading = useRef<HTMLHeadingElement>(null)
  const requestGeneration = useRef(0)
  const activeRequest = useRef<AbortController | null>(null)
  const detailGeneration = useRef(0)
  const activeDetailRequest = useRef<AbortController | null>(null)
  const navigation: RoleNavigationItem[] = [{ label: 'Incident queue', current: true }]

  useEffect(() => {
    const controller = new AbortController()
    const generation = ++requestGeneration.current
    activeRequest.current = controller
    loadResponderQueue(query, controller.signal)
      .then((page) => {
        if (!controller.signal.aborted && generation === requestGeneration.current) {
          setQueue({ kind: 'loaded', page })
        }
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || generation !== requestGeneration.current) return
        if (error instanceof ResponderQueueError) {
          setQueue({
            kind: error.kind === 'session' ? 'session-expired'
              : error.kind === 'denied' ? 'denied'
                : error.kind === 'invalid' ? 'invalid' : 'service-error',
          })
        } else {
          setQueue({ kind: 'service-error' })
        }
      })
    return () => {
      controller.abort()
      if (activeRequest.current === controller) activeRequest.current = null
    }
  }, [query])

  useEffect(() => {
    pageHeading.current?.focus()
  }, [selectedIncidentId])

  useEffect(() => {
    if (!selectedIncidentId) return
    const controller = new AbortController()
    const generation = ++detailGeneration.current
    const request = { incidentId: selectedIncidentId, loginName }
    activeDetailRequest.current = controller
    loadResponderIncidentDetail(selectedIncidentId, controller.signal)
      .then((loaded) => {
        if (!controller.signal.aborted && generation === detailGeneration.current) {
          setDetail({ kind: 'loaded', ...request, detail: loaded })
        }
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || generation !== detailGeneration.current) return
        if (error instanceof ResponderQueueError && error.kind === 'session') {
          setQueue({ kind: 'session-expired' })
          setDetail({ kind: 'session-expired', ...request })
        } else if (error instanceof ResponderQueueError && error.kind === 'denied') {
          setDetail({ kind: 'denied', ...request })
        } else {
          setDetail({ kind: 'service-error', ...request })
        }
      })
    return () => {
      controller.abort()
      if (activeDetailRequest.current === controller) activeDetailRequest.current = null
    }
  }, [loginName, selectedIncidentId, detailRequestKey])

  function submitFilters(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    changeQuery({ ...draft, search: draft.search.trim(), page: 0, size: PAGE_SIZE })
  }

  function changePage(page: number) {
    if (queue.kind !== 'loaded' || page < 0 || page >= queue.page.totalPages) return
    changeQuery({ ...query, page })
  }

  function refreshQueue() {
    changeQuery({ ...query, page: 0 })
  }

  function openDetail(incidentId: string) {
    detailGeneration.current++
    activeDetailRequest.current?.abort()
    setDetail({ kind: 'loading', incidentId, loginName })
    setSelectedIncidentId(incidentId)
  }

  function retryDetail() {
    if (!selectedIncidentId) return
    detailGeneration.current++
    activeDetailRequest.current?.abort()
    setDetail({ kind: 'loading', incidentId: selectedIncidentId, loginName })
    setDetailRequestKey((key) => key + 1)
  }

  function backToQueue() {
    detailGeneration.current++
    activeDetailRequest.current?.abort()
    setDetail(null)
    setSelectedIncidentId(null)
    changeQuery({ ...query })
  }

  function changeQuery(next: ResponderQueueQuery) {
    requestGeneration.current++
    activeRequest.current?.abort()
    setQueue({ kind: 'loading' })
    setQuery(next)
  }

  async function signOut() {
    requestGeneration.current++
    detailGeneration.current++
    activeRequest.current?.abort()
    activeDetailRequest.current?.abort()
    setSelectedIncidentId(null)
    setDetail(null)
    setQueue({ kind: 'signing-out' })
    try {
      await onLogout()
    } catch {
      setQueue({ kind: 'service-error' })
    }
  }

  return (
    <RoleShell
      actorInitial="R"
      brandSubtitle="Responder workspace"
      loginName={loginName}
      navigationId="responder-navigation"
      navigationItems={navigation}
      navigationLabel="Responder navigation"
      onLogout={signOut}
      roleLabel="Responder"
      serviceHealth={serviceHealth}
    >
      <main id="main-content" className="content">
        <header className="page-header">
          <div>
            <p className="eyebrow">Responder workspace</p>
            <h1 ref={pageHeading} tabIndex={-1}>{selectedIncidentId ? 'Incident details' : 'Incident queue'}</h1>
            <p>{selectedIncidentId
              ? 'Review this incident using your current responder access.'
              : 'View incidents assigned to you and unassigned incidents in your accessible categories.'}</p>
          </div>
          {selectedIncidentId
            ? <button className="secondary-button" type="button" onClick={backToQueue}>Back to queue</button>
            : <button className="secondary-button" type="button" onClick={refreshQueue}>Refresh queue</button>}
        </header>

        {selectedIncidentId ? (
          <ResponderDetailContent
            state={detail?.incidentId === selectedIncidentId && detail.loginName === loginName
              ? detail : { kind: 'loading', incidentId: selectedIncidentId, loginName }}
            onRetry={retryDetail}
            onLogout={signOut}
          />
        ) : <section className="incident-panel" aria-labelledby="responder-queue-heading">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Authorized incidents</p>
              <h2 id="responder-queue-heading">Queue</h2>
            </div>
            {queue.kind === 'loaded' && <span className="data-state">{queue.page.totalElements} incidents</span>}
          </div>
          <form className="responder-filters" onSubmit={submitFilters}>
            <label htmlFor="responder-search">Search incidents</label>
            <input
              id="responder-search"
              maxLength={200}
              value={draft.search}
              onChange={(event) => setDraft({ ...draft, search: event.target.value })}
              placeholder="Title, description, or incident ID"
            />
            {queue.kind === 'loaded' && queue.page.categoriesConfigured && categories.length > 0 && (
              <fieldset className="category-choice">
                <legend>Category</legend>
                {categories.map((category) => (
                  <label key={category}>
                    <input
                      type="checkbox"
                      checked={draft.categories.includes(category)}
                      onChange={() => setDraft({ ...draft, categories: toggleValue(draft.categories, category) })}
                    />
                    {categoryNames[category]}
                  </label>
                ))}
              </fieldset>
            )}
            <fieldset className="category-choice">
              <legend>Status</legend>
              {statusOptions.map((status) => (
                <label key={status}>
                  <input
                    type="checkbox"
                    checked={draft.statuses.includes(status)}
                    onChange={() => setDraft({ ...draft, statuses: toggleValue(draft.statuses, status) })}
                  />
                  {statusLabel(status)}
                </label>
              ))}
            </fieldset>
            <label htmlFor="responder-assignment">Assignment</label>
            <select
              id="responder-assignment"
              value={draft.assignment}
              onChange={(event) => setDraft({ ...draft, assignment: event.target.value as DraftFilters['assignment'] })}
            >
              <option value="ALL">All queue incidents</option>
              <option value="ASSIGNED">Assigned to me</option>
              <option value="UNASSIGNED">Available to claim</option>
            </select>
            <button className="primary-button" type="submit">Apply filters</button>
          </form>

          <QueueContent state={queue} onRetry={refreshQueue} onLogout={signOut} onPageChange={changePage}
            onOpenDetail={openDetail} />
        </section>
        }
      </main>
    </RoleShell>
  )
}

function QueueContent({ state, onRetry, onLogout, onPageChange, onOpenDetail }: {
  state: QueueState
  onRetry: () => void
  onLogout: () => Promise<void>
  onPageChange: (page: number) => void
  onOpenDetail: (incidentId: string) => void
}) {
  if (state.kind === 'loading') return <p className="panel-state" role="status">Loading incidents…</p>
  if (state.kind === 'signing-out') return <p className="panel-state" role="status">Signing out…</p>
  if (state.kind === 'session-expired') {
    return (
      <div className="panel-state" role="alert">
        Your session has expired or changed. Sign in again to view the incident queue.{' '}
        <button type="button" onClick={() => void onLogout()}>Return to portal selection</button>
      </div>
    )
  }
  if (state.kind === 'denied') {
    return <p className="panel-state" role="alert">The incident queue is unavailable for this session.</p>
  }
  if (state.kind === 'invalid') {
    return (
      <div className="panel-state" role="alert">
        One or more filters are invalid. Update them and try again.{' '}
        <button type="button" onClick={onRetry}>Try again</button>
      </div>
    )
  }
  if (state.kind === 'service-error') {
    return (
      <div className="panel-state" role="alert">
        Incidents are temporarily unavailable.{' '}
        <button type="button" onClick={onRetry}>Try again</button>
      </div>
    )
  }

  const { page } = state
  return (
    <>
      {!page.categoriesConfigured && (
        <p className="panel-state" role="status">
          No categories are currently assigned. Incidents already assigned to you can still appear here.
        </p>
      )}
      {page.incidents.length === 0 ? (
        <div className="empty-state">
          <span className="empty-state__mark" aria-hidden="true">○</span>
          <h3>{page.categoriesConfigured ? 'No incidents match these filters' : 'No assigned incidents to show'}</h3>
          <p>{page.categoriesConfigured
            ? 'Try changing your search or filters.'
            : 'Incidents assigned to you will appear here, even when no categories are assigned.'}</p>
        </div>
      ) : (
        <IncidentTable incidents={page.incidents} onOpenDetail={onOpenDetail} />
      )}
      {page.totalPages > 1 && (
        <div className="pagination" aria-label="Incident queue pages">
          <button type="button" disabled={page.page === 0} onClick={() => onPageChange(page.page - 1)}>
            Previous
          </button>
          <span>Page {page.page + 1} of {page.totalPages}</span>
          <button type="button" disabled={page.page + 1 >= page.totalPages}
            onClick={() => onPageChange(page.page + 1)}>
            Next
          </button>
        </div>
      )}
    </>
  )
}

function IncidentTable({ incidents, onOpenDetail }: {
  incidents: ResponderQueuePage['incidents']
  onOpenDetail: (incidentId: string) => void
}) {
  return (
    <div className="table-region" role="region" aria-label="Responder incidents" tabIndex={0}>
      <table>
        <thead>
          <tr>
            <th scope="col">Incident</th>
            <th scope="col">Category</th>
            <th scope="col">Status</th>
            <th scope="col">Submitted</th>
          </tr>
        </thead>
        <tbody>
          {incidents.map((incident) => (
            <tr key={incident.id}>
              <th scope="row">
                <button className="incident-title-link" type="button" aria-label={`View details for ${incident.title}`}
                  onClick={() => onOpenDetail(incident.id)}>
                  {incident.title}
                </button>
                <small>{incident.id}</small>
              </th>
              <td>{categoryNames[incident.category]}</td>
              <td><span className="status-badge">{statusLabel(incident.status)}</span></td>
              <td>{new Date(incident.submittedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ResponderDetailContent({ state, onRetry, onLogout }: {
  state: DetailState
  onRetry: () => void
  onLogout: () => Promise<void>
}) {
  if (state.kind === 'loading') return <section className="incident-panel" aria-labelledby="responder-detail-heading">
    <h2 className="panel-state" id="responder-detail-heading" role="status">Loading incident details…</h2>
  </section>
  if (state.kind === 'session-expired') return <section className="incident-panel" aria-labelledby="responder-detail-heading">
    <div className="panel-state" role="alert">
      <h2 id="responder-detail-heading">Session expired</h2>
      <p>Your session has expired or changed. Sign in again to view this incident.</p>
      <button type="button" onClick={() => void onLogout()}>Return to portal selection</button>
    </div>
  </section>
  if (state.kind === 'denied') return <section className="incident-panel" aria-labelledby="responder-detail-heading">
    <div className="panel-state" role="alert">
      <h2 id="responder-detail-heading">Incident unavailable</h2>
      <p>This incident is unavailable to your current responder session.</p>
    </div>
  </section>
  if (state.kind === 'service-error') return <section className="incident-panel" aria-labelledby="responder-detail-heading">
    <div className="panel-state" role="alert">
      <h2 id="responder-detail-heading">Details unavailable</h2>
      <p>Incident details are temporarily unavailable.</p>
      <button type="button" onClick={onRetry}>Try again</button>
    </div>
  </section>

  const { detail } = state
  return (
    <section className="incident-panel responder-detail" aria-labelledby="responder-detail-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">{categoryNames[detail.category]} · {statusLabel(detail.status)}</p>
          <h2 id="responder-detail-heading">{detail.title}</h2>
        </div>
      </div>
      <dl className="responder-detail__metadata">
        <div><dt>Reporter</dt><dd>{detail.reporterLabel}</dd></div>
        <div><dt>Submitted</dt><dd>{new Date(detail.submittedAt).toLocaleString()}</dd></div>
      </dl>
      <section className="responder-detail__description" aria-labelledby="responder-description-heading">
        <h3 id="responder-description-heading">Description</h3>
        <p>{detail.description}</p>
      </section>
    </section>
  )
}

function toggleValue<T extends string>(values: T[], value: T): T[] {
  return values.includes(value) ? values.filter((current) => current !== value) : [...values, value]
}

function statusLabel(status: 'SUBMITTED' | 'ASSIGNED'): string {
  return status === 'SUBMITTED' ? 'Submitted' : 'Assigned'
}
