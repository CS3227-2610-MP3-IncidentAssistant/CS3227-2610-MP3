import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ResponderPortal } from './ResponderPortal'

const basePage = {
  incidents: [],
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 1,
  categoriesConfigured: true,
}

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=responder-csrf; Path=/'
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0'
})

describe('Responder incident queue', () => {
  it('loads server rows and posts bounded filters without putting search text in the URL', async () => {
    let requestCount = 0
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body)) as { page: number }
      const requestIndex = requestCount++
      if (requestIndex === 1) {
        return response({
          ...basePage,
          incidents: [row('server result despite search', 'ASSIGNED')],
          totalElements: 26,
          totalPages: 2,
        })
      }
      if (body.page === 1) {
        return response({
          ...basePage,
          incidents: [row('Second page incident', 'ASSIGNED')],
          page: 1,
          totalElements: 26,
          totalPages: 2,
        })
      }
      return response({
        ...basePage,
        incidents: [row('First page incident')],
        totalElements: 26,
        totalPages: 2,
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    expect(screen.getByRole('status')).toHaveTextContent('Loading incidents')
    expect(await screen.findByText('First page incident')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Search incidents'), { target: { value: ' VPN outage ' } })
    fireEvent.click(screen.getByLabelText('IT'))
    fireEvent.click(screen.getByLabelText('Assigned'))
    fireEvent.change(screen.getByLabelText('Assignment'), { target: { value: 'ASSIGNED' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))

    expect(await screen.findByText('server result despite search')).toBeInTheDocument()
    const filteredCall = fetchMock.mock.calls[1]
    expect(filteredCall?.[0]).toBe('/api/v1/responder/incidents/query')
    expect(String(filteredCall?.[0])).not.toContain('VPN outage')
    expect(new Headers(filteredCall?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('responder-csrf')
    expect(JSON.parse(String(filteredCall?.[1]?.body))).toEqual({
      search: 'VPN outage',
      categories: ['IT'],
      statuses: ['ASSIGNED'],
      assignment: 'ASSIGNED',
      page: 0,
      size: 25,
    })

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Second page incident')).toBeInTheDocument()
    expect(screen.queryByText('server result despite search')).not.toBeInTheDocument()
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toMatchObject({ page: 1, size: 25 })
  })

  it('keeps already-assigned rows visible when no categories remain and hides category filters', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({
      ...basePage,
      categoriesConfigured: false,
      incidents: [row('Still assigned to me', 'ASSIGNED')],
      totalElements: 1,
    })))
    render(portal())

    expect(await screen.findByText('Still assigned to me')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('No categories are currently assigned')
    expect(screen.queryByLabelText('IT')).not.toBeInTheDocument()
  })

  it('distinguishes no categories from an empty configured queue', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ ...basePage, categoriesConfigured: false })))
    render(portal())

    expect(await screen.findByRole('heading', { name: 'No assigned incidents to show' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('No categories are currently assigned')
  })

  it.each([
    [404, 'The incident queue is unavailable for this session.'],
    [503, 'Incidents are temporarily unavailable.'],
  ])('shows a safe recoverable state for HTTP %i', async (status, message) => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ message: 'private server exception' }, status)))
    render(portal())

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByText('private server exception')).not.toBeInTheDocument()
  })

  it('clears the queue on session expiry and returns to portal selection on request', async () => {
    const onLogout = vi.fn(async () => {})
    vi.stubGlobal('fetch', vi.fn(async () => response({ message: 'expired' }, 401)))
    render(portal(onLogout))

    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has expired or changed')
    expect(screen.queryByText('First page incident')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Return to portal selection' }))
    await waitFor(() => expect(onLogout).toHaveBeenCalledOnce())
  })

  it('ignores an older response even when the request mock does not honor abort', async () => {
    const pending: Array<(result: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => pending.push(resolve)))
    vi.stubGlobal('fetch', fetchMock)
    render(portal())
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

    fireEvent.change(screen.getByLabelText('Search incidents'), { target: { value: 'newer query' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    pending[1]?.(response({ ...basePage, incidents: [row('Newer server row')], totalElements: 1 }))
    expect(await screen.findByText('Newer server row')).toBeInTheDocument()
    pending[0]?.(response({ ...basePage, incidents: [row('Stale server row')], totalElements: 1 }))

    await waitFor(() => expect(screen.queryByText('Stale server row')).not.toBeInTheDocument())
    expect(screen.getByText('Newer server row')).toBeInTheDocument()
  })
})

function portal(onLogout: () => Promise<void> = async () => {}) {
  return (
    <ResponderPortal
      categories={['IT', 'FACILITIES']}
      loginName="responder-account"
      onLogout={onLogout}
      serviceHealth="available"
    />
  )
}

function row(title: string, status: 'SUBMITTED' | 'ASSIGNED' = 'SUBMITTED') {
  return {
    id: '60000000-0000-0000-0000-000000000001',
    title,
    category: 'IT',
    status,
    submittedAt: '2026-10-20T01:00:00Z',
    queueEnteredAt: '2026-10-20T01:00:00Z',
  }
}

function response(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response
}
