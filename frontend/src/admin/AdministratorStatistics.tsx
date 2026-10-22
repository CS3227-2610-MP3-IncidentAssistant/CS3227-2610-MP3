import { useEffect, useState } from 'react'
import {
  ApiError,
  loadAdministratorStatistics,
  type AdministratorStatistics as StatisticsResult,
  type AdministratorStatisticsFilters,
  type IncidentCategory,
} from '../api'

const CATEGORIES: IncidentCategory[] = ['IT', 'HUMAN_RELATIONS', 'FACILITIES']
const EMPTY_FILTERS: AdministratorStatisticsFilters = {
  categories: [], responderId: '', reporterId: '', periodFrom: '', periodThrough: '',
}

type StatisticsQuery = AdministratorStatisticsFilters & { revision: number }

export function AdministratorStatistics() {
  const [filters, setFilters] = useState<AdministratorStatisticsFilters>(EMPTY_FILTERS)
  const [query, setQuery] = useState<StatisticsQuery>({ ...EMPTY_FILTERS, revision: 0 })
  const [result, setResult] = useState<StatisticsResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filterError, setFilterError] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    loadAdministratorStatistics(query, controller.signal)
      .then((next) => {
        setResult(next)
        setError('')
      })
      .catch((reason: unknown) => {
        if (reason instanceof DOMException && reason.name === 'AbortError') return
        setError(reason instanceof ApiError && reason.status === 401
          ? 'Your session has expired. Sign in again.'
          : 'Operational statistics are temporarily unavailable.')
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [query])

  function applyFilters(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (filters.periodFrom && filters.periodThrough && filters.periodFrom > filters.periodThrough) {
      setFilterError('The start date must be on or before the end date.')
      return
    }
    setFilterError('')
    request(filters)
  }

  function request(next: AdministratorStatisticsFilters) {
    setLoading(true)
    setError('')
    setQuery((current) => ({ ...next, revision: current.revision + 1 }))
  }

  function resetFilters() {
    setFilters(EMPTY_FILTERS)
    setFilterError('')
    request(EMPTY_FILTERS)
  }

  function toggleCategory(category: IncidentCategory) {
    setFilters((current) => ({
      ...current,
      categories: current.categories.includes(category)
        ? current.categories.filter((value) => value !== category)
        : [...current.categories, category],
    }))
  }

  return <main id="main-content" className="content">
    <header className="page-header">
      <div>
        <p className="eyebrow">Operational performance</p>
        <h1>Company statistics</h1>
        <p>Review privacy-safe incident outcomes calculated from persisted lifecycle events.</p>
      </div>
      <button className="secondary-button" type="button" disabled={loading}
        onClick={() => request(query)}>{loading ? 'Refreshing…' : 'Refresh'}</button>
    </header>

    <section className="incident-panel statistics-filter-panel" aria-labelledby="statistics-filter-heading">
      <div className="section-heading">
        <div><p className="eyebrow">Scope</p><h2 id="statistics-filter-heading">Filter statistics</h2></div>
      </div>
      <form className="statistics-filters" onSubmit={applyFilters}>
        <fieldset className="category-choice">
          <legend>Incident categories</legend>
          {CATEGORIES.map((category) => <label key={category}>
            <input type="checkbox" checked={filters.categories.includes(category)}
              onChange={() => toggleCategory(category)} />
            {displayEnum(category)}
          </label>)}
        </fieldset>
        <div className="statistics-field">
          <label htmlFor="statistics-responder">Responder</label>
          <select id="statistics-responder" value={filters.responderId}
            onChange={(event) => setFilters({ ...filters, responderId: event.target.value })}>
            <option value="">All responders</option>
            {result?.filterOptions.responders.map((option) =>
              <option value={option.id} key={option.id}>{option.displayName}</option>)}
          </select>
        </div>
        <div className="statistics-field">
          <label htmlFor="statistics-reporter">Reporter</label>
          <select id="statistics-reporter" value={filters.reporterId}
            onChange={(event) => setFilters({ ...filters, reporterId: event.target.value })}>
            <option value="">All non-anonymous reporters</option>
            {result?.filterOptions.reporters.map((option) =>
              <option value={option.id} key={option.id}>{option.displayName}</option>)}
          </select>
        </div>
        <div className="statistics-field">
          <label htmlFor="statistics-from">From date</label>
          <input id="statistics-from" type="date" value={filters.periodFrom}
            aria-describedby="statistics-period-help statistics-period-error"
            aria-invalid={filterError ? 'true' : undefined}
            onChange={(event) => setFilters({ ...filters, periodFrom: event.target.value })} />
        </div>
        <div className="statistics-field">
          <label htmlFor="statistics-through">Through date</label>
          <input id="statistics-through" type="date" value={filters.periodThrough}
            aria-describedby="statistics-period-help statistics-period-error"
            aria-invalid={filterError ? 'true' : undefined}
            onChange={(event) => setFilters({ ...filters, periodThrough: event.target.value })} />
        </div>
        <p id="statistics-period-help" className="help-text">Dates are inclusive and interpreted in UTC.</p>
        {filterError && <p id="statistics-period-error" className="field-error" role="alert">{filterError}</p>}
        <div className="form-actions">
          <button className="primary-button" type="submit" disabled={loading}>Apply filters</button>
          <button className="ghost-button" type="button" disabled={loading} onClick={resetFilters}>Reset</button>
        </div>
      </form>
    </section>

    {loading && !result && <p className="panel-state" role="status">Loading operational statistics…</p>}
    {error && <div className="incident-panel panel-state statistics-state" role="alert">
      <p>{error}</p>
      <button className="secondary-button" type="button" onClick={() => request(query)}>Try again</button>
    </div>}
    {result && !error && <>
      {loading && <p className="statistics-refresh-state" role="status">Updating statistics…</p>}
      <StatisticsSummary summary={result.summary} />
      <ResponderBreakdown responders={result.responders} />
    </>}
  </main>
}

