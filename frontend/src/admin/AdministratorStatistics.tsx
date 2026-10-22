import { useEffect, useState } from 'react'
import {
  ApiError,
  loadAdministratorStatistics,
  requestAdministratorInsight,
  type AdministratorInsight,
  type AdministratorStatistics as StatisticsResult,
  type AdministratorStatisticsFilters,
  type IncidentCategory,
  type SessionRefresher,
} from '../api'
import { incidentCategoryName } from '../shared/incident/incidentDetail'

const CATEGORIES: IncidentCategory[] = ['IT', 'HUMAN_RELATIONS', 'FACILITIES']
const SINGAPORE_HOUR = new Intl.DateTimeFormat('en-SG', {
  timeZone: 'Asia/Singapore', day: 'numeric', month: 'short', year: 'numeric',
  hour: 'numeric', hour12: true,
})
const EMPTY_FILTERS: AdministratorStatisticsFilters = {
  categories: [], responderId: '', reporterId: '', periodFrom: '', periodThrough: '',
}

type StatisticsQuery = AdministratorStatisticsFilters & { revision: number }

export function AdministratorStatistics({ onRefreshSession }: { onRefreshSession: SessionRefresher }) {
  const [filters, setFilters] = useState<AdministratorStatisticsFilters>(EMPTY_FILTERS)
  const [query, setQuery] = useState<StatisticsQuery>({ ...EMPTY_FILTERS, revision: 0 })
  const [result, setResult] = useState<StatisticsResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filterError, setFilterError] = useState('')
  const [insightQuestion, setInsightQuestion] = useState('')
  const [insight, setInsight] = useState<AdministratorInsight | null>(null)
  const [insightLoading, setInsightLoading] = useState(false)
  const [insightError, setInsightError] = useState('')
  const [insightResponderIds, setInsightResponderIds] = useState<string[]>([])

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

  async function askInsight(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!insightQuestion.trim() || Array.from(insightQuestion).length > 500) {
      setInsightError('Enter a question of 1 to 500 characters.')
      return
    }
    setInsightLoading(true)
    setInsightError('')
    setInsight(null)
    try {
      setInsight(await requestAdministratorInsight(insightQuestion, filters.categories,
        insightResponderIds, onRefreshSession))
    } catch (reason) {
      setInsightError(reason instanceof ApiError ? reason.message : 'AI insights are temporarily unavailable.')
    } finally {
      setInsightLoading(false)
    }
  }

  function toggleInsightResponder(responderId: string) {
    setInsightResponderIds((current) => current.includes(responderId)
      ? current.filter((value) => value !== responderId)
      : current.length < 5 ? [...current, responderId] : current)
    setInsight(null)
    setInsightError('')
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
            {incidentCategoryName(category)}
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

    <section className="incident-panel statistics-insight-panel" aria-labelledby="statistics-insight-heading">
      <div className="section-heading"><div>
        <p className="eyebrow">AI-assisted interpretation</p>
        <h2 id="statistics-insight-heading">Ask about operational statistics</h2>
      </div></div>
      <form className="statistics-insight-form" onSubmit={askInsight}>
        <label htmlFor="statistics-insight-question">Question</label>
        <textarea id="statistics-insight-question" rows={3} maxLength={500} value={insightQuestion}
          onChange={(event) => { setInsightQuestion(event.target.value); setInsight(null); setInsightError('') }}
          aria-describedby="statistics-insight-help" />
        <p id="statistics-insight-help" className="help-text">Include a period. Results are limited to 10 rows, with first-place ties shown together; responder comparisons require 2–5 selections. Every responder result is shown regardless of sample size, with zero-denominator values marked Not available. Do not enter names, incident details, comments, reporter information, identifiers, or links. Current category and responder filters are applied separately and are not sent to the AI provider.</p>
        <fieldset className="insight-responder-choice">
          <legend>Responders to compare (optional, select 2–5)</legend>
          {result?.filterOptions.responders.map((option) => <label key={option.id}>
            <input type="checkbox" checked={insightResponderIds.includes(option.id)}
              disabled={!insightResponderIds.includes(option.id) && insightResponderIds.length >= 5}
              onChange={() => toggleInsightResponder(option.id)} />
            {option.displayName}
          </label>)}
        </fieldset>
        <div className="form-actions">
          <button className="primary-button" type="submit" disabled={insightLoading}>{insightLoading ? 'Interpreting…' : 'Get insight'}</button>
          {(insight || insightError) && <button className="ghost-button" type="button" onClick={() => { setInsight(null); setInsightError('') }}>Clear</button>}
        </div>
      </form>
      {insightError && <p className="field-error statistics-insight-message" role="alert">{insightError}</p>}
      {insight && <InsightResult insight={insight} />}
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

function InsightResult({ insight }: { insight: AdministratorInsight }) {
  const activeFilters = [
    insight.categoryFilters.length > 0
      ? `Categories: ${insight.categoryFilters.map((category) => incidentCategoryName(category as IncidentCategory)).join(', ')}`
      : null,
    insight.responderFilters.length > 0
      ? `Responders: ${insight.responderFilters.join(', ')}`
      : null,
  ].filter((filter): filter is string => filter !== null)

  return <div className="statistics-insight-result" role="region" aria-label="AI insight result" aria-live="polite">
    <p className="eyebrow">{insight.outcome === 'ANSWER' ? 'Deterministic answer' : 'Clarification needed'}</p>
    <h3>{insight.summary}</h3>
    {insight.outcome === 'ANSWER' && <dl className="insight-query-details" aria-label="Deterministic query details">
      <div><dt>Metric</dt><dd>{displayEnum(insight.metric!)}</dd></div>
      <div><dt>Ranking direction</dt><dd>{insight.rankingDirection ? displayEnum(insight.rankingDirection) : 'Not applicable'}</dd></div>
      <div><dt>Limit</dt><dd>{insight.limit ?? 'Not applicable'}</dd></div>
      <div><dt>Grouping</dt><dd>{displayEnum(insight.grouping!)}</dd></div>
      <div><dt>Period</dt><dd>{formatSingaporeHour(insight.periodStart!)} to {formatSingaporeHour(insight.periodEndExclusive!)} (Singapore time; end exclusive)</dd></div>
      <div><dt>Active filters</dt><dd>{activeFilters.length > 0 ? activeFilters.join('; ') : 'None'}</dd></div>
    </dl>}
    {insight.rows.length > 0 && <div className="table-region" role="region" aria-label="AI insight rows" tabIndex={0}>
      <table><thead><tr><th scope="col">Result</th><th scope="col">Value</th><th scope="col">Sample</th></tr></thead>
        <tbody>{insight.rows.map((row) => <tr key={row.label}>
          <th scope="row">{row.label}</th><td>{row.displayValue}</td><td>{row.sampleSize}</td>
        </tr>)}</tbody></table>
    </div>}
    <p className="statistics-table-note">{insight.advisoryNotice} Treat responder comparisons as operational indicators, not employee-performance or disciplinary judgments.</p>
  </div>
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

function formatSingaporeHour(instant: string) {
  return SINGAPORE_HOUR.format(new Date(instant))
}

function formatRate(rate: number | null) {
  return rate === null ? 'No data' : `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(rate * 100)}%`
}

function displayEnum(value: string) {
  if (value === 'IT') return 'IT'
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./, (letter) => letter.toUpperCase())
}
