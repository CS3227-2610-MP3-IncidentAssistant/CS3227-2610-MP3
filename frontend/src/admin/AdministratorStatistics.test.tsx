import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdministratorStatistics } from './AdministratorStatistics'

const refreshSession = async () => ({ authenticated: true, role: 'ADMINISTRATOR' })

const statistics = {
  outcome: 'AVAILABLE',
  summary: {
    resolvedIncidentCount: 3, averageTimeToClaimMillis: 5_400_000, claimedCycleCount: 4,
    averageTimeInProgressMillis: 90_000_000, completedCycleCount: 5,
    reopenedIncidentCount: 1, reopenRate: 1 / 3, reopenEventCount: 2, administratorResolvedCount: 1,
  },
  responders: [{
    responderId: '10000000-0000-0000-0000-000000000001', displayName: '<img src=x onerror=alert(1)>',
    resolvedCycleCount: 2, averageTimeInProgressMillis: 7_200_000,
    reopenedCycleCount: 1, reopenRate: 0.5, administratorResolvedCount: 1,
  }],
  filterOptions: {
    responders: [
      { id: '10000000-0000-0000-0000-000000000001', displayName: 'response-one' },
      { id: '10000000-0000-0000-0000-000000000002', displayName: 'response-two' },
    ],
    reporters: [{ id: '20000000-0000-0000-0000-000000000002', displayName: 'visible-reporter' }],
  },
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('AdministratorStatistics', () => {
  it('renders company metrics and an accessible privacy-safe responder breakdown', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(statistics)))
    render(<AdministratorStatistics onRefreshSession={refreshSession} />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading operational statistics')
    expect(await screen.findByRole('heading', { name: 'Incident outcomes' })).toBeInTheDocument()
    expect(screen.getByText('1h 30m')).toBeInTheDocument()
    expect(screen.getByText('1d 1h')).toBeInTheDocument()
    expect(screen.getByText(/33.3%/)).toBeInTheDocument()
    const table = screen.getByRole('region', { name: 'Responder operational statistics' })
    expect(within(table).getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(table.querySelector('img')).toBeNull()
    expect(screen.getByText(/not attributed to individual responders/)).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'visible-reporter' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Reporter' }).querySelectorAll('option')).toHaveLength(2)
  })

  it('shows explicit no-data values and an empty responder state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      ...statistics,
      summary: {
        ...statistics.summary, averageTimeToClaimMillis: null, averageTimeInProgressMillis: null,
        reopenedIncidentCount: 0, reopenRate: null,
      },
      responders: [],
    })))
    render(<AdministratorStatistics onRefreshSession={refreshSession} />)

    expect(await screen.findAllByText('No data')).toHaveLength(3)
    expect(screen.getByRole('heading', { name: 'No responder data' })).toBeInTheDocument()
  })

  it('sends category, identity, and inclusive UTC period filters', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(statistics))
    vi.stubGlobal('fetch', fetchMock)
    render(<AdministratorStatistics onRefreshSession={refreshSession} />)
    await screen.findByRole('heading', { name: 'Incident outcomes' })

    fireEvent.click(screen.getByRole('checkbox', { name: 'IT' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Facilities' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'Responder' }), {
      target: { value: statistics.filterOptions.responders[0].id },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Reporter' }), {
      target: { value: statistics.filterOptions.reporters[0].id },
    })
    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-10-01' } })
    fireEvent.change(screen.getByLabelText('Through date'), { target: { value: '2026-10-22' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const url = new URL(String(fetchMock.mock.calls[1][0]), 'https://incident.test')
    expect(url.searchParams.getAll('category')).toEqual(['IT', 'FACILITIES'])
    expect(url.searchParams.get('responderId')).toBe(statistics.filterOptions.responders[0].id)
    expect(url.searchParams.get('reporterId')).toBe(statistics.filterOptions.reporters[0].id)
    expect(url.searchParams.get('periodFrom')).toBe('2026-10-01T00:00:00.000Z')
    expect(url.searchParams.get('periodThrough')).toBe('2026-10-22T23:59:59.999Z')
  })

  it('rejects an inverted period without issuing another statistics request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(statistics))
    vi.stubGlobal('fetch', fetchMock)
    render(<AdministratorStatistics onRefreshSession={refreshSession} />)
    await screen.findByRole('heading', { name: 'Incident outcomes' })

    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-10-22' } })
    fireEvent.change(screen.getByLabelText('Through date'), { target: { value: '2026-10-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))

    expect(screen.getByRole('alert')).toHaveTextContent('start date must be on or before the end date')
    expect(screen.getByLabelText('From date')).toHaveAttribute('aria-invalid', 'true')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('retains previous results during refresh and retries a failed request', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(statistics))
      .mockResolvedValueOnce(jsonResponse({ code: 'PERSISTENCE_FAILURE' }, 503))
      .mockResolvedValueOnce(jsonResponse(statistics))
    vi.stubGlobal('fetch', fetchMock)
    render(<AdministratorStatistics onRefreshSession={refreshSession} />)
    await screen.findByRole('heading', { name: 'Incident outcomes' })

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable')
    expect(screen.queryByRole('heading', { name: 'Incident outcomes' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Incident outcomes' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('submits filters separately and renders deterministic insight rows as escaped text', async () => {
    const insight = {
      outcome: 'ANSWER', clarificationCode: null, summary: 'Responder operational indicator.',
      metric: 'AVERAGE_TIME_IN_PROGRESS', periodStart: '2026-10-01T00:00:00.000Z',
      periodEndExclusive: '2026-10-22T18:27:45.266Z', snapshotAt: '2026-10-23T00:00:00Z',
      rows: [
        { label: '<script>alert(1)</script>', numericValue: 7200000, displayValue: '120 minutes', sampleSize: 1 },
        { label: 'response-one', numericValue: 7200000, displayValue: '120 minutes', sampleSize: 1 },
        { label: 'response-two', numericValue: null, displayValue: 'Not available', sampleSize: 0 },
      ],
      advisoryNotice: 'AI interpreted the question; application code calculated every value.', grouping: 'RESPONDER',
      rankingDirection: 'DESCENDING', limit: 3, categoryFilters: ['IT'],
      responderFilters: ['response-one', 'response-two'],
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init
      return String(input).includes('/insights') ? jsonResponse(insight) : jsonResponse(statistics)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<AdministratorStatistics onRefreshSession={refreshSession} />)
    await screen.findByRole('heading', { name: 'Incident outcomes' })

    fireEvent.click(screen.getByRole('checkbox', { name: 'IT' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'Responder' }), {
      target: { value: statistics.filterOptions.responders[0].id },
    })
    fireEvent.click(screen.getByRole('checkbox', { name: 'response-one' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'response-two' }))
    fireEvent.change(screen.getByLabelText('Question'), {
      target: { value: 'Who had the longest average time in progress during the past 30 days?' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Get insight' }))

    const region = await screen.findByRole('region', { name: 'AI insight result' })
    expect(within(region).getByText('<script>alert(1)</script>')).toBeInTheDocument()
    expect(region.querySelector('script')).toBeNull()
    expect(within(region).getAllByText('120 minutes')).toHaveLength(2)
    expect(within(region).getByText('Not available')).toBeInTheDocument()
    expect(within(region).getByRole('cell', { name: '0' })).toBeInTheDocument()
    expect(within(region).getAllByRole('cell', { name: '1' })).toHaveLength(2)
    expect(within(region).getByText('Metric')).toBeInTheDocument()
    expect(within(region).getByText('Average time in progress')).toBeInTheDocument()
    expect(within(region).getByText('Descending')).toBeInTheDocument()
    expect(within(region).getByText('3')).toBeInTheDocument()
    expect(within(region).getByText('Responder')).toBeInTheDocument()
    expect(within(region).getByText('1 Oct 2026, 8 am to 23 Oct 2026, 2 am (Singapore time; end exclusive)')).toBeInTheDocument()
    expect(within(region).getByText('Categories: IT; Responders: response-one, response-two')).toBeInTheDocument()
    expect(screen.getByText(/Every responder result is shown regardless of sample size/)).toBeInTheDocument()
    expect(screen.getByText(/first-place ties shown together/)).toBeInTheDocument()
    expect(screen.queryByText(/responder samples were excluded/)).not.toBeInTheDocument()
    const [, init] = fetchMock.mock.calls.find(([url]) => String(url).includes('/insights'))!
    expect(JSON.parse(String(init?.body))).toEqual({
      question: 'Who had the longest average time in progress during the past 30 days?',
      categories: ['IT'], responderIds: statistics.filterOptions.responders.map((responder) => responder.id),
    })
  })

  it('refreshes a stale session once before retrying the insight request', async () => {
    const answer = {
      outcome: 'NEEDS_CLARIFICATION', clarificationCode: 'MISSING_PERIOD',
      summary: 'Specify a reporting period.', metric: null, periodStart: null,
      periodEndExclusive: null, snapshotAt: '2026-10-23T00:00:00Z', rows: [],
      advisoryNotice: 'No analytics query was run.', grouping: null,
      rankingDirection: null, limit: null, categoryFilters: [], responderFilters: [],
    }
    let insightCalls = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (!String(input).includes('/insights')) return jsonResponse(statistics)
      insightCalls++
      return insightCalls === 1
        ? jsonResponse({ code: 'SECURITY_SESSION_EXPIRED' }, 403)
        : jsonResponse(answer)
    })
    const refresh = vi.fn(async () => ({ authenticated: true, role: 'ADMINISTRATOR' }))
    vi.stubGlobal('fetch', fetchMock)
    render(<AdministratorStatistics onRefreshSession={refresh} />)
    await screen.findByRole('heading', { name: 'Incident outcomes' })

    fireEvent.change(screen.getByLabelText('Question'), { target: { value: 'Which category is slowest?' } })
    fireEvent.click(screen.getByRole('button', { name: 'Get insight' }))

    expect(await screen.findByRole('heading', { name: 'Specify a reporting period.' })).toBeInTheDocument()
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(insightCalls).toBe(2)
  })
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
