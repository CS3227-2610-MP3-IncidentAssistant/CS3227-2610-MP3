import type { ReactNode } from 'react'
import {
  AttachmentList,
  IncidentComments,
  IncidentDescription,
  LifecycleHistory,
  ResolutionHistory,
  SloProgressSection,
} from './IncidentDetailSections'
import {
  formatInstant,
  incidentCategoryName,
  incidentStatusLabel,
  type IncidentDetailModel,
} from './incidentDetail'

/**
 * The role-neutral incident detail view, mirroring the MP2 desktop
 * `ui/shared/components/IncidentDetailView` and its `IncidentDetailActions`:
 * one presentation shell whose role-specific workflow controls are supplied by
 * the owning portal.
 *
 * Omitting a capability hides its control. That is a presentation convenience
 * only — the Java backend reauthorizes every read and mutation, so hiding a
 * control is never the authorization boundary.
 *
 * The MP2 "Audit timeline" placeholder section is deliberately not reproduced:
 * audit records stay internal-only with no HTTP read endpoint.
 */
export type IncidentDetailCapabilities = {
  /** Rendered beneath the metadata, e.g. assign/reassign, unassign, claim. */
  primaryActions?: ReactNode
  /** Rendered after the history sections, e.g. a resolution form. */
  resolutionForm?: ReactNode
  /** Rendered after the comment thread, e.g. a comment composer. */
  commentComposer?: ReactNode
  /** Role-specific authorized attachment endpoint. Omit to hide attachments. */
  attachmentUrl?: (attachmentId: string) => string
  /** Shows the lifecycle timeline when the role's API returns one. */
  showLifecycleHistory?: boolean
}

export function IncidentDetailView({ detail, prefix, capabilities, onBack, backLabel, feedback }: {
  detail: IncidentDetailModel
  /** Unique id prefix so headings stay unique within the page. */
  prefix: string
  capabilities: IncidentDetailCapabilities
  onBack?: () => void
  backLabel?: string
  /** Live-region feedback for the most recent mutation attempt. */
  feedback?: ReactNode
}) {
  const attachments = detail.attachments ?? []
  const lifecycleHistory = detail.lifecycleHistory ?? []
  return <section className="incident-panel incident-detail" aria-labelledby={`${prefix}-detail-heading`}>
    {onBack && <button className="ghost-button" type="button" onClick={onBack}>
      {backLabel ?? 'Back'}
    </button>}
    <div className="section-heading">
      <div>
        <p className="eyebrow">
          {incidentCategoryName(detail.category)} · {incidentStatusLabel(detail.status)}
        </p>
        <h2 id={`${prefix}-detail-heading`}>{detail.title}</h2>
        <p className="help-text">Incident {detail.id}</p>
      </div>
    </div>

    <IncidentMetadata detail={detail} />

    {capabilities.primaryActions}
    {feedback}

    <IncidentDescription description={detail.description} prefix={prefix} />

    {detail.slo ? <SloProgressSection slo={detail.slo} prefix={prefix} /> : null}

    {capabilities.attachmentUrl
      ? <AttachmentList
        attachments={attachments}
        prefix={prefix}
        attachmentUrl={capabilities.attachmentUrl}
      />
      : null}

    <IncidentComments comments={detail.comments ?? []} prefix={prefix} />
    {capabilities.commentComposer}

    {capabilities.showLifecycleHistory
      ? <LifecycleHistory history={lifecycleHistory} prefix={prefix} />
      : null}

    <ResolutionHistory history={detail.resolutionHistory ?? []} prefix={prefix} />
    {capabilities.resolutionForm}
  </section>
}

/** Lifecycle facts, rendered only where the role's API supplies them. */
function IncidentMetadata({ detail }: { detail: IncidentDetailModel }) {
  const entries: Array<[string, string]> = [
    ['Reporter', detail.reporterLabel],
  ]
  if (detail.assigneeLabel !== undefined) {
    entries.push(['Assignee', detail.assigneeLabel?.trim() ? detail.assigneeLabel : 'Unassigned'])
  }
  const timestamps: Array<[string, string | null | undefined]> = [
    ['Created', detail.createdAt],
    ['Submitted', detail.submittedAt],
    ['Withdrawn', detail.withdrawnAt],
    ['Queue entered', detail.queueEnteredAt],
    ['First assigned', detail.firstAssignedAt],
    ['Latest assignment', detail.latestAssignedAt],
  ]
  return <dl className="incident-detail__metadata">
    {entries.map(([label, value]) => <div key={label}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>)}
    {timestamps.map(([label, value]) => value
      ? <div key={label}>
        <dt>{label}</dt>
        <dd><time dateTime={value}>{formatInstant(value)}</time></dd>
      </div>
      : null)}
  </dl>
}
