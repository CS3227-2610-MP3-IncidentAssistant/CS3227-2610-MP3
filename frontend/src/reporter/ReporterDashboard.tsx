import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  loadReporterIncidents,
  submitReporterIncident,
  type IncidentSubmission,
  type ReporterIncidentPage,
  type SessionRefresher,
} from '../api'

const emptyPage: ReporterIncidentPage = {
  incidents: [],
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 1,
}

export function ReporterDashboard({ onRefreshSession }: { onRefreshSession: SessionRefresher }) {
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState({ search: '', page: 0, revision: 0 })
  const [result, setResult] = useState<ReporterIncidentPage>(emptyPage)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)

  const beginLoad = useCallback(() => {
    setLoading(true)
    setLoadError(false)
  }, [])

  const reload = useCallback(() => {
    beginLoad()
    setQuery((current) => ({ ...current, revision: current.revision + 1 }))
  }, [beginLoad])

  function submitSearch(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    beginLoad()
    setQuery((current) => ({
      search: search.trim(),
      page: 0,
      revision: current.revision + 1,
    }))
  }

  function loadPage(page: number) {
    beginLoad()
    setQuery((current) => ({ ...current, page }))
  }

  useEffect(() => {
    const controller = new AbortController()
    loadReporterIncidents(query.search, query.page, controller.signal)
      .then((page) => setResult(page))
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) setLoadError(true)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [query])

  return (
    <>
      <header className="page-header">
        <div>
          <p className="eyebrow">Reporter workspace</p>
          <h1>Report and track incidents</h1>
          <p>
            Create a report and follow its progress without exposing incidents owned by anyone else.
          </p>
        </div>
      </header>
      <IncidentForm onRefreshSession={onRefreshSession} onSubmitted={reload} />
      <section className="incident-panel" aria-labelledby="my-incidents-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Owned by you</p>
            <h2 id="my-incidents-heading">My incidents</h2>
          </div>
          <span className="data-state">{result.totalElements} reports</span>
        </div>
        <form className="filter-bar" onSubmit={submitSearch}>
          <label htmlFor="reporter-search">Search your reports</label>
          <input
            id="reporter-search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Title, description, or incident ID"
          />
          <button className="primary-button" type="submit">Search</button>
          <button className="secondary-button" type="button" onClick={reload}>Refresh</button>
        </form>
        {loading && <p className="panel-state" role="status">Loading incidents…</p>}
        {!loading && loadError && (
          <div className="panel-state" role="alert">
            Incidents are temporarily unavailable.{' '}
            <button type="button" onClick={reload}>Try again</button>
          </div>
        )}
        {!loading && !loadError && result.incidents.length === 0 && (
          <div className="empty-state">
            <span className="empty-state__mark" aria-hidden="true">○</span>
            <h3>No incidents match</h3>
            <p>Submit your first incident or adjust the search.</p>
          </div>
        )}
        {!loading && !loadError && result.incidents.length > 0 && (
          <IncidentTable incidents={result.incidents} />
        )}
        {!loading && !loadError && result.totalPages > 1 && (
          <div className="pagination">
            <button
              type="button"
              disabled={result.page === 0}
              onClick={() => loadPage(result.page - 1)}
            >
              Previous
            </button>
            <span>Page {result.page + 1} of {result.totalPages}</span>
            <button
              type="button"
              disabled={result.page + 1 >= result.totalPages}
              onClick={() => loadPage(result.page + 1)}
            >
              Next
            </button>
          </div>
        )}
      </section>
    </>
  )
}

