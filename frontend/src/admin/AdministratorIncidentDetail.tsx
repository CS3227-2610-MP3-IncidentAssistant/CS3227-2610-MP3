import { useCallback, useEffect, useRef, useState } from 'react'
import {
  addAdministratorIncidentComment,
  assignAdministratorIncident,
  loadAdministratorIncidentDetail,
  resolveAdministratorIncident,
  unassignAdministratorIncident,
  ApiError,
  type AdministratorIncidentDetail as AdministratorIncidentDetailModel,
  type EligibleResponder,
  type SessionRefresher,
} from '../api'
import { IncidentDetailView } from '../shared/incident/IncidentDetailView'
import type { IncidentDetailModel } from '../shared/incident/incidentDetail'

const MAX_COMMENT_LENGTH = 4_000
const MAX_RESOLUTION_REMARKS_LENGTH = 2_000
const PREFIX = 'administrator-incident'

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; detail: AdministratorIncidentDetailModel }
  | { kind: 'denied' }
  | { kind: 'unavailable' }

type MutationState =
  | { kind: 'idle' }
  | { kind: 'pending' }
  | { kind: 'conflict'; message: string }
  | { kind: 'error'; message: string }
  | { kind: 'success'; message: string }

/**
 * Administrator incident detail, composed from the shared role-neutral view.
 *
 * Every mutation sends the version the Administrator actually saw plus a
 * generated idempotency key, so a stale or duplicated submission is rejected or
 * de-duplicated by the server rather than trusted from this component.
 */
export function AdministratorIncidentDetail({ incidentId, onBack, refreshSession }: {
  incidentId: string
  onBack: () => void
  refreshSession: SessionRefresher
}) {
  const [state, setState] = useState<DetailState>({ kind: 'loading' })
  const [mutation, setMutation] = useState<MutationState>({ kind: 'idle' })
  // Bumping the revision re-runs the load effect without setting state in it.
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    loadAdministratorIncidentDetail(incidentId, controller.signal)
      .then((detail) => setState({ kind: 'ready', detail }))
      .catch((reason: unknown) => {
        if (controller.signal.aborted) return
        // A denied or missing incident is reported identically so neither
        // existence nor access is revealed.
        setState(reason instanceof ApiError && reason.status === 404
          ? { kind: 'denied' }
          : { kind: 'unavailable' })
      })
    return () => controller.abort()
  }, [incidentId, revision])

  const reload = useCallback(() => setRevision((current) => current + 1), [])

  /** Runs a mutation, then refreshes so the next action carries a fresh version. */
  async function runMutation(action: () => Promise<void>, successMessage: string) {
    setMutation({ kind: 'pending' })
    try {
      await action()
      setMutation({ kind: 'success', message: successMessage })
      reload()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setMutation({
          kind: 'conflict',
          message: 'This incident changed since it was loaded. The latest details have been reloaded.',
        })
        reload()
        return
      }
      setMutation({
        kind: 'error',
        message: error instanceof ApiError ? error.message : 'The update could not be saved. Try again.',
      })
    }
  }

  if (state.kind === 'loading') {
    return <section className="incident-panel" aria-labelledby={`${PREFIX}-detail-heading`}>
      <h2 className="panel-state" id={`${PREFIX}-detail-heading`} role="status">Loading incident details…</h2>
    </section>
  }
  if (state.kind === 'denied') {
    return <section className="incident-panel" aria-labelledby={`${PREFIX}-detail-heading`}>
      <div className="panel-state" role="alert">
        <h2 id={`${PREFIX}-detail-heading`}>Incident unavailable</h2>
        <p>This incident cannot be displayed. Return to the dashboard and refresh.</p>
        <button className="ghost-button" type="button" onClick={onBack}>Back to dashboard</button>
      </div>
    </section>
  }
  if (state.kind === 'unavailable') {
    return <section className="incident-panel" aria-labelledby={`${PREFIX}-detail-heading`}>
      <div className="panel-state" role="alert">
        <h2 id={`${PREFIX}-detail-heading`}>Details unavailable</h2>
        <p>Incident details are temporarily unavailable.</p>
        <button className="secondary-button" type="button" onClick={reload}>Try again</button>
        <button className="ghost-button" type="button" onClick={onBack}>Back to dashboard</button>
      </div>
    </section>
  }

  const { detail } = state
  const busy = mutation.kind === 'pending'
  return <IncidentDetailView
    detail={toSharedModel(detail)}
    prefix={PREFIX}
    onBack={onBack}
    backLabel="Back to dashboard"
    feedback={<MutationFeedback state={mutation} />}
    capabilities={{
      attachmentUrl: (attachmentId) =>
        `/api/v1/admin/incidents/${encodeURIComponent(detail.id)}/attachments/${encodeURIComponent(attachmentId)}`,
      primaryActions: <AssignmentActions
        detail={detail}
        busy={busy}
        onAssign={(responderId) => void runMutation(
          () => assignAdministratorIncident(detail.id, detail.version, responderId, refreshSession),
          'The responder assignment was saved.',
        )}
        onUnassign={() => void runMutation(
          () => unassignAdministratorIncident(detail.id, detail.version, refreshSession),
          'The incident was unassigned and returned to its category queue.',
        )}
      />,
      // Keyed on the revision so a successful mutation, which reloads, remounts
      // the composer empty. A failure does not reload, so the unsent text stays
      // available for another attempt.
      commentComposer: <CommentComposer
        key={`comment:${revision}`}
        busy={busy}
        onSubmit={(text, requestId) => void runMutation(
          () => addAdministratorIncidentComment(detail.id, requestId, text, refreshSession),
          'The comment was added.',
        )}
      />,
      resolutionForm: detail.status === 'ASSIGNED'
        ? <ResolutionForm
          busy={busy}
          onSubmit={(remarks, requestId) => void runMutation(
            () => resolveAdministratorIncident(detail.id, detail.version, requestId, remarks, refreshSession),
            'The incident was resolved.',
          )}
        />
        : null,
    }}
  />
}

