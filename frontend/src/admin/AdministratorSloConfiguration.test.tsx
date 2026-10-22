import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdministratorSloConfiguration } from './AdministratorSloConfiguration'

const configuration = {
  outcome: 'AVAILABLE',
  categories: [
    {
      category: 'IT',
      current: {
        id: '10000000-0000-0000-0000-000000000001', category: 'IT',
        timeToClaimMinutes: 10, timeInProgressMinutes: 20, reopenRatePercent: 5,
        effectiveAt: '2026-10-21T12:00:00Z',
      },
      history: [
        {
          id: '10000000-0000-0000-0000-000000000001', category: 'IT',
          timeToClaimMinutes: 10, timeInProgressMinutes: 20, reopenRatePercent: 5,
          effectiveAt: '2026-10-21T12:00:00Z',
        },
      ],
    },
    { category: 'HUMAN_RELATIONS', current: null, history: [] },
    { category: 'FACILITIES', current: null, history: [] },
  ],
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('AdministratorSloConfiguration', () => {
  it('shows loading while the configuration request is pending', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading SLO targets')
  })

  it('shows an empty state when no incident categories are available', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ outcome: 'AVAILABLE', categories: [] })))

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)

    expect(await screen.findByText('No incident categories are available.')).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Category' })).not.toBeInTheDocument()
  })

  it('retries a failed configuration load', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValueOnce(jsonResponse(configuration))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable')
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))

    expect(await screen.findByRole('combobox', { name: 'Category' })).toHaveValue('IT')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('uses one category selector and separates the current effective target from the editor', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(configuration)))

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)

    const category = await screen.findByRole('combobox', { name: 'Category' })
    expect(category).toHaveValue('IT')
    expect(screen.getByRole('heading', { name: 'IT' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New IT target version' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'IT SLO version history' })).toHaveTextContent('10 min')
    expect(screen.queryByRole('heading', { name: 'New Human relations target version' })).not.toBeInTheDocument()

    fireEvent.change(category, { target: { value: 'HUMAN_RELATIONS' } })

    expect(screen.getByRole('heading', { name: 'Human relations' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New Human relations target version' })).toBeInTheDocument()
    expect(screen.getByText('No target versions yet.')).toBeInTheDocument()

    fireEvent.change(category, { target: { value: 'FACILITIES' } })
    expect(screen.getByRole('heading', { name: 'Facilities' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New Facilities target version' })).toBeInTheDocument()
  })

  it('disables an unchanged save and sends the expected version after an edit', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(configuration))
      .mockResolvedValueOnce(jsonResponse({ changed: true }))
      .mockResolvedValueOnce(jsonResponse(configuration))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)
    const save = await screen.findByRole('button', { name: 'Save new version' })
    expect(save).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Average time to claim (minutes)'), { target: { value: '11' } })
    fireEvent.click(save)

    expect(await screen.findByText('IT targets were updated.')).toBeInTheDocument()
    const request = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(request[0]).toBe('/api/v1/admin/slo/IT')
    expect(JSON.parse(String(request[1].body))).toMatchObject({
      expectedVersionId: '10000000-0000-0000-0000-000000000001',
      timeToClaimMinutes: 11,
      timeInProgressMinutes: 20,
      reopenRatePercent: 5,
    })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
  })

  it('keeps entered values and associates server validation with its field', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(configuration))
      .mockResolvedValueOnce(jsonResponse({
        code: 'VALIDATION', message: 'Review the highlighted fields',
        fieldErrors: { timeToClaimMinutes: 'Enter a non-negative whole number of minutes' },
      }, 400))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)
    const claim = await screen.findByLabelText('Average time to claim (minutes)', { selector: '#IT-claim' })
    fireEvent.change(claim, { target: { value: '15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save new version' }))

    expect(await screen.findByText('Enter a non-negative whole number of minutes')).toBeInTheDocument()
    expect(claim).toHaveValue(15)
    expect(claim).toHaveAttribute('aria-invalid', 'true')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })

  it('keeps entered values and reports a stale-version conflict', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(configuration))
      .mockResolvedValueOnce(jsonResponse({ code: 'CONFLICT', message: 'Target changed' }, 409))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)
    const claim = await screen.findByLabelText('Average time to claim (minutes)')
    fireEvent.change(claim, { target: { value: '15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save new version' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'These targets changed in another session. Refresh before trying again.',
    )
    expect(claim).toHaveValue(15)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('prevents duplicate submissions while a save is pending', async () => {
    let resolveSave: (response: Response) => void = () => {}
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(configuration))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => {
        resolveSave = resolve
      }))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)
    fireEvent.change(await screen.findByLabelText('Average time to claim (minutes)'), { target: { value: '15' } })
    const save = screen.getByRole('button', { name: 'Save new version' })
    fireEvent.click(save)
    expect(await screen.findByRole('button', { name: 'Saving…' })).toBeDisabled()
    fireEvent.click(save)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    resolveSave(jsonResponse({ changed: false }))
    expect(await screen.findByText('No values changed.')).toBeInTheDocument()
  })

  it('paginates the selected category version history newest first', async () => {
    const longHistory = Array.from({ length: 12 }, (_, index) => ({
      id: `10000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
      category: 'IT',
      timeToClaimMinutes: index + 1,
      timeInProgressMinutes: 20,
      reopenRatePercent: 5,
      effectiveAt: `2026-10-${String(index + 1).padStart(2, '0')}T12:00:00Z`,
    }))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      ...configuration,
      categories: [{ ...configuration.categories[0], history: longHistory }],
    })))

    render(<AdministratorSloConfiguration onRefreshSession={vi.fn()} />)

    const history = await screen.findByRole('region', { name: 'IT SLO version history' })
    expect(within(history).queryByText('2 min')).not.toBeInTheDocument()
    const pagination = screen.getByRole('navigation', { name: 'IT SLO version history pages' })
    expect(pagination).toHaveTextContent('Page 1 of 2')
    fireEvent.click(within(pagination).getByRole('button', { name: 'Next' }))
    expect(within(history).getByText('2 min')).toBeInTheDocument()
    expect(pagination).toHaveTextContent('Page 2 of 2')
  })
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
