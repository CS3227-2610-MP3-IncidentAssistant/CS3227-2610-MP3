import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdministratorIncidentDetail } from './AdministratorIncidentDetail'

const incidentId = '30000000-0000-0000-0000-000000000001'
const responderId = '40000000-0000-0000-0000-000000000002'
const attachmentId = '50000000-0000-0000-0000-000000000003'

const assignedDetail = {
  id: incidentId,
  version: 7,
  title: 'Door badge reader offline',
  description: 'Line one\n\n  indented line two',
  category: 'FACILITIES',
  status: 'ASSIGNED',
  anonymous: false,
  reporterLabel: 'reporter-one',
  assigneeLabel: 'responder-one',
  createdAt: '2026-10-20T08:00:00Z',
  submittedAt: '2026-10-20T08:05:00Z',
  withdrawnAt: null,
  queueEnteredAt: '2026-10-20T08:05:00Z',
  firstAssignedAt: '2026-10-20T09:00:00Z',
  latestAssignedAt: '2026-10-20T09:00:00Z',
  slo: {
    timeToClaimTargetMinutes: 30,
    timeInProgressTargetMinutes: 240,
    timeToClaimElapsedMillis: 3_300_000,
    timeInProgressElapsedMillis: 1_800_000,
    claimState: 'OVERDUE',
    progressState: 'WITHIN_TARGET',
  },
  resolutionHistory: [{
    cycleNumber: 1,
    resolvedAt: '2026-10-19T10:00:00Z',
    resolvedByLabel: 'responder-two',
    remarks: 'Earlier cycle remarks.',
  }],
  comments: [{
    id: '60000000-0000-0000-0000-000000000004',
    authorLabel: 'Anonymous reporter',
    authorRole: 'REPORTER',
    text: '<script>comment stays text</script>',
    createdAt: '2026-10-20T08:10:00Z',
  }],
  attachments: [{
    id: attachmentId,
    displayName: 'Attachment 1',
    mediaType: 'image/png',
    sizeBytes: 2048,
    createdAt: '2026-10-20T08:06:00Z',
  }],
  eligibleResponders: [{ id: responderId, loginName: 'responder-one' }],
}