function StatisticsSummary({ summary }: { summary: StatisticsResult['summary'] }) {
  return <section aria-labelledby="statistics-summary-heading">
    <div className="section-heading statistics-section-heading">
      <div><p className="eyebrow">Company summary</p><h2 id="statistics-summary-heading">Incident outcomes</h2></div>
    </div>
    <div className="metric-grid statistics-metric-grid">
      <Metric label="Incidents resolved" value={String(summary.resolvedIncidentCount)}
        detail={`${summary.completedCycleCount} completed resolution cycles`} />
      <Metric label="Average time to claim" value={formatDuration(summary.averageTimeToClaimMillis)}
        detail={summary.averageTimeToClaimMillis === null ? 'No claimed cycles in this scope' : `${summary.claimedCycleCount} claimed cycles`} />
      <Metric label="Average time in progress" value={formatDuration(summary.averageTimeInProgressMillis)}
        detail={summary.averageTimeInProgressMillis === null ? 'No completed cycles in this scope' : `${summary.completedCycleCount} completed cycles`} />
      <Metric label="Reopened incidents" value={String(summary.reopenedIncidentCount)}
        detail={summary.reopenRate === null ? 'No data' : `${formatRate(summary.reopenRate)} · ${summary.reopenEventCount} reopen events`} />
      <Metric label="Administrator-resolved" value={String(summary.administratorResolvedCount)}
        detail="Completed by an Administrator" />
    </div>
  </section>
}

function Metric({ label, value, detail }: { label: string, value: string, detail: string }) {
  return <article className="metric-card">
    <p>{label}</p><strong>{value}</strong><small>{detail}</small>
  </article>
}

function ResponderBreakdown({ responders }: { responders: StatisticsResult['responders'] }) {
  return <section className="incident-panel" aria-labelledby="responder-statistics-heading">
    <div className="section-heading">
      <div>
        <p className="eyebrow">Responder breakdown</p>
        <h2 id="responder-statistics-heading">Resolution performance</h2>
      </div>
      <span className="data-state">{responders.length} responders</span>
    </div>
    {responders.length === 0 ? <div className="empty-state">
      <h3>No responder data</h3>
      <p>No completed responder resolution cycles match the selected scope.</p>
    </div> : <div className="table-region" role="region" aria-label="Responder operational statistics" tabIndex={0}>
      <table>
        <thead><tr>
          <th scope="col">Responder</th><th scope="col">Resolved cycles</th>
          <th scope="col">Average time in progress</th><th scope="col">Reopened</th>
          <th scope="col">Reopen rate</th><th scope="col">Administrator-resolved</th>
        </tr></thead>
        <tbody>{responders.map((responder) => <tr key={responder.responderId}>
          <th scope="row">{responder.displayName}</th>
          <td>{responder.resolvedCycleCount}</td>
          <td>{formatDuration(responder.averageTimeInProgressMillis)}</td>
          <td>{responder.reopenedCycleCount}</td>
          <td>{formatRate(responder.reopenRate)}</td>
          <td>{responder.administratorResolvedCount}</td>
        </tr>)}</tbody>
      </table>
    </div>}
    <p className="statistics-table-note">Time to claim is a queue-level measure and is not attributed to individual responders.</p>
  </section>
}

function formatDuration(milliseconds: number | null) {
  if (milliseconds === null) return 'No data'
  const totalMinutes = Math.round(milliseconds / 60_000)
  const days = Math.floor(totalMinutes / 1_440)
  const hours = Math.floor((totalMinutes % 1_440) / 60)
  const minutes = totalMinutes % 60
  const parts = []
  if (days) parts.push(`${days}d`)
  if (hours) parts.push(`${hours}h`)
  if (minutes || parts.length === 0) parts.push(`${minutes}m`)
  return parts.join(' ')
}

function formatRate(rate: number | null) {
  return rate === null ? 'No data' : `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(rate * 100)}%`
}

function displayEnum(value: string) {
  if (value === 'IT') return 'IT'
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./, (letter) => letter.toUpperCase())
}
