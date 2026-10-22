import type { IncidentCategory } from '../../api'

/**
 * The role-neutral shape of the incident detail sections.
 *
 * Each role's API returns a superset or subset of these fields, so every member
 * beyond the always-present core is optional. A role that cannot read a section
 * simply omits it and the corresponding section does not render; presence of
 * data here is never authorization on its own, which the Java backend enforces.
 */
export type IncidentDetailModel = {
  id: string
  title: string
  description: string
  category: IncidentCategory
  status: string
  reporterLabel: string
  assigneeLabel?: string | null
  version?: number
  createdAt?: string | null
  submittedAt?: string | null
  withdrawnAt?: string | null
  queueEnteredAt?: string | null
  firstAssignedAt?: string | null
  latestAssignedAt?: string | null
  slo?: IncidentSloProgress | null
  resolutionHistory?: IncidentResolutionEntry[]
  comments?: IncidentCommentEntry[]
  attachments?: IncidentAttachmentEntry[]
  lifecycleHistory?: IncidentLifecycleEntry[]
}

export type IncidentSloProgress = {
  timeToClaimTargetMinutes: number | null
  timeInProgressTargetMinutes: number | null
  timeToClaimElapsedMillis: number | null
  timeInProgressElapsedMillis: number | null
  claimState: IncidentSloState
  progressState: IncidentSloState
}

export type IncidentSloState = 'WITHIN_TARGET' | 'OVERDUE' | 'NOT_APPLICABLE' | 'NOT_CONFIGURED'

export type IncidentResolutionEntry = {
  cycleNumber: number
  resolvedAt: string
  resolvedByLabel?: string | null
  remarks: string
}

export type IncidentCommentEntry = {
  id?: string
  authorLabel: string
  authorRole: 'REPORTER' | 'RESPONDER' | 'ADMINISTRATOR'
  text: string
  createdAt: string
}

export type IncidentAttachmentEntry = {
  id: string
  displayName?: string | null
  mediaType: string
  sizeBytes: number
  createdAt?: string | null
}

export type IncidentLifecycleEntry = {
  action: 'INCIDENT_SUBMITTED' | 'INCIDENT_CLAIMED' | 'INCIDENT_RESOLVED'
    | 'INCIDENT_REOPENED' | 'INCIDENT_HANDED_OFF' | 'INCIDENT_WITHDRAWN'
  occurredAt: string
  fromStatus: string | null
  toStatus: string | null
}

export const incidentCategoryNames: Record<IncidentCategory, string> = {
  IT: 'IT',
  HUMAN_RELATIONS: 'Human relations',
  FACILITIES: 'Facilities',
}

export function incidentCategoryName(category: IncidentCategory): string {
  return incidentCategoryNames[category] ?? displayEnum(category)
}

export function incidentStatusLabel(status: string): string {
  return displayEnum(status)
}

export function incidentRoleName(role: IncidentCommentEntry['authorRole']): string {
  switch (role) {
    case 'REPORTER': return 'Reporter'
    case 'RESPONDER': return 'Responder'
    case 'ADMINISTRATOR': return 'Administrator'
  }
}

export function incidentLifecycleLabel(action: IncidentLifecycleEntry['action']): string {
  switch (action) {
    case 'INCIDENT_SUBMITTED': return 'Submitted'
    case 'INCIDENT_CLAIMED': return 'Claimed'
    case 'INCIDENT_RESOLVED': return 'Resolved'
    case 'INCIDENT_REOPENED': return 'Reopened'
    case 'INCIDENT_HANDED_OFF': return 'Handed off'
    case 'INCIDENT_WITHDRAWN': return 'Withdrawn'
  }
}

/** Stable, non-colour-dependent text for an SLO state. */
export function incidentSloStateLabel(state: IncidentSloState): string {
  switch (state) {
    case 'WITHIN_TARGET': return 'Within target'
    case 'OVERDUE': return 'Overdue'
    case 'NOT_APPLICABLE': return 'Not applicable'
    case 'NOT_CONFIGURED': return 'No target configured'
  }
}

export function attachmentFormatLabel(mediaType: string): string {
  if (mediaType === 'image/png') return 'PNG'
  if (mediaType === 'image/jpeg') return 'JPEG'
  return mediaType
}

/**
 * Renders a UTC instant in the viewer's local zone while keeping the original
 * server value available to assistive technology through `dateTime`.
 */
export function formatInstant(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString()
}

/** Human-readable elapsed/target duration built from whole minutes. */
export function formatMinutes(minutes: number): string {
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  if (hours < 24) return remainder === 0 ? `${hours} h` : `${hours} h ${remainder} min`
  const days = Math.floor(hours / 24)
  const leftoverHours = hours % 24
  return leftoverHours === 0 ? `${days} d` : `${days} d ${leftoverHours} h`
}

export function formatMillisDuration(millis: number): string {
  return formatMinutes(Math.max(0, Math.round(millis / 60_000)))
}

function displayEnum(value: string): string {
  const spaced = value.replace(/_/g, ' ').toLowerCase()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}
