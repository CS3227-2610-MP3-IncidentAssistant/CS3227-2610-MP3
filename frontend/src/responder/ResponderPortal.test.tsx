import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
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

  it('opens authorized detail, renders untrusted fields as text, and returns to the same queue', async () => {
    const scriptText = '<script>window.compromised = true</script>'
    const detail = {
      id: '60000000-0000-0000-0000-000000000001',
      title: '<img src=x onerror=alert(1)> outage',
      description: scriptText,
      reporterLabel: 'Anonymous reporter',
      category: 'IT',
      status: 'SUBMITTED',
      submittedAt: '2026-10-20T01:00:00Z',
      queueEnteredAt: '2026-10-20T01:00:00Z',
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'GET') return response(detail)
      return response({ ...basePage, incidents: [row('Open this incident')], totalElements: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Open this incident' }))

    expect(await screen.findByRole('heading', { name: detail.title })).toBeInTheDocument()
    expect(screen.getByText(scriptText)).toBeInTheDocument()
    expect(document.querySelector('script')).not.toBeInTheDocument()
    expect(document.querySelector('img')).not.toBeInTheDocument()
    expect(screen.getByText('Anonymous reporter')).toBeInTheDocument()
    expect(fetchMock.mock.calls[1]?.[0]).toBe(`/api/v1/responder/incidents/${detail.id}`)
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: 'GET', credentials: 'same-origin', cache: 'no-store' })

    fireEvent.click(screen.getByRole('button', { name: 'Back to queue' }))
    expect(await screen.findByRole('button', { name: 'View details for Open this incident' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toMatchObject({ page: 0, size: 25 })
    expect(screen.queryByText(scriptText)).not.toBeInTheDocument()
  })

  it('retries a failed detail request and focuses the page heading on navigation', async () => {
    const detail = {
      id: row('Retry this incident').id,
      title: 'Recovered incident details',
      description: 'Safe after retry',
      reporterLabel: 'Reporter',
      category: 'IT',
      status: 'SUBMITTED',
      submittedAt: row('Retry this incident').submittedAt,
      queueEnteredAt: row('Retry this incident').queueEnteredAt,
    }
    let detailRequests = 0
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'GET') {
        detailRequests++
        return detailRequests === 1 ? response({ message: 'internal failure' }, 503) : response(detail)
      }
      return response({ ...basePage, incidents: [row('Retry this incident')], totalElements: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Retry this incident' }))
    expect(await screen.findByRole('heading', { name: 'Details unavailable' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Incident details' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Recovered incident details' })).toBeInTheDocument()
    expect(detailRequests).toBe(2)
    fireEvent.click(screen.getByRole('button', { name: 'Back to queue' }))
    expect(await screen.findByRole('button', { name: 'View details for Retry this incident' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Incident queue' })).toHaveFocus()
  })

  it('preserves the queue page when returning from incident details', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'GET') return response({
        id: row('Page two incident').id,
        title: 'Page two details',
        description: 'Details',
        reporterLabel: 'Reporter',
        category: 'IT',
        status: 'SUBMITTED',
        submittedAt: row('Page two incident').submittedAt,
        queueEnteredAt: row('Page two incident').queueEnteredAt,
      })
      const body = JSON.parse(String(init?.body)) as { page: number }
      return response({ ...basePage, page: body.page, incidents: [row(body.page === 1
        ? 'Page two incident' : 'Page one incident')], totalElements: 26, totalPages: 2 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    expect(await screen.findByText('Page one incident')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.click(await screen.findByRole('button', { name: 'View details for Page two incident' }))
    expect(await screen.findByRole('heading', { name: 'Page two details' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Back to queue' }))

    expect(await screen.findByText('Page two incident')).toBeInTheDocument()
    expect(JSON.parse(String(fetchMock.mock.calls.at(-1)?.[1]?.body))).toMatchObject({ page: 1 })
  })

  it('claims a submitted incident once and confirms the server state', async () => {
    let finishClaim: ((result: Response) => void) | undefined
    let finishRefresh: ((result: Response) => void) | undefined
    let claimCommitted = false
    let detailRequests = 0
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/claim')) {
        return new Promise<Response>((resolve) => {
          finishClaim = (result) => {
            claimCommitted = true
            resolve(result)
          }
        })
      }
      if (init?.method === 'GET') {
        detailRequests++
        if (claimCommitted) return new Promise<Response>((resolve) => { finishRefresh = resolve })
        return Promise.resolve(response(detailRecord('SUBMITTED')))
      }
      return Promise.resolve(response({ ...basePage, incidents: [row('Claim this incident')], totalElements: 1 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Claim this incident' }))
    expect(await screen.findByRole('button', { name: 'Claim incident' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Claim incident' }))
    const claimButton = screen.getByRole('button', { name: 'Claiming…' })
    expect(claimButton).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Back to queue' })).toBeDisabled()
    fireEvent.click(claimButton)
    expect(fetchMock.mock.calls.filter(([input]) => String(input).endsWith('/claim'))).toHaveLength(1)

    const claimCall = fetchMock.mock.calls.find(([input]) => String(input).endsWith('/claim'))
    expect(claimCall?.[1]).toMatchObject({ method: 'POST', credentials: 'same-origin', cache: 'no-store' })
    expect(claimCall?.[1]?.body).toBeUndefined()
    expect(new Headers(claimCall?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('responder-csrf')
    await act(async () => finishClaim?.(response({ claimed: true })))

    expect(await screen.findByText(/Incident claimed and assigned to you/)).toBeInTheDocument()
    expect(await screen.findByText('Loading incident details…')).toBeInTheDocument()
    expect(screen.queryByText(/Submitted/, { selector: '.eyebrow' })).not.toBeInTheDocument()
    await waitFor(() => expect(detailRequests).toBe(2))
    await act(async () => finishRefresh?.(response(detailRecord('ASSIGNED'))))

    expect(await screen.findByText(/Assigned/, { selector: '.eyebrow' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Claim incident' })).not.toBeInTheDocument()
  })

  it('shows a generic claim conflict and reconciles detail without exposing the other assignee', async () => {
    let detailRequests = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/claim')) return response({ code: 'CLAIM_CONFLICT' }, 409)
      if (init?.method === 'GET') {
        detailRequests++
        return detailRequests === 1
          ? response(detailRecord('SUBMITTED'))
          : response({ code: 'UNAVAILABLE', message: 'Resource unavailable' }, 404)
      }
      return response({ ...basePage, incidents: [row('Restricted claim')], totalElements: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Restricted claim' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Claim incident' }))

    expect(await screen.findByRole('heading', { name: 'Incident unavailable' })).toBeInTheDocument()
    expect(screen.getByText('This incident is no longer available to claim. Check its current status before trying again.'))
      .toBeInTheDocument()
    expect(screen.getByText('This incident is unavailable to your current responder session.')).toBeInTheDocument()
    expect(screen.queryByText(/claimed by|assignee/i)).not.toBeInTheDocument()
  })

  it('keeps claim success feedback visible when the follow-up detail read fails', async () => {
    let detailRequests = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/claim')) return response({ claimed: true })
      if (init?.method === 'GET') {
        detailRequests++
        return detailRequests === 1
          ? response(detailRecord('SUBMITTED'))
          : response({ message: 'private database failure' }, 503)
      }
      return response({ ...basePage, incidents: [row('Claim with transient refresh failure')], totalElements: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Claim with transient refresh failure' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Claim incident' }))

    expect(await screen.findByText(/Incident claimed and assigned to you/)).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Details unavailable' })).toBeInTheDocument()
    expect(screen.queryByText('private database failure')).not.toBeInTheDocument()
  })

  it('clears protected detail and queue after a claim session expiry', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/claim')) return response({ code: 'SECURITY_SESSION_EXPIRED' }, 401)
      if (init?.method === 'GET') return response(detailRecord('SUBMITTED'))
      return response({ ...basePage, incidents: [row('Session-bound claim')], totalElements: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Session-bound claim' }))
    expect(await screen.findByRole('heading', { name: 'Protected incident details' })).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('button', { name: 'Claim incident' }))

    expect(await screen.findByRole('heading', { name: 'Incident queue' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Your session has expired or changed')
    expect(screen.queryByText('Private detail content')).not.toBeInTheDocument()
  })

  it('does not offer a claim action for an already-assigned incident', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method === 'GET'
      ? response(detailRecord('ASSIGNED'))
      : response({ ...basePage, incidents: [row('Already assigned', 'ASSIGNED')], totalElements: 1 }))
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Already assigned' }))
    expect(await screen.findByRole('heading', { name: 'Protected incident details' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Claim incident' })).not.toBeInTheDocument()
  })

  it('reconciles an uncertain claim failure without automatically resubmitting the mutation', async () => {
    let claimRequests = 0
    let detailRequests = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/claim')) {
        claimRequests++
        return response({ message: 'temporarily unavailable' }, 503)
      }
      if (init?.method === 'GET') {
        detailRequests++
        return response(detailRecord('SUBMITTED'))
      }
      return response({ ...basePage, incidents: [row('Uncertain claim')], totalElements: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Uncertain claim' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Claim incident' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The claim outcome could not be confirmed')
    expect(claimRequests).toBe(1)
    fireEvent.click(screen.getByRole('button', { name: 'Check status' }))

    expect(await screen.findByRole('button', { name: 'Claim incident' })).toBeEnabled()
    expect(detailRequests).toBeGreaterThanOrEqual(3)
    expect(claimRequests).toBe(1)
  })

  it.each([403, 404])('shows the same safe unavailable detail state for HTTP %i', async (status) => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method === 'GET'
      ? response({ code: 'UNAVAILABLE', message: 'private server details' }, status)
      : response({ ...basePage, incidents: [row('Restricted incident')], totalElements: 1 }))
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Restricted incident' }))

    expect(await screen.findByRole('heading', { name: 'Incident unavailable' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('unavailable to your current responder session')
    expect(screen.queryByText('private server details')).not.toBeInTheDocument()
  })

  it('does not restore a detail response after returning to the queue', async () => {
    let finishDetail: ((result: Response) => void) | undefined
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => init?.method === 'GET'
      ? new Promise<Response>((resolve) => { finishDetail = resolve })
      : Promise.resolve(response({ ...basePage, incidents: [row('Delayed incident')], totalElements: 1 })))
    vi.stubGlobal('fetch', fetchMock)
    render(portal())

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Delayed incident' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    fireEvent.click(screen.getByRole('button', { name: 'Back to queue' }))
    expect(await screen.findByRole('button', { name: 'View details for Delayed incident' })).toBeInTheDocument()
    await act(async () => {
      finishDetail?.(response({
        id: row('Delayed incident').id,
        title: 'Late protected detail',
        description: 'Late private content',
        reporterLabel: 'Anonymous reporter',
        category: 'IT',
        status: 'SUBMITTED',
        submittedAt: row('Delayed incident').submittedAt,
        queueEnteredAt: row('Delayed incident').queueEnteredAt,
      }))
    })

    expect(screen.queryByText('Late private content')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Late protected detail' })).not.toBeInTheDocument()
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

function detailRecord(status: 'SUBMITTED' | 'ASSIGNED') {
  return {
    id: row('Responder detail').id,
    title: 'Protected incident details',
    description: 'Private detail content',
    reporterLabel: 'Anonymous reporter',
    category: 'IT',
    status,
    submittedAt: row('Responder detail').submittedAt,
    queueEnteredAt: row('Responder detail').queueEnteredAt,
  }
}

function response(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response
}
