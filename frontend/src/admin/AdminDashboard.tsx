import { useEffect, useState } from 'react'
import { ApiError, loadDashboard, type DashboardPage, type IncidentRow, type SloTarget } from '../api'

type DashboardQuery = {
  search: string
  page: number
  revision: number
}

export function AdminDashboard() {
  const dashboard = useDashboardData()

  return <main id="main-content" className="content">
    <DashboardHeader onRefresh={dashboard.refresh} />
    <SloOverview targets={dashboard.result?.sloTargets ?? []} loading={dashboard.loading && !dashboard.result} />
    <IncidentPanel dashboard={dashboard} />
  </main>
}

function useDashboardData() {
  const [query, setQuery] = useState<DashboardQuery>({ search: '', page: 0, revision: 0 })
  const [result, setResult] = useState<DashboardPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    loadDashboard(query.search, query.page, controller.signal)
      .then((next) => {
        setResult(next)
        setError('')
      })
      .catch((reason: unknown) => {
        if (reason instanceof DOMException && reason.name === 'AbortError') return
        setError(reason instanceof ApiError && reason.status === 401
          ? 'Your session has expired. Sign in again.'
          : 'Incident data is temporarily unavailable.')
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [query])

  function request(next: Pick<DashboardQuery, 'search' | 'page'>) {
    setLoading(true)
    setError('')
    setQuery((current) => ({ ...next, revision: current.revision + 1 }))
  }

  return {
    result,
    loading,
    error,
    search: query.search,
    page: query.page,
    searchFor: (search: string) => request({ search: search.trim(), page: 0 }),
    reset: () => request({ search: '', page: 0 }),
    previousPage: () => request({ search: query.search, page: query.page - 1 }),
    nextPage: () => request({ search: query.search, page: query.page + 1 }),
    refresh: () => request({ search: query.search, page: query.page }),
  }
}

type DashboardState = ReturnType<typeof useDashboardData>

function DashboardHeader({ onRefresh }: { onRefresh: () => void }) {
  return <header className="page-header">
    <div>
      <p className="eyebrow">Administration</p>
      <h1>Company incident overview</h1>
      <p>Monitor privacy-safe incident operations and current category targets.</p>
    </div>
    <button className="secondary-button" type="button" onClick={onRefresh}>Refresh</button>
  </header>
}

function IncidentPanel({ dashboard }: { dashboard: DashboardState }) {
  const [searchInput, setSearchInput] = useState('')
  const incidents = dashboard.result?.incidents ?? []

  function reset() {
    setSearchInput('')
    dashboard.reset()
  }

  return <section className="incident-panel" aria-labelledby="incidents-heading">
    <div className="section-heading">
      <div>
        <p className="eyebrow">Incident queue</p>
        <h2 id="incidents-heading">Company incidents</h2>
      </div>
      {dashboard.result && <span className="data-state">{dashboard.result.totalElements} incidents</span>}
    </div>
    <form className="filter-bar" role="search" onSubmit={(event) => {
      event.preventDefault()
      dashboard.searchFor(searchInput)
    }}>
      <label htmlFor="incident-search">Search incidents</label>
      <input
        id="incident-search"
        value={searchInput}
        onChange={(event) => setSearchInput(event.target.value)}
        placeholder="Title, description, or identifier"
      />
      <button className="secondary-button" type="submit">Search</button>
      <button className="ghost-button" type="button" onClick={reset}>Reset</button>
    </form>

    {dashboard.loading && <p className="panel-state" role="status">Loading incidents…</p>}
    {!dashboard.loading && dashboard.error && <div className="panel-state" role="alert">{dashboard.error}</div>}
    {!dashboard.loading && !dashboard.error && incidents.length === 0 && <IncidentEmptyState />}
    {!dashboard.error && incidents.length > 0 && <IncidentTable incidents={incidents} />}
    {dashboard.result && dashboard.result.totalPages > 1 && <Pagination dashboard={dashboard} />}
  </section>
}

function IncidentTable({ incidents }: { incidents: IncidentRow[] }) {
  return <div className="table-region" role="region" aria-label="Company incidents" tabIndex={0}>
    <table>
      <thead>
        <tr>
          <th scope="col">Incident</th>
          <th scope="col">Category</th>
          <th scope="col">Status</th>
          <th scope="col">Reporter</th>
          <th scope="col">Assignee</th>
          <th scope="col">Created</th>
        </tr>
      </thead>
      <tbody>{incidents.map((incident) => <IncidentTableRow incident={incident} key={incident.id} />)}</tbody>
    </table>
  </div>
}

function IncidentTableRow({ incident }: { incident: IncidentRow }) {
  return <tr>
    <th scope="row">
      <span className="incident-title">{incident.title}</span>
      <small>{incident.id}</small>
    </th>
    <td>{displayEnum(incident.category)}</td>
    <td><span className="status-badge">{displayEnum(incident.status)}</span></td>
    <td>{incident.reporterLabel}</td>
    <td>{incident.assigneeLabel ?? 'Unassigned'}</td>
    <td><time dateTime={incident.createdAt}>{new Date(incident.createdAt).toLocaleString()}</time></td>
  </tr>
}

function Pagination({ dashboard }: { dashboard: DashboardState }) {
  const result = dashboard.result
  if (!result) return null
  return <nav className="pagination" aria-label="Incident pages">
    <button type="button" disabled={dashboard.page === 0 || dashboard.loading} onClick={dashboard.previousPage}>
      Previous
    </button>
    <span>Page {dashboard.page + 1} of {result.totalPages}</span>
    <button
      type="button"
      disabled={dashboard.page + 1 >= result.totalPages || dashboard.loading}
      onClick={dashboard.nextPage}
    >
      Next
    </button>
  </nav>
}

function IncidentEmptyState() {
  return <div className="empty-state">
    <h3>No incidents match</h3>
    <p>Adjust the search or clear the filters.</p>
  </div>
}

function SloOverview({ targets, loading }: { targets: SloTarget[], loading: boolean }) {
  return <section aria-labelledby="slo-heading">
    <div className="section-heading">
      <div><p className="eyebrow">Service objectives</p><h2 id="slo-heading">Category targets</h2></div>
    </div>
    <div className="metric-grid">
      {loading
        ? <p role="status">Loading SLO targets…</p>
        : targets.map((target) => <SloTargetCard target={target} key={target.category} />)}
    </div>
  </section>
}

function SloTargetCard({ target }: { target: SloTarget }) {
  const configured = target.timeToClaimMinutes != null
  return <article className="metric-card">
    <p>{displayEnum(target.category)}</p>
    <strong>{configured ? `${target.timeToClaimMinutes} min` : 'Not configured'}</strong>
    <small>{target.timeInProgressMinutes == null
      ? 'Set targets in SLO configuration'
      : `${target.timeInProgressMinutes} min in progress · ${target.reopenRatePercent}% reopen`}</small>
  </article>
}

function displayEnum(value: string) {
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./, (letter) => letter.toUpperCase())
}