/** Administrators read attachments but never add them, so no upload control. */
function toSharedModel(detail: AdministratorIncidentDetailModel): IncidentDetailModel {
  return {
    id: detail.id,
    title: detail.title,
    description: detail.description,
    category: detail.category,
    status: detail.status,
    reporterLabel: detail.reporterLabel,
    assigneeLabel: detail.assigneeLabel,
    version: detail.version,
    createdAt: detail.createdAt,
    submittedAt: detail.submittedAt,
    withdrawnAt: detail.withdrawnAt,
    queueEnteredAt: detail.queueEnteredAt,
    firstAssignedAt: detail.firstAssignedAt,
    latestAssignedAt: detail.latestAssignedAt,
    slo: detail.slo,
    resolutionHistory: detail.resolutionHistory,
    comments: detail.comments,
    attachments: detail.attachments,
  }
}

function MutationFeedback({ state }: { state: MutationState }) {
  if (state.kind === 'idle') return null
  if (state.kind === 'pending') {
    return <p className="panel-state" role="status">Saving…</p>
  }
  if (state.kind === 'success') {
    return <p className="form-success" role="status">{state.message}</p>
  }
  return <p className="form-error" role="alert">{state.message}</p>
}

function AssignmentActions({ detail, busy, onAssign, onUnassign }: {
  detail: AdministratorIncidentDetailModel
  busy: boolean
  onAssign: (responderId: string) => void
  onUnassign: () => void
}) {
  const [selected, setSelected] = useState('')
  const [confirmingUnassign, setConfirmingUnassign] = useState(false)
  const assigned = detail.status === 'ASSIGNED'
  const eligible = detail.eligibleResponders

  if (detail.status === 'RESOLVED' || detail.status === 'WITHDRAWN') return null

  return <section className="incident-detail__actions" aria-labelledby="administrator-incident-actions-heading">
    <h3 id="administrator-incident-actions-heading">Assignment</h3>
    {eligible.length === 0
      ? <p className="help-text">
        No enabled responder currently has access to this incident&apos;s category. Configure
        responder category access from Accounts before assigning this incident.
      </p>
      : <div className="incident-detail__assign">
        <label htmlFor="administrator-incident-responder">
          {assigned ? 'Reassign to responder' : 'Assign to responder'}
        </label>
        <select
          id="administrator-incident-responder"
          value={selected}
          disabled={busy}
          onChange={(event) => setSelected(event.target.value)}
        >
          <option value="">Select a responder</option>
          {eligible.map((responder: EligibleResponder) => <option key={responder.id} value={responder.id}>
            {responder.loginName}
          </option>)}
        </select>
        <button
          className="primary-button"
          type="button"
          disabled={busy || selected === ''}
          onClick={() => onAssign(selected)}
        >
          {assigned ? 'Reassign incident' : 'Assign incident'}
        </button>
      </div>}

    {assigned && <>
      <button
        className="danger-outline-button"
        type="button"
        disabled={busy}
        onClick={() => setConfirmingUnassign(true)}
      >
        Unassign incident
      </button>
      <UnassignDialog
        open={confirmingUnassign}
        assigneeLabel={detail.assigneeLabel}
        busy={busy}
        onCancel={() => setConfirmingUnassign(false)}
        onConfirm={() => { setConfirmingUnassign(false); onUnassign() }}
      />
    </>}
  </section>
}

