import { useEffect, useRef, useState } from 'react'
import {
  ApiError,
  decidePromotionRequest,
  loadPromotionRequests,
  type IncidentCategory,
  type PromotionRequestRecord,
  type SessionRefresher,
} from '../api'
import { Pagination } from '../shared/Pagination'

const categories: IncidentCategory[] = ['IT', 'HUMAN_RELATIONS', 'FACILITIES']
const categoryNames: Record<IncidentCategory, string> = {
  IT: 'IT',
  HUMAN_RELATIONS: 'Human relations',
  FACILITIES: 'Facilities',
}

type PendingDecision = {
  request: PromotionRequestRecord
  decision: 'APPROVE' | 'REJECT'
}

export function PromotionRequests({ onRefreshSession }: { onRefreshSession: SessionRefresher }) {
  const [requests, setRequests] = useState<PromotionRequestRecord[]>([])
  const [selectedByRequest, setSelectedByRequest] = useState<Record<string, IncidentCategory[]>>({})
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [pendingDecision, setPendingDecision] = useState<PendingDecision | null>(null)
  const [submittingId, setSubmittingId] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const returnFocus = useRef<HTMLButtonElement | null>(null)
  const feedbackRegion = useRef<HTMLParagraphElement | null>(null)

  useEffect(() => {
    if (feedback) feedbackRegion.current?.focus()
  }, [feedback])

  useEffect(() => {
    const controller = new AbortController()
    loadPromotionRequests(page, controller.signal)
      .then((result) => {
        setRequests(result.requests)
        setTotalPages(Math.max(1, result.totalPages))
        setSelectedByRequest((current) => {
          const next = { ...current }
          for (const request of result.requests) {
            next[request.requestId] ??= [...request.requestedCategories]
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

  function requestDecision(
    request: PromotionRequestRecord,
    decision: 'APPROVE' | 'REJECT',
    trigger: HTMLButtonElement,
  ) {
    const approvedCategories = selectedByRequest[request.requestId] ?? []
    setActionError('')
    setFeedback('')
    if (decision === 'APPROVE' && approvedCategories.length === 0) {
      setActionError(`Select at least one category for ${request.requesterLabel ?? 'this request'}.`)
      return
    }
    returnFocus.current = trigger
    setPendingDecision({ request, decision })
  }

  function closeConfirmation() {
    if (submittingId) return
    setPendingDecision(null)
    setActionError('')
    window.setTimeout(() => returnFocus.current?.focus(), 0)
  }

  async function decide() {
    if (!pendingDecision) return
    const { request, decision } = pendingDecision
    const approvedCategories = selectedByRequest[request.requestId] ?? []
    setSubmittingId(request.requestId)
    try {
      await decidePromotionRequest(request.requestId, decision, approvedCategories, onRefreshSession)
      setPendingDecision(null)
      setFeedback(decision === 'APPROVE'
        ? `${request.requesterLabel ?? 'The Reporter'} was promoted with ${approvedCategories.map((category) => categoryNames[category]).join(', ')} access.`
        : `${request.requesterLabel ?? 'The Reporter'}'s promotion request was rejected.`)
      reload()
    } catch (error) {
      setActionError(error instanceof ApiError && (error.status === 401 || error.code === 'UNAUTHENTICATED')
        ? 'Your session has expired. Sign in again before deciding this request.'
        : error instanceof ApiError && (error.status === 409 || error.status === 404)
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
      {actionError && !pendingDecision && <p className="form-error" role="alert">{actionError}</p>}
      {feedback && (
        <p className="form-success" role="status" ref={feedbackRegion} tabIndex={-1}>{feedback}</p>
      )}
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
                onClick={(event) => requestDecision(request, 'APPROVE', event.currentTarget)}
              >
                {isSubmitting ? 'Saving…' : 'Approve selected categories'}
              </button>
              <button
                className="secondary-button"
                type="button"
                disabled={isSubmitting}
                onClick={(event) => requestDecision(request, 'REJECT', event.currentTarget)}
              >
                Reject
              </button>
            </div>
          </section>
        )
      })}
      {!loadError && <Pagination page={page} totalPages={totalPages} ariaLabel="Promotion request pages"
        disabled={loading} onPageChange={setPage} />}
      <PromotionDecisionDialog
        pendingDecision={pendingDecision}
        selectedCategories={pendingDecision
          ? selectedByRequest[pendingDecision.request.requestId] ?? []
          : []}
        error={actionError}
        submitting={submittingId !== null}
        onCancel={closeConfirmation}
        onConfirm={() => void decide()}
      />
    </section>
  )
}

function PromotionDecisionDialog({ pendingDecision, selectedCategories, error, submitting, onCancel, onConfirm }: {
  pendingDecision: PendingDecision | null
  selectedCategories: IncidentCategory[]
  error: string
  submitting: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (pendingDecision && !element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    } else if (!pendingDecision && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [pendingDecision])

  const requester = pendingDecision?.request.requesterLabel ?? 'this Reporter'
  const approving = pendingDecision?.decision === 'APPROVE'
  const categoryList = selectedCategories.map((category) => categoryNames[category]).join(', ')

  return <dialog
    aria-describedby="promotion-decision-description"
    aria-labelledby="promotion-decision-title"
    className="confirmation-dialog"
    ref={dialog}
    onCancel={(event) => {
      event.preventDefault()
      onCancel()
    }}
  >
    {pendingDecision && <div className="confirmation-dialog__content">
      <p className="eyebrow">Promotion decision</p>
      <h2 id="promotion-decision-title">{approving ? 'Confirm promotion' : 'Confirm rejection'}</h2>
      <p id="promotion-decision-description">
        {approving
          ? `Promote ${requester} and grant exactly ${categoryList} access? Their Reporter sessions will end, and they must sign in through the Responder portal.`
          : `Reject ${requester}'s request? The request will close without changing their role or category access.`}
      </p>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="confirmation-dialog__actions">
        <button className="ghost-button" type="button" disabled={submitting} onClick={onCancel}>Cancel</button>
        <button
          className={approving ? 'primary-button' : 'danger-button'}
          type="button"
          disabled={submitting}
          onClick={onConfirm}
        >
          {submitting ? 'Saving…' : approving ? 'Confirm promotion' : 'Confirm rejection'}
        </button>
      </div>
    </div>}
  </dialog>
}