const submittedDetail = {
  ...assignedDetail,
  status: 'SUBMITTED',
  assigneeLabel: null,
  firstAssignedAt: null,
  latestAssignedAt: null,
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('AdministratorIncidentDetail', () => {
  it('renders lifecycle, SLO, resolution, comment and attachment data as escaped text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(assignedDetail)))
    renderDetail()

    expect(await screen.findByRole('heading', { name: assignedDetail.title })).toBeInTheDocument()
    expect(screen.getByText('reporter-one')).toBeInTheDocument()
    // The assignee appears in the metadata as well as in the reassign options.
    expect(screen.getAllByText('responder-one').length).toBeGreaterThan(0)

    // Untrusted comment text is rendered as text, never parsed as markup.
    expect(screen.getByText('<script>comment stays text</script>')).toBeInTheDocument()
    expect(document.querySelector('script')).not.toBeInTheDocument()

    // SLO state is conveyed by words, not colour alone.
    expect(screen.getByText('Overdue')).toBeInTheDocument()
    expect(screen.getByText('Within target')).toBeInTheDocument()

    expect(screen.getByRole('heading', { name: 'Resolution history' })).toBeInTheDocument()
    expect(screen.getByText('Earlier cycle remarks.')).toBeInTheDocument()

    expect(screen.getByRole('link', { name: /Attachment 1 \(PNG\)/ })).toHaveAttribute(
      'href', `/api/v1/admin/incidents/${incidentId}/attachments/${attachmentId}`,
    )
    // Administrators read attachments but never add them.
    expect(screen.queryByLabelText(/add attachment/i)).not.toBeInTheDocument()
  })

  it('preserves deliberate description whitespace', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(assignedDetail)))
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    // The exact raw text, including the blank line and indentation, is in the DOM,
    // and CSS (white-space: pre-wrap) is what makes it visible.
    const description = document.querySelector('.incident-detail__description p')
    expect(description?.textContent).toBe(assignedDetail.description)
  })

  it('sends the loaded version and a generated idempotency key when assigning', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(submittedDetail))
      .mockResolvedValueOnce(jsonResponse({ changed: true, version: 8 }))
      .mockResolvedValueOnce(jsonResponse({ ...assignedDetail, version: 8 }))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail()

    await screen.findByRole('heading', { name: submittedDetail.title })
    // An unassigned incident offers Assign, not Reassign.
    expect(screen.getByRole('button', { name: 'Assign incident' })).toBeInTheDocument()

    fireEvent.change(screen.getByRole('combobox', { name: 'Assign to responder' }), {
      target: { value: responderId },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Assign incident' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    const [url, init] = fetchMock.mock.calls[1]
    expect(url).toBe(`/api/v1/admin/incidents/${incidentId}/assignment`)
    expect(JSON.parse(String(init.body))).toEqual({ expectedVersion: 7, responderId })
  })

  it('labels the action Reassign for an already assigned incident', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(assignedDetail)))
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    expect(screen.getByRole('button', { name: 'Reassign incident' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign incident' })).not.toBeInTheDocument()
  })

  it('explains that category access must be configured when no responder is eligible', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ ...submittedDetail, eligibleResponders: [] }),
    ))
    renderDetail()

    await screen.findByRole('heading', { name: submittedDetail.title })
    expect(screen.getByText(/Configure\s+responder category access/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign incident' })).not.toBeInTheDocument()
  })

  it('states the consequence before unassigning and only mutates after confirmation', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(assignedDetail))
      .mockResolvedValueOnce(jsonResponse({ changed: true, version: 8 }))
      .mockResolvedValueOnce(jsonResponse(submittedDetail))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    fireEvent.click(screen.getByRole('button', { name: 'Unassign incident' }))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/keeps its original position in its\s+category queue/))
      .toBeInTheDocument()

    // Cancelling changes nothing.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(fetchMock).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Unassign incident' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Unassign incident' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(fetchMock.mock.calls[1][0]).toBe(`/api/v1/admin/incidents/${incidentId}/unassign`)
    expect(JSON.parse(String(fetchMock.mock.calls[1][1].body))).toEqual({ expectedVersion: 7 })
  })

  it('rejects blank resolution remarks in the client without calling the server', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(assignedDetail))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    fireEvent.change(screen.getByRole('textbox', { name: 'Resolution remarks' }), {
      target: { value: '   ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Resolve incident' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter resolution remarks')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not offer resolution for an unassigned incident', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(submittedDetail)))
    renderDetail()

    await screen.findByRole('heading', { name: submittedDetail.title })
    expect(screen.queryByRole('button', { name: 'Resolve incident' })).not.toBeInTheDocument()
  })

  it('sends remarks with an idempotency key when resolving', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(assignedDetail))
      .mockResolvedValueOnce(jsonResponse({ resolved: true, version: 8, cycleNumber: 2 }))
      .mockResolvedValueOnce(jsonResponse({ ...assignedDetail, status: 'RESOLVED', version: 8 }))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    fireEvent.change(screen.getByRole('textbox', { name: 'Resolution remarks' }), {
      target: { value: '  Replaced the reader.  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Resolve incident' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    const body = JSON.parse(String(fetchMock.mock.calls[1][1].body))
    expect(body.expectedVersion).toBe(7)
    // Entered whitespace is retained after the non-blank check.
    expect(body.remarks).toBe('  Replaced the reader.  ')
    expect(body.requestId).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('sends a comment with an idempotency key and keeps the text on failure', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(assignedDetail))
      .mockResolvedValueOnce(jsonResponse({ code: 'INCIDENT_UNAVAILABLE', message: 'x' }, 503))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    fireEvent.change(screen.getByRole('textbox', { name: 'Add a comment' }), {
      target: { value: 'Following up with facilities.' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add comment' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    const body = JSON.parse(String(fetchMock.mock.calls[1][1].body))
    expect(body.text).toBe('Following up with facilities.')
    expect(body.requestId).toMatch(/^[0-9a-f-]{36}$/)
    // A recoverable failure preserves the unsent text for another attempt.
    expect(screen.getByRole('textbox', { name: 'Add a comment' }))
      .toHaveValue('Following up with facilities.')
  })

  it('clears the comment field once the comment is accepted', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(assignedDetail))
      .mockResolvedValueOnce(jsonResponse({}, 200))
      .mockResolvedValueOnce(jsonResponse(assignedDetail))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    fireEvent.change(screen.getByRole('textbox', { name: 'Add a comment' }), {
      target: { value: 'Following up with facilities.' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add comment' }))

    // The reload after a successful mutation remounts the composer empty, so a
    // reload cannot resubmit the same text.
    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: 'Add a comment' })).toHaveValue('')
    })
  })

  it('reports a stale mutation as a conflict and reloads the latest detail', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(assignedDetail))
      .mockResolvedValueOnce(jsonResponse({ code: 'UNASSIGNMENT_CONFLICT', message: 'changed' }, 409))
      .mockResolvedValueOnce(jsonResponse({ ...submittedDetail, version: 9 }))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    fireEvent.click(screen.getByRole('button', { name: 'Unassign incident' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Unassign incident' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('changed since it was loaded')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
  })

  it('reports a denied or missing incident without revealing which', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ code: 'UNAVAILABLE', message: 'Resource unavailable' }, 404),
    ))
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Incident unavailable' })).toBeInTheDocument()
    expect(screen.queryByText(assignedDetail.title)).not.toBeInTheDocument()
  })

  it('offers a retry when incident details are temporarily unavailable', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ code: 'INCIDENT_UNAVAILABLE', message: 'x' }, 503))
      .mockResolvedValueOnce(jsonResponse(assignedDetail))
    vi.stubGlobal('fetch', fetchMock)
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Details unavailable' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: assignedDetail.title })).toBeInTheDocument()
  })

  it('does not render an audit timeline section', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(assignedDetail)))
    renderDetail()

    await screen.findByRole('heading', { name: assignedDetail.title })
    expect(screen.queryByText(/audit/i)).not.toBeInTheDocument()
  })
})

function renderDetail() {
  return render(<AdministratorIncidentDetail
    incidentId={incidentId}
    onBack={() => {}}
    refreshSession={() => Promise.resolve({ authenticated: true, role: 'ADMINISTRATOR' })}
  />)
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