function IncidentTable({ incidents }: { incidents: ReporterIncidentPage['incidents'] }) {
  return (
    <div className="table-region" role="region" aria-label="My incidents" tabIndex={0}>
      <table>
        <thead>
          <tr>
            <th scope="col">Incident</th>
            <th scope="col">Category</th>
            <th scope="col">Status</th>
            <th scope="col">Submitted</th>
            <th scope="col">Privacy</th>
          </tr>
        </thead>
        <tbody>
          {incidents.map((incident) => (
            <tr key={incident.id}>
              <th scope="row">
                <span className="incident-title">{incident.title}</span>
                <small>{incident.id}</small>
              </th>
              <td>{categoryLabel(incident.category)}</td>
              <td><span className="status-badge">{incident.status}</span></td>
              <td>{incident.submittedAt ? new Date(incident.submittedAt).toLocaleString() : 'Draft'}</td>
              <td>{incident.anonymous ? 'Anonymous' : 'Named'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function IncidentForm({ onRefreshSession, onSubmitted }: {
  onRefreshSession: SessionRefresher
  onSubmitted: () => void
}) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState<IncidentSubmission['category'] | ''>('')
  const [anonymous, setAnonymous] = useState(false)
  const [submissionKey, setSubmissionKey] = useState(() => crypto.randomUUID())
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [feedback, setFeedback] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const form = useRef<HTMLFormElement>(null)

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFieldErrors({})
    setFeedback('')
    if (!category) {
      setFieldErrors({ category: 'Select a category.' })
      return
    }
    setSubmitting(true)
    try {
      await submitReporterIncident(
        { title, description, category, anonymous, submissionKey },
        onRefreshSession,
      )
      setTitle('')
      setDescription('')
      setCategory('')
      setAnonymous(false)
      setSubmissionKey(crypto.randomUUID())
      setFeedback('Incident submitted. Your report is now in the responder queue.')
      onSubmitted()
    } catch (error) {
      if (error instanceof ApiError && error.fieldErrors) setFieldErrors(error.fieldErrors)
      setFeedback(error instanceof Error ? error.message : 'The incident could not be submitted. Try again.')
      form.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
    } finally {
      setSubmitting(false)
    }
  }

  const hasFieldErrors = Object.keys(fieldErrors).length > 0

  return (
    <section className="submission-card" aria-labelledby="new-incident-heading">
      <div>
        <p className="eyebrow">New incident</p>
        <h2 id="new-incident-heading">What happened?</h2>
      </div>
      {feedback && (
        <p
          className={hasFieldErrors ? 'form-error' : 'form-success'}
          role={hasFieldErrors ? 'alert' : 'status'}
        >
          {feedback}
        </p>
      )}
      <form ref={form} className="incident-form" onSubmit={(event) => void submit(event)} noValidate>
        <label htmlFor="incident-title">Title</label>
        <input
          id="incident-title"
          required
          maxLength={200}
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          aria-invalid={Boolean(fieldErrors.title)}
          aria-describedby={fieldErrors.title ? 'incident-title-error' : undefined}
        />
        {fieldErrors.title && (
          <span className="field-error" id="incident-title-error">{fieldErrors.title}</span>
        )}

        <label htmlFor="incident-category">Category</label>
        <select
          id="incident-category"
          required
          value={category}
          onChange={(event) => setCategory(event.target.value as IncidentSubmission['category'] | '')}
          aria-invalid={Boolean(fieldErrors.category)}
          aria-describedby={fieldErrors.category ? 'incident-category-error' : undefined}
        >
          <option value="">Select a category</option>
          <option value="IT">IT</option>
          <option value="HUMAN_RELATIONS">Human Relations</option>
          <option value="FACILITIES">Facilities</option>
        </select>
        {fieldErrors.category && (
          <span className="field-error" id="incident-category-error">{fieldErrors.category}</span>
        )}

        <label htmlFor="incident-description">Description</label>
        <textarea
          id="incident-description"
          required
          maxLength={10000}
          rows={6}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          aria-invalid={Boolean(fieldErrors.description)}
          aria-describedby={fieldErrors.description ? 'incident-description-error' : undefined}
        />
        {fieldErrors.description && (
          <span className="field-error" id="incident-description-error">
            {fieldErrors.description}
          </span>
        )}

        <div className="anonymous-control">
          <input
            id="incident-anonymous"
            type="checkbox"
            checked={anonymous}
            onChange={(event) => setAnonymous(event.target.checked)}
            aria-describedby="anonymous-help"
          />
          <label htmlFor="incident-anonymous">Submit anonymously</label>
        </div>
        <p id="anonymous-help" className="help-text">
          Responders and normal administrator views will not show your identity. Hosting operators may
          still access protected records, and image contents or metadata may identify you.
        </p>
        <button className="primary-button" type="submit" disabled={submitting}>
          {submitting ? 'Submitting…' : 'Submit incident'}
        </button>
      </form>
    </section>
  )
}

function categoryLabel(category: string) {
  return category === 'HUMAN_RELATIONS' ? 'Human Relations' : category.charAt(0) + category.slice(1).toLowerCase()
}