/** Destructive action, so the consequence is stated before submission. */
function UnassignDialog({ open, assigneeLabel, busy, onCancel, onConfirm }: {
  open: boolean
  assigneeLabel: string | null
  busy: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (open && !element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    } else if (!open && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [open])

  return <dialog
    aria-describedby="administrator-unassign-description"
    aria-labelledby="administrator-unassign-title"
    className="confirmation-dialog"
    ref={dialog}
    onCancel={(event) => { event.preventDefault(); if (!busy) onCancel() }}
  >
    <div className="confirmation-dialog__content">
      <p className="eyebrow">Assignment</p>
      <h2 id="administrator-unassign-title">Unassign this incident?</h2>
      <p id="administrator-unassign-description">
        {assigneeLabel?.trim() ? `${assigneeLabel} will no longer be assigned. ` : ''}
        The incident returns to Submitted and keeps its original position in its
        category queue.
      </p>
      <div className="confirmation-dialog__actions">
        <button className="ghost-button" type="button" disabled={busy} onClick={onCancel}>Cancel</button>
        <button className="danger-button" type="button" disabled={busy} onClick={onConfirm}>
          Unassign incident
        </button>
      </div>
    </div>
  </dialog>
}

function CommentComposer({ busy, onSubmit }: {
  busy: boolean
  onSubmit: (text: string, requestId: string) => void
}) {
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  // A stable id per composed comment makes a retry idempotent server-side.
  const [requestId, setRequestId] = useState(() => crypto.randomUUID())
  const length = [...text].length

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (text.trim().length === 0) {
      setError('Enter comment text before adding a comment.')
      return
    }
    if (length > MAX_COMMENT_LENGTH) {
      setError(`Comments must be ${MAX_COMMENT_LENGTH.toLocaleString()} characters or fewer.`)
      return
    }
    setError(null)
    onSubmit(text, requestId)
    // Unsent text is kept until the mutation is known to have succeeded; the
    // parent reloads on success, which remounts this composer empty.
    setRequestId(crypto.randomUUID())
  }

  return <form className="incident-detail__comment-form" onSubmit={submit}>
    <label htmlFor="administrator-comment-text">Add a comment</label>
    <textarea
      id="administrator-comment-text"
      value={text}
      disabled={busy}
      aria-invalid={error ? 'true' : undefined}
      aria-describedby="administrator-comment-count administrator-comment-error"
      onChange={(event) => { setText(event.target.value); setError(null) }}
    />
    <p id="administrator-comment-count">
      {length.toLocaleString()} / {MAX_COMMENT_LENGTH.toLocaleString()} characters
    </p>
    <p id="administrator-comment-error" role={error ? 'alert' : undefined}>{error}</p>
    <button className="primary-button" type="submit" disabled={busy}>
      {busy ? 'Adding…' : 'Add comment'}
    </button>
  </form>
}

function ResolutionForm({ busy, onSubmit }: {
  busy: boolean
  onSubmit: (remarks: string, requestId: string) => void
}) {
  const [remarks, setRemarks] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [requestId, setRequestId] = useState(() => crypto.randomUUID())
  const length = [...remarks].length

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (remarks.trim().length === 0) {
      setError('Enter resolution remarks before resolving this incident.')
      return
    }
    if (length > MAX_RESOLUTION_REMARKS_LENGTH) {
      setError(`Resolution remarks must be ${MAX_RESOLUTION_REMARKS_LENGTH.toLocaleString()} characters or fewer.`)
      return
    }
    setError(null)
    onSubmit(remarks, requestId)
    setRequestId(crypto.randomUUID())
  }

  return <form className="incident-detail__resolution-form" onSubmit={submit}>
    <h3>Resolve incident</h3>
    <label htmlFor="administrator-resolution-remarks">Resolution remarks</label>
    <textarea
      id="administrator-resolution-remarks"
      value={remarks}
      disabled={busy}
      aria-invalid={error ? 'true' : undefined}
      aria-describedby="administrator-resolution-count administrator-resolution-error"
      onChange={(event) => { setRemarks(event.target.value); setError(null) }}
    />
    <p id="administrator-resolution-count">
      {length.toLocaleString()} / {MAX_RESOLUTION_REMARKS_LENGTH.toLocaleString()} characters
    </p>
    <p id="administrator-resolution-error" role={error ? 'alert' : undefined}>{error}</p>
    <button className="primary-button" type="submit" disabled={busy}>
      {busy ? 'Resolving…' : 'Resolve incident'}
    </button>
  </form>
}
