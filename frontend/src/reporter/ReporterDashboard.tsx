import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  loadReporterIncidents,
  improveReporterDescription,
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

  const [aiLoading, setAiLoading] = useState(false)
  const [aiSuggestion, setAiSuggestion] = useState('')
  const [aiError, setAiError] = useState('')
  const aiRequest = useRef<AbortController | null>(null)
  const aiGeneration = useRef(0)

  function discardAi() {
    aiGeneration.current++
    aiRequest.current?.abort()
    aiRequest.current = null
    setAiLoading(false)
    setAiSuggestion('')
    setAiError('')
  }

  useEffect(() => () => {
    aiGeneration.current++
    aiRequest.current?.abort()
  }, [])

  async function improveDescription() {
    if (!category || !title.trim() || !description.trim() || submitting || aiLoading) return
    discardAi()
    const generation = aiGeneration.current
    const controller = new AbortController()
    aiRequest.current = controller
    setAiLoading(true)
    try {
      const suggestion = await improveReporterDescription({ title, category, description }, onRefreshSession, controller.signal)
      if (generation === aiGeneration.current && !controller.signal.aborted) setAiSuggestion(suggestion)
    } catch (error: unknown) {
      if (generation === aiGeneration.current && !controller.signal.aborted) {
        setAiError(error instanceof ApiError ? error.message : 'Description assistance is unavailable. You can still submit your own description.')
      }
    } finally {
      if (generation === aiGeneration.current) {
        aiRequest.current = null
        setAiLoading(false)
      }
    }
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    discardAi()
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
          onChange={(event) => { discardAi(); setTitle(event.target.value) }}
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
          onChange={(event) => { discardAi(); setCategory(event.target.value as IncidentSubmission['category'] | '') }}
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
          onChange={(event) => { discardAi(); setDescription(event.target.value) }}
          aria-invalid={Boolean(fieldErrors.description)}
          aria-describedby={fieldErrors.description ? 'incident-description-error' : undefined}
        />
        {fieldErrors.description && (
          <span className="field-error" id="incident-description-error">
            {fieldErrors.description}
          </span>
        )}

        <div className="ai-description-actions">
          <button
            className="secondary-button"
            type="button"
            disabled={!title.trim() || !category || !description.trim() || submitting || aiLoading}
            onClick={() => void improveDescription()}
          >Improve description</button>
          {aiLoading && <button className="secondary-button" type="button" onClick={discardAi}>Cancel</button>}
        </div>
        <p className="help-text">Optional AI assistance sends the title, category, and description for processing. Review the suggestion before applying it.</p>
        {aiLoading && <p role="status">Preparing a suggestion. You can still submit your incident.</p>}
        {aiError && <p className="form-error" role="alert">{aiError}</p>}
        {aiSuggestion && (
          <section className="ai-description-review" aria-labelledby="ai-description-heading">
            <h3 id="ai-description-heading">AI-generated suggestion</h3>
            <p className="help-text">Check the facts before applying. Applying changes only this form.</p>
            <p className="ai-description-text">{aiSuggestion}</p>
            <div className="ai-description-actions">
              <button className="secondary-button" type="button" onClick={() => { setDescription(aiSuggestion); discardAi() }}>Apply suggestion</button>
              <button className="secondary-button" type="button" onClick={discardAi}>Discard</button>
            </div>
          </section>
        )}

        <div className="anonymous-control">
          <input
            id="incident-anonymous"
            type="checkbox"
            checked={anonymous}
            onChange={(event) => { discardAi(); setAnonymous(event.target.checked) }}
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
