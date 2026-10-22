import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdministratorStatistics } from './AdministratorStatistics'

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
    responders: [{ id: '10000000-0000-0000-0000-000000000001', displayName: 'response-one' }],
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
    render(<AdministratorStatistics />)

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
    render(<AdministratorStatistics />)

    expect(await screen.findAllByText('No data')).toHaveLength(3)
    expect(screen.getByRole('heading', { name: 'No responder data' })).toBeInTheDocument()
  })

  it('sends category, identity, and inclusive UTC period filters', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(statistics))
    vi.stubGlobal('fetch', fetchMock)
    render(<AdministratorStatistics />)
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
    render(<AdministratorStatistics />)
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
    render(<AdministratorStatistics />)
    await screen.findByRole('heading', { name: 'Incident outcomes' })

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable')
    expect(screen.queryByRole('heading', { name: 'Incident outcomes' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Incident outcomes' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
