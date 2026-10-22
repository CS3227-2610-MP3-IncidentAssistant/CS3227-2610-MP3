import { useEffect, useState } from 'react'
import {
  ApiError,
  loadSloConfiguration,
  saveSloTarget,
  type SessionRefresher,
  type SloCategoryConfiguration,
} from '../api'
import { Pagination } from '../shared/Pagination'

const HISTORY_PAGE_SIZE = 10

export function AdministratorSloConfiguration({ onRefreshSession }: {
  onRefreshSession: SessionRefresher
}) {
  const [categories, setCategories] = useState<SloCategoryConfiguration[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [revision, setRevision] = useState(0)
  const [selectedCategory, setSelectedCategory] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    loadSloConfiguration(controller.signal)
      .then((result) => {
        setCategories(result.categories)
        setSelectedCategory((current) => result.categories.some(({ category }) => category === current)
          ? current
          : result.categories[0]?.category ?? '')
        setFailed(false)
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) setFailed(true)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [revision])

  function reload() {
    setRevision((current) => current + 1)
  }

  return <main id="main-content" className="content">
    <header className="page-header">
      <div>
        <p className="eyebrow">SLO configuration</p>
        <h1>Category targets</h1>
        <p>Changes apply prospectively. Previous versions remain in the read-only history.</p>
      </div>
      <button className="secondary-button" type="button" onClick={reload}>Refresh</button>
    </header>
    {loading && <p className="panel-state" role="status">Loading SLO targets…</p>}
    {!loading && failed && <div className="incident-panel panel-state" role="alert">
      SLO configuration is temporarily unavailable. Refresh to try again.
    </div>}
    {!loading && !failed && categories.length === 0 && <div className="incident-panel panel-state">
      No incident categories are available.
    </div>}
    {!loading && !failed && categories.length > 0 && <>
      <section className="incident-panel slo-category-selector" aria-labelledby="slo-category-selector-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Incident category</p>
            <h2 id="slo-category-selector-heading">Choose a category</h2>
          </div>
        </div>
        <label htmlFor="slo-category">Category</label>
        <select id="slo-category" value={selectedCategory}
          onChange={(event) => setSelectedCategory(event.target.value)}>
          {categories.map(({ category }) => <option value={category} key={category}>{displayEnum(category)}</option>)}
        </select>
      </section>
      {categories.filter(({ category }) => category === selectedCategory).map((configuration) => <SloCategoryEditor
        configuration={configuration}
        key={`${configuration.category}:${configuration.current?.id ?? 'unconfigured'}`}
        onRefreshSession={onRefreshSession}
        onSaved={reload}
      />)}
    </>}
  </main>
}

function SloCategoryEditor({ configuration, onRefreshSession, onSaved }: {
  configuration: SloCategoryConfiguration
  onRefreshSession: SessionRefresher
  onSaved: () => void
}) {
  const current = configuration.current
  const [claim, setClaim] = useState(current ? String(current.timeToClaimMinutes) : '')
  const [progress, setProgress] = useState(current ? String(current.timeInProgressMinutes) : '')
  const [reopen, setReopen] = useState(current ? String(current.reopenRatePercent) : '')
  const [submitting, setSubmitting] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [historyPage, setHistoryPage] = useState(0)
  const categoryLabel = displayEnum(configuration.category)
  const history = [...configuration.history].reverse()
  const totalHistoryPages = Math.max(1, Math.ceil(history.length / HISTORY_PAGE_SIZE))
  const visibleHistory = history.slice(historyPage * HISTORY_PAGE_SIZE, (historyPage + 1) * HISTORY_PAGE_SIZE)
  const unchanged = current !== null
    && Number(claim) === current.timeToClaimMinutes
    && Number(progress) === current.timeInProgressMinutes
    && Number(reopen) === current.reopenRatePercent

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setFeedback('')
    setFieldErrors({})
    try {
      const result = await saveSloTarget(configuration.category, {
        timeToClaimMinutes: Number(claim),
        timeInProgressMinutes: Number(progress),
        reopenRatePercent: Number(reopen),
        expectedVersionId: current?.id ?? null,
      }, onRefreshSession)
      setFeedback(result.changed ? `${categoryLabel} targets were updated.` : 'No values changed.')
      if (result.changed) onSaved()
    } catch (error) {
      if (error instanceof ApiError) {
        setFieldErrors(error.fieldErrors ?? {})
        setFeedback(error.status === 409
          ? 'These targets changed in another session. Refresh before trying again.'
          : error.message)
      } else {
        setFeedback('The SLO target could not be saved. Try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return <div className="slo-configuration-list">
    <section className="incident-panel slo-current-target" aria-labelledby={`slo-current-${configuration.category}`}>
      <div className="section-heading">
        <div>
          <p className="eyebrow">Current effective target</p>
          <h2 id={`slo-current-${configuration.category}`}>{categoryLabel}</h2>
        </div>
        <span className="data-state">{current ? `Effective ${formatInstant(current.effectiveAt)}` : 'Not configured'}</span>
      </div>
      <div className="metric-grid slo-target-metrics">
        <SloMetric label="Average time to claim" value={current ? `${current.timeToClaimMinutes} min` : '—'} />
        <SloMetric label="Average time in progress" value={current ? `${current.timeInProgressMinutes} min` : '—'} />
        <SloMetric label="Average reopen rate" value={current ? `${current.reopenRatePercent}%` : '—'} />
      </div>
    </section>
    <section className="incident-panel slo-category" aria-labelledby={`slo-${configuration.category}`}>
      <div className="section-heading">
        <div>
          <p className="eyebrow">Configure category targets</p>
          <h2 id={`slo-${configuration.category}`}>New {categoryLabel} target version</h2>
        </div>
      </div>
      <form className="slo-target-form" onSubmit={(event) => void submit(event)}>
        <SloNumberField id={`${configuration.category}-claim`} label="Average time to claim (minutes)"
          value={claim} onChange={setClaim} error={fieldErrors.timeToClaimMinutes} step="1" />
        <SloNumberField id={`${configuration.category}-progress`} label="Average time in progress (minutes)"
          value={progress} onChange={setProgress} error={fieldErrors.timeInProgressMinutes} step="1" />
        <SloNumberField id={`${configuration.category}-reopen`} label="Average reopen rate (%)"
          value={reopen} onChange={setReopen} error={fieldErrors.reopenRatePercent} step="0.01" max="100" />
        <button className="primary-button" type="submit" disabled={submitting || unchanged}>
          {submitting ? 'Saving…' : 'Save new version'}
        </button>
        {feedback && <p className={Object.keys(fieldErrors).length > 0 ? 'form-error' : 'account-feedback'}
          role={Object.keys(fieldErrors).length > 0 ? 'alert' : 'status'}>{feedback}</p>}
      </form>
      <div className="slo-history">
        <h3>Version history</h3>
        {configuration.history.length === 0 ? <p>No target versions yet.</p> : <div className="table-region" role="region"
          aria-label={`${categoryLabel} SLO version history`} tabIndex={0}>
          <table>
            <thead><tr><th scope="col">Effective</th><th scope="col">Claim</th><th scope="col">In progress</th><th scope="col">Reopen rate</th></tr></thead>
            <tbody>{visibleHistory.map((version) => <tr key={version.id}>
              <th scope="row">{formatInstant(version.effectiveAt)}</th>
              <td>{version.timeToClaimMinutes} min</td>
              <td>{version.timeInProgressMinutes} min</td>
              <td>{version.reopenRatePercent}%</td>
            </tr>)}</tbody>
          </table>
        </div>}
        <Pagination page={historyPage} totalPages={totalHistoryPages}
          ariaLabel={`${categoryLabel} SLO version history pages`} onPageChange={setHistoryPage} />
      </div>
    </section>
  </div>
}

function SloMetric({ label, value }: { label: string, value: string }) {
  return <article className="metric-card">
    <p>{label}</p>
    <strong>{value}</strong>
    <small>{value === '—' ? 'Not configured' : 'Current effective value'}</small>
  </article>
}

function SloNumberField({ id, label, value, onChange, error, step, max }: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  error?: string
  step: string
  max?: string
}) {
  const errorId = `${id}-error`
  return <div className="slo-field">
    <label htmlFor={id}>{label}</label>
    <input id={id} type="number" min="0" max={max} step={step} required value={value}
      aria-invalid={Boolean(error)} aria-describedby={error ? errorId : undefined}
      onChange={(event) => onChange(event.target.value)} />
    {error && <span className="field-error" id={errorId}>{error}</span>}
  </div>
}

function displayEnum(value: string) {
  if (value === 'IT') return 'IT'
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./, (letter) => letter.toUpperCase())
}

function formatInstant(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
