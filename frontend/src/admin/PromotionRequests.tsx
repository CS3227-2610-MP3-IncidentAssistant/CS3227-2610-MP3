import { useEffect, useState } from 'react'
import {
  ApiError,
  decidePromotionRequest,
  loadPromotionRequests,
  type IncidentCategory,
  type PromotionRequestRecord,
  type SessionRefresher,
} from '../api'

const categories: IncidentCategory[] = ['IT', 'HUMAN_RELATIONS', 'FACILITIES']
const categoryNames: Record<IncidentCategory, string> = {
  IT: 'IT',
  HUMAN_RELATIONS: 'Human relations',
  FACILITIES: 'Facilities',
}

export function PromotionRequests({ onRefreshSession }: { onRefreshSession: SessionRefresher }) {
  const [requests, setRequests] = useState<PromotionRequestRecord[]>([])
  const [selectedByRequest, setSelectedByRequest] = useState<Record<string, IncidentCategory[]>>({})
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [actionError, setActionError] = useState('')
  const [submittingId, setSubmittingId] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    loadPromotionRequests(page, controller.signal)
      .then((result) => {
        setRequests(result.requests)
        setTotalPages(Math.max(1, result.totalPages))
        setSelectedByRequest((current) => {
          const next = { ...current }
          for (const request of result.requests) {
            next[request.requestId] ??= []
          }
          return next
        })
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

  function toggleCategory(request: PromotionRequestRecord, category: IncidentCategory) {
    if (!request.requestedCategories.includes(category)) return
    setSelectedByRequest((current) => {
      const selected = current[request.requestId] ?? []
      return {
        ...current,
        [request.requestId]: selected.includes(category)
          ? selected.filter((value) => value !== category)
          : [...selected, category],
      }
    })
  }

  async function decide(request: PromotionRequestRecord, decision: 'APPROVE' | 'REJECT') {
    const approvedCategories = selectedByRequest[request.requestId] ?? []
    setActionError('')
    if (decision === 'APPROVE' && approvedCategories.length === 0) {
      setActionError(`Select at least one category for ${request.requesterLabel ?? 'this request'}.`)
      return
    }
    setSubmittingId(request.requestId)
    try {
      await decidePromotionRequest(request.requestId, decision, approvedCategories, onRefreshSession)
      reload()
    } catch (error) {
      setActionError(error instanceof ApiError && (error.status === 409 || error.status === 404)
        ? 'This request has already changed. Refresh the list before deciding again.'
        : error instanceof Error
          ? error.message
          : 'The decision could not be saved. Try again.')
    } finally {
      setSubmittingId(null)
    }
  }

  return (
    <section className="promotion-requests" aria-labelledby="promotion-requests-heading">
      <header className="section-heading">
        <div>
          <p className="eyebrow">Access review</p>
          <h2 id="promotion-requests-heading">Promotion requests</h2>
          <p>Review Reporter requests and approve only the categories they requested.</p>
        </div>
        <button
          className="secondary-button"
          type="button"
          onClick={reload}
        >
          Refresh
        </button>
      </header>
      {actionError && <p className="form-error" role="alert">{actionError}</p>}
      {loading && <p className="panel-state" role="status">Loading promotion requests…</p>}
      {!loading && loadError && (
        <div className="panel-state" role="alert">
          Promotion requests are temporarily unavailable. Refresh to try again.
        </div>
      )}
      {!loading && !loadError && requests.length === 0 && (
        <div className="empty-state">
          <h2>No pending promotion requests</h2>
          <p>New requests will appear here for review.</p>
        </div>
      )}
      {!loading && !loadError && requests.map((request) => {
        const selected = selectedByRequest[request.requestId] ?? []
        const isSubmitting = submittingId === request.requestId
        return (
          <section className="submission-card" key={request.requestId} aria-labelledby={`request-${request.requestId}`}>
            <div>
              <p className="eyebrow">Pending since {new Date(request.requestedAt).toLocaleString()}</p>
              <h2 id={`request-${request.requestId}`}>{request.requesterLabel ?? 'Reporter'}</h2>
              <p>Requested categories: {request.requestedCategories.map((category) => categoryNames[category]).join(', ')}</p>
              {request.comments && <p>Additional comments: {request.comments}</p>}
            </div>
            <fieldset className="category-choice">
              <legend>Categories to grant</legend>
              {categories.filter((category) => request.requestedCategories.includes(category)).map((category) => (
                <label key={category}>
                  <input
                    type="checkbox"
                    checked={selected.includes(category)}
                    disabled={isSubmitting}
                    onChange={() => toggleCategory(request, category)}
                  />
                  {categoryNames[category]}
                </label>
              ))}
            </fieldset>
            <div className="form-actions">
              <button
                className="primary-button"
                type="button"
                disabled={isSubmitting}
                onClick={() => void decide(request, 'APPROVE')}
              >
                {isSubmitting ? 'Saving…' : 'Approve selected categories'}
              </button>
              <button
                className="secondary-button"
                type="button"
                disabled={isSubmitting}
                onClick={() => void decide(request, 'REJECT')}
              >
                Reject
              </button>
            </div>
          </section>
        )
      })}
      {!loading && !loadError && totalPages > 1 && (
        <nav className="pagination" aria-label="Promotion request pages">
          <button type="button" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>
            Previous
          </button>
          <span>Page {page + 1} of {totalPages}</span>
          <button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage((current) => current + 1)}>
            Next
          </button>
        </nav>
      )}
    </section>
  )
}
