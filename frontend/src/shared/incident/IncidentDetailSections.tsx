import {
  attachmentFormatLabel,
  formatInstant,
  formatMillisDuration,
  formatMinutes,
  incidentLifecycleLabel,
  incidentRoleName,
  incidentSloStateLabel,
  type IncidentAttachmentEntry,
  type IncidentCommentEntry,
  type IncidentLifecycleEntry,
  type IncidentResolutionEntry,
  type IncidentSloProgress,
  type IncidentSloState,
} from './incidentDetail'

/**
 * Presentation-only incident detail sections shared by every role portal.
 *
 * These components never load protected records, never infer identity, and
 * never decide what a role may do. The owning portal passes already-authorized
 * data and an explicit attachment URL builder for its own role's endpoint.
 */

export function IncidentDescription({ description, prefix }: {
  description: string
  prefix: string
}) {
  return <section className="incident-detail__description" aria-labelledby={`${prefix}-description-heading`}>
    <h3 id={`${prefix}-description-heading`}>Description</h3>
    {/* Deliberate reporter whitespace is preserved by CSS, not by markup. */}
    <p className="incident-detail__text">{description}</p>
  </section>
}

export function AttachmentList({ attachments, prefix, attachmentUrl }: {
  attachments: IncidentAttachmentEntry[]
  prefix: string
  /** Role-specific authorized download endpoint for one attachment. */
  attachmentUrl: (attachmentId: string) => string
}) {
  if (attachments.length === 0) return null
  return <section className="incident-detail__attachments" aria-labelledby={`${prefix}-attachments-heading`}>
    <h3 id={`${prefix}-attachments-heading`}>Attachments</h3>
    <ul>
      {attachments.map((attachment, index) => <li key={attachment.id}>
        <a href={attachmentUrl(attachment.id)}>
          {attachment.displayName?.trim() ? attachment.displayName : `Attachment ${index + 1}`}
          {' '}({attachmentFormatLabel(attachment.mediaType)})
        </a>
      </li>)}
    </ul>
  </section>
}

export function IncidentComments({ comments, prefix }: {
  comments: IncidentCommentEntry[]
  prefix: string
}) {
  return <section className="incident-detail__comments" aria-labelledby={`${prefix}-comments-heading`}>
    <h3 id={`${prefix}-comments-heading`}>Comments</h3>
    {comments.length === 0
      ? <p className="help-text">No comments yet.</p>
      : <ol className="incident-comment-list">
        {comments.map((comment, index) => <li key={comment.id ?? `${comment.createdAt}-${index}`}>
          <p><strong>{comment.authorLabel}</strong> · {incidentRoleName(comment.authorRole)}</p>
          <p className="incident-detail__text">{comment.text}</p>
          <time dateTime={comment.createdAt}>{formatInstant(comment.createdAt)}</time>
        </li>)}
      </ol>}
  </section>
}

export function LifecycleHistory({ history, prefix }: {
  history: IncidentLifecycleEntry[]
  prefix: string
}) {
  if (history.length === 0) return null
  return <section className="incident-detail__lifecycle" aria-labelledby={`${prefix}-lifecycle-heading`}>
    <h3 id={`${prefix}-lifecycle-heading`}>Incident history</h3>
    <ol>
      {history.map((entry, index) => <li key={`${entry.occurredAt}-${entry.action}-${index}`}>
        <p>{incidentLifecycleLabel(entry.action)}{entry.fromStatus && entry.toStatus
          ? `: ${entry.fromStatus} → ${entry.toStatus}` : ''}</p>
        <time dateTime={entry.occurredAt}>{formatInstant(entry.occurredAt)}</time>
      </li>)}
    </ol>
  </section>
}

export function ResolutionHistory({ history, prefix }: {
  history: IncidentResolutionEntry[]
  prefix: string
}) {
  if (history.length === 0) {
    return <section className="incident-detail__resolutions" aria-labelledby={`${prefix}-resolution-history-heading`}>
      <h3 id={`${prefix}-resolution-history-heading`}>Resolution history</h3>
      <p className="help-text">Resolution remarks will appear here after an incident is resolved.</p>
    </section>
  }
  return <section className="incident-detail__resolutions" aria-labelledby={`${prefix}-resolution-history-heading`}>
    <h3 id={`${prefix}-resolution-history-heading`}>Resolution history</h3>
    <ol>
      {history.map((entry) => <li key={entry.cycleNumber}>
        <h4>Resolution cycle {entry.cycleNumber}</h4>
        <p className="incident-detail__resolution-date">
          <time dateTime={entry.resolvedAt}>{formatInstant(entry.resolvedAt)}</time>
          {entry.resolvedByLabel ? <> · Resolved by {entry.resolvedByLabel}</> : null}
        </p>
        <p className="incident-detail__text">{entry.remarks}</p>
      </li>)}
    </ol>
  </section>
}

export function SloProgressSection({ slo, prefix }: {
  slo: IncidentSloProgress
  prefix: string
}) {
  return <section className="incident-detail__slo" aria-labelledby={`${prefix}-slo-heading`}>
    <h3 id={`${prefix}-slo-heading`}>Service-level objective</h3>
    {slo.claimState === 'NOT_CONFIGURED' && slo.progressState === 'NOT_CONFIGURED'
      ? <p className="help-text">No service-level objective is configured for this category.</p>
      : <dl className="incident-detail__slo-grid">
        <SloMeasure
          label="Time to claim"
          state={slo.claimState}
          targetMinutes={slo.timeToClaimTargetMinutes}
          elapsedMillis={slo.timeToClaimElapsedMillis}
        />
        <SloMeasure
          label="Time in progress"
          state={slo.progressState}
          targetMinutes={slo.timeInProgressTargetMinutes}
          elapsedMillis={slo.timeInProgressElapsedMillis}
        />
      </dl>}
  </section>
}

function SloMeasure({ label, state, targetMinutes, elapsedMillis }: {
  label: string
  state: IncidentSloState
  targetMinutes: number | null
  elapsedMillis: number | null
}) {
  // Text carries the state; colour is a secondary cue only.
  const toneClass = state === 'OVERDUE'
    ? 'status-badge status-badge--danger'
    : state === 'WITHIN_TARGET' ? 'status-badge status-badge--positive' : 'status-badge'
  return <div>
    <dt>{label}</dt>
    <dd>
      <span className={toneClass}>{incidentSloStateLabel(state)}</span>
      <span className="incident-detail__slo-values">
        {elapsedMillis === null ? 'Not started' : formatMillisDuration(elapsedMillis)}
        {targetMinutes === null ? '' : ` of ${formatMinutes(targetMinutes)} target`}
      </span>
    </dd>
  </div>
}
