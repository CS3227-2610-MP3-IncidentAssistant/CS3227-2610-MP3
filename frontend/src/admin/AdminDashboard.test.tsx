import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminDashboard } from './AdminDashboard'

const dashboard = {
  incidents: [],
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 0,
  sloTargets: [
    {
      category: 'IT',
      timeToClaimMinutes: 10, timeInProgressMinutes: 125, reopenRatePercent: 5,
      effectiveAt: '2026-10-21T12:00:00Z',
    },
    {
      category: 'HUMAN_RELATIONS',
      timeToClaimMinutes: null, timeInProgressMinutes: null, reopenRatePercent: null,
      effectiveAt: null,
    },
  ],
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('AdminDashboard SLO overview', () => {
  it('displays the three objectives separately for a configured category', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(dashboard)))

    render(<AdminDashboard />)

    const category = await waitFor(() => screen.getByRole('region', { name: /IT$/ }))
    expect(within(category).getByText('Average time to claim').parentElement)
      .toHaveTextContent('0h 10m')
    expect(within(category).getByText('Average time in progress').parentElement)
      .toHaveTextContent('2h 5m')
    expect(within(category).getByText('Average reopen rate').parentElement)
      .toHaveTextContent('5%')
  })

  it('capitalizes the IT category label rather than lowercasing it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(dashboard)))

    render(<AdminDashboard />)

    expect(await waitFor(() => screen.getByRole('heading', { name: /— IT$/, level: 3 }))).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /It$/, level: 3 })).not.toBeInTheDocument()
  })

  it('formats durations as hours and minutes like the desktop application', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      ...dashboard,
      sloTargets: [{
        category: 'FACILITIES',
        timeToClaimMinutes: 0, timeInProgressMinutes: 1440, reopenRatePercent: 12.5,
        effectiveAt: '2026-10-21T12:00:00Z',
      }],
    })))

    render(<AdminDashboard />)

    const category = await waitFor(() => screen.getByRole('region', { name: /Facilities$/ }))
    expect(within(category).getByText('Average time to claim').parentElement)
      .toHaveTextContent('0h 0m')
    expect(within(category).getByText('Average time in progress').parentElement)
      .toHaveTextContent('24h 0m')
    expect(within(category).getByText('Average reopen rate').parentElement)
      .toHaveTextContent('12.5%')
  })

  it('marks every objective of an unconfigured category as not configured', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(dashboard)))

    render(<AdminDashboard />)

    const category = await waitFor(() => screen.getByRole('region', { name: /Human Relations$/ }))
    expect(within(category).getByText('No target version yet')).toBeInTheDocument()
    expect(within(category).getAllByText('—')).toHaveLength(3)
    expect(within(category).getAllByText('Not configured')).toHaveLength(3)
  })
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
