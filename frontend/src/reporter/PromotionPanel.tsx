import { useEffect, useState } from 'react'
import {
  ApiError,
  loadPromotionHistory,
  submitPromotionRequest,
  type IncidentCategory,
  type PromotionRequestRecord,
  type SessionRefresher,
} from '../api'
import { Pagination } from '../shared/Pagination'
import { incidentCategoryNames as categoryNames } from '../shared/incident/incidentDetail'

const categories: IncidentCategory[] = ['IT', 'HUMAN_RELATIONS', 'FACILITIES']

export function PromotionPanel({ onRefreshSession }: { onRefreshSession: SessionRefresher }) {
  const [requests, setRequests] = useState<PromotionRequestRecord[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [totalElements, setTotalElements] = useState(0)
  const [hasPendingRequest, setHasPendingRequest] = useState(false)
  const [selectedCategories, setSelectedCategories] = useState<IncidentCategory[]>([])
  const [comments, setComments] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [feedback, setFeedback] = useState<{ message: string; error: boolean } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    loadPromotionHistory(page, controller.signal)
      .then((history) => {
        setRequests(history.requests)
        setTotalPages(Math.max(1, history.totalPages))
        setTotalElements(history.totalElements)
        if (page === 0) {
          setHasPendingRequest(history.requests.some((request) => request.status === 'PENDING'))
        }
        setLoadError(false)
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) setLoadError(true)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [page, revision])

  function reload() {
    setLoading(true)
    setLoadError(false)
    setRevision((current) => current + 1)
  }

  function loadPage(nextPage: number) {
    setLoading(true)
    setLoadError(false)
    setPage(nextPage)
  }

  function toggleCategory(category: IncidentCategory) {
    setSelectedCategories((selected) => selected.includes(category)
      ? selected.filter((value) => value !== category)
      : [...selected, category])
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFeedback(null)
    if (selectedCategories.length === 0) {
      setFeedback({ message: 'Select at least one category.', error: true })
      return
    }
    setSubmitting(true)
    try {
      await submitPromotionRequest(selectedCategories, comments, onRefreshSession)
      setSelectedCategories([])
      setComments('')
      setFeedback({ message: 'Your promotion request was submitted.', error: false })
      setPage(0)
      reload()
    } catch (error) {
      const message = error instanceof ApiError && (error.status === 401 || error.code === 'UNAUTHENTICATED')
        ? 'Your session has expired. Sign in again before submitting a request.'
        : error instanceof ApiError && error.status === 409
        ? 'A promotion request is already pending. Refresh to see its status.'
        : error instanceof Error
          ? error.message
          : 'The promotion request could not be submitted. Try again.'
      setFeedback({ message, error: true })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <header className="page-header">
        <div>
          <p className="eyebrow">Reporter workspace</p>
          <h1>Responder promotion</h1>
          <p>Request access to the incident categories you are qualified to handle.</p>
        </div>
      </header>
      <section className="submission-card" aria-labelledby="promotion-request-heading">
        <div>
          <p className="eyebrow">New request</p>
          <h2 id="promotion-request-heading">Request Responder access</h2>
          <p>Select one or more categories. An Administrator may approve a subset.</p>
        </div>
        {feedback && (
          <p className={feedback.error ? 'form-error' : 'form-success'} role={feedback.error ? 'alert' : 'status'}>
            {feedback.message}
          </p>
        )}
        {loading ? (
          <p role="status">Checking for a pending request…</p>
        ) : loadError ? (
          <p role="alert">Request history must load before you can submit another request.</p>
        ) : hasPendingRequest ? (
          <p role="status">You already have a pending request. You can submit another after it is decided.</p>
        ) : (
          <form className="incident-form" onSubmit={(event) => void submit(event)}>
            <fieldset className="category-choice">
              <legend>Requested categories</legend>
              {categories.map((category) => (
                <label key={category}>
                  <input
                    type="checkbox"
                    checked={selectedCategories.includes(category)}
                    onChange={() => toggleCategory(category)}
                  />
                  {categoryNames[category]}
                </label>
              ))}
            </fieldset>
            <label htmlFor="promotion-comments">Additional comments (optional)</label>
            <textarea
              id="promotion-comments"
              maxLength={2000}
              rows={4}
              value={comments}
              onChange={(event) => setComments(event.target.value)}
              aria-describedby="promotion-comments-help"
            />
            <p className="help-text" id="promotion-comments-help">
              {comments.length.toLocaleString()} of 2,000 characters.
            </p>
            <button className="primary-button" type="submit" disabled={submitting}>
              {submitting ? 'Submitting…' : 'Submit request'}
            </button>
          </form>
        )}
      </section>
      <section className="incident-panel" aria-labelledby="promotion-history-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Your requests</p>
            <h2 id="promotion-history-heading">Request history</h2>
          </div>
          <div className="section-heading__actions">
            <span className="data-state">{totalElements} requests</span>
            <button className="secondary-button" type="button" onClick={reload}>Refresh</button>
          </div>
        </div>
        {loading && <p className="panel-state" role="status">Loading request history…</p>}
        {!loading && loadError && (
          <div className="panel-state" role="alert">
            Request history is temporarily unavailable. Refresh to try again.
          </div>
        )}
        {!loading && !loadError && requests.length === 0 && (
          <div className="empty-state">
            <h3>No promotion requests</h3>
            <p>Your submitted requests and decisions will appear here.</p>
          </div>
        )}
        {!loading && !loadError && requests.length > 0 && (
          <div className="table-region" role="region" aria-label="Promotion request history" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Submitted</th>
                  <th scope="col">Requested</th>
                  <th scope="col">Comments</th>
                  <th scope="col">Approved</th>
                  <th scope="col">Status</th>
                  <th scope="col">Decided</th>
                </tr>
              </thead>
              <tbody>
                {requests.map((request) => (
                  <tr key={request.requestId}>
                    <td>{new Date(request.requestedAt).toLocaleString()}</td>
                    <td>{request.requestedCategories.map((category) => categoryNames[category]).join(', ')}</td>
                    <td className="promotion-comment">{request.comments ?? 'None'}</td>
                    <td>{request.approvedCategories === null
                      ? 'Pending decision'
                      : request.approvedCategories.map((category) => categoryNames[category]).join(', ') || 'None'}</td>
                    <td><span className="status-badge">{request.status}</span></td>
                    <td>{request.decidedAt ? new Date(request.decidedAt).toLocaleString() : 'Pending'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {!loadError && (
          <Pagination
            page={page}
            totalPages={totalPages}
            ariaLabel="Promotion request history pages"
            disabled={loading}
            onPageChange={loadPage}
          />
        )}
      </section>
    </>
  )
}
