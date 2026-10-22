import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { IncidentDetailView } from './IncidentDetailView'
import { formatMinutes, incidentSloStateLabel } from './incidentDetail'
import type { IncidentDetailModel } from './incidentDetail'

const baseDetail: IncidentDetailModel = {
  id: '30000000-0000-0000-0000-000000000001',
  title: 'Shared detail',
  description: 'Description text',
  category: 'IT',
  status: 'SUBMITTED',
  reporterLabel: 'Anonymous reporter',
}

afterEach(cleanup)

describe('IncidentDetailView', () => {
  it('renders only the sections the role actually supplies', () => {
    render(<IncidentDetailView detail={baseDetail} prefix="test" capabilities={{}} />)

    expect(screen.getByRole('heading', { name: 'Shared detail' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Description' })).toBeInTheDocument()
    // No SLO, attachment, or lifecycle data was supplied, so no such sections.
    expect(screen.queryByRole('heading', { name: 'Service-level objective' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Attachments' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Incident history' })).not.toBeInTheDocument()
    // An assignee row is omitted entirely when the role cannot read one.
    expect(screen.queryByText('Assignee')).not.toBeInTheDocument()
  })

  it('hides a capability whose control the role did not provide', () => {
    render(<IncidentDetailView
      detail={{ ...baseDetail, attachments: [{ id: 'a1', mediaType: 'image/png', sizeBytes: 1 }] }}
      prefix="test"
      capabilities={{}}
    />)

    // Attachment data present but no authorized URL builder: no download links.
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Attachments' })).not.toBeInTheDocument()
  })

  it('renders role-supplied action, composer and resolution controls', () => {
    render(<IncidentDetailView
      detail={baseDetail}
      prefix="test"
      capabilities={{
        primaryActions: <button type="button">Claim incident</button>,
        commentComposer: <button type="button">Add comment</button>,
        resolutionForm: <button type="button">Resolve incident</button>,
        attachmentUrl: (id) => `/role/attachments/${id}`,
      }}
    />)

    expect(screen.getByRole('button', { name: 'Claim incident' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add comment' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Resolve incident' })).toBeInTheDocument()
  })

  it('builds attachment links from the role-specific endpoint', () => {
    render(<IncidentDetailView
      detail={{
        ...baseDetail,
        attachments: [{ id: 'att-1', displayName: null, mediaType: 'image/jpeg', sizeBytes: 10 }],
      }}
      prefix="test"
      capabilities={{ attachmentUrl: (id) => `/api/v1/role/attachments/${id}` }}
    />)

    // A missing display name falls back to a generic, non-identifying label.
    expect(screen.getByRole('link', { name: /Attachment 1 \(JPEG\)/ }))
      .toHaveAttribute('href', '/api/v1/role/attachments/att-1')
  })

  it('states SLO progress in words as well as colour', () => {
    render(<IncidentDetailView
      detail={{
        ...baseDetail,
        slo: {
          timeToClaimTargetMinutes: 30,
          timeInProgressTargetMinutes: null,
          timeToClaimElapsedMillis: 3_600_000,
          timeInProgressElapsedMillis: null,
          claimState: 'OVERDUE',
          progressState: 'NOT_APPLICABLE',
        },
      }}
      prefix="test"
      capabilities={{}}
    />)

    expect(screen.getByText('Overdue')).toBeInTheDocument()
    expect(screen.getByText('Not applicable')).toBeInTheDocument()
    expect(screen.getByText(/1 h of 30 min target/)).toBeInTheDocument()
    expect(screen.getByText('Not started')).toBeInTheDocument()
  })

  it('explains an unconfigured service-level objective', () => {
    render(<IncidentDetailView
      detail={{
        ...baseDetail,
        slo: {
          timeToClaimTargetMinutes: null,
          timeInProgressTargetMinutes: null,
          timeToClaimElapsedMillis: null,
          timeInProgressElapsedMillis: null,
          claimState: 'NOT_CONFIGURED',
          progressState: 'NOT_CONFIGURED',
        },
      }}
      prefix="test"
      capabilities={{}}
    />)

    expect(screen.getByText(/No service-level objective is configured/)).toBeInTheDocument()
  })

  it('keeps untrusted comment and resolution text as text', () => {
    render(<IncidentDetailView
      detail={{
        ...baseDetail,
        comments: [{
          authorLabel: '<img src=x onerror=alert(1)>',
          authorRole: 'REPORTER',
          text: '<script>alert(1)</script>',
          createdAt: '2026-10-20T08:00:00Z',
        }],
        resolutionHistory: [{
          cycleNumber: 1,
          resolvedAt: '2026-10-20T09:00:00Z',
          remarks: '<b>not bold</b>',
        }],
      }}
      prefix="test"
      capabilities={{}}
    />)

    expect(screen.getByText('<script>alert(1)</script>')).toBeInTheDocument()
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(screen.getByText('<b>not bold</b>')).toBeInTheDocument()
    expect(document.querySelector('script')).toBeNull()
    expect(document.querySelector('img')).toBeNull()
  })

  it('never renders an audit timeline section', () => {
    render(<IncidentDetailView
      detail={baseDetail}
      prefix="test"
      capabilities={{ showLifecycleHistory: true }}
    />)

    expect(screen.queryByText(/audit/i)).not.toBeInTheDocument()
  })
})

describe('incident detail formatting', () => {
  it('formats durations in readable units', () => {
    expect(formatMinutes(45)).toBe('45 min')
    expect(formatMinutes(60)).toBe('1 h')
    expect(formatMinutes(90)).toBe('1 h 30 min')
    expect(formatMinutes(1_440)).toBe('1 d')
    expect(formatMinutes(1_500)).toBe('1 d 1 h')
  })

  it('gives every SLO state a stable text label', () => {
    expect(incidentSloStateLabel('WITHIN_TARGET')).toBe('Within target')
    expect(incidentSloStateLabel('OVERDUE')).toBe('Overdue')
    expect(incidentSloStateLabel('NOT_APPLICABLE')).toBe('Not applicable')
    expect(incidentSloStateLabel('NOT_CONFIGURED')).toBe('No target configured')
  })
})
