import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PromotionPanel } from './PromotionPanel'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

describe('Reporter promotion requests', () => {
  it('submits selected categories and shows the retained request history', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-value; path=/'
    let historyRequests = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/v1/reporter/promotion-request' && init?.method === 'POST') {
        return response({
          requestId: 'request-1',
          requestedCategories: ['IT'],
          approvedCategories: null,
          comments: 'I can help with IT.',
          requestedAt: '2026-10-19T08:00:00Z',
          status: 'PENDING',
          decidedAt: null,
        }, 201)
      }
      if (url === '/api/v1/reporter/promotion-request?page=0&size=25') {
        historyRequests += 1
        const requests = historyRequests === 1 ? [] : [{
          requestId: 'request-1',
          requestedCategories: ['IT'],
          approvedCategories: null,
          comments: 'I can help with IT.',
          requestedAt: '2026-10-19T08:00:00Z',
          status: 'PENDING',
          decidedAt: null,
        }]
        return response({ requests, page: 0, size: 25, totalElements: requests.length, totalPages: 1 })
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionPanel onRefreshSession={async () => ({ authenticated: true, role: 'REPORTER' })} />)
    fireEvent.click(await screen.findByLabelText('IT'))
    fireEvent.change(screen.getByLabelText('Additional comments (optional)'), {
      target: { value: 'I can help with IT.' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByText('Your promotion request was submitted.')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/reporter/promotion-request',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ requestedCategories: ['IT'], comments: 'I can help with IT.' }),
      }),
    ))
    expect(await screen.findByText('PENDING')).toBeInTheDocument()
    expect(screen.getByText('IT', { selector: 'td' })).toBeInTheDocument()
  })

  it('does not submit an empty category selection', async () => {
    const fetchMock = vi.fn(async () => response({
      requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1,
    }))
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionPanel onRefreshSession={async () => ({ authenticated: true, role: 'REPORTER' })} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Submit request' }))

    expect(await screen.findByText('Select at least one category.')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('pages through retained history and renders comments as text', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const page = url.includes('page=1') ? 1 : 0
      return response({
        requests: [{
          requestId: `request-${page}`,
          requestedCategories: ['FACILITIES'],
          approvedCategories: [],
          comments: page === 0 ? 'First page' : '<img src=x onerror=alert(1)>',
          requestedAt: '2026-10-19T08:00:00Z',
          status: 'REJECTED',
          decidedAt: '2026-10-20T08:00:00Z',
        }],
        page,
        size: 25,
        totalElements: 26,
        totalPages: 2,
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionPanel onRefreshSession={async () => ({ authenticated: true, role: 'REPORTER' })} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }))

    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/v1/reporter/promotion-request?page=1&size=25',
      expect.objectContaining({ credentials: 'same-origin' }),
    )
  })

  it('blocks duplicate submission while the first request is pending', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-value; path=/'
    let resolveSubmission: ((value: Response) => void) | undefined
    const pendingSubmission = new Promise<Response>((resolve) => { resolveSubmission = resolve })
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return pendingSubmission
      return response({ requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionPanel onRefreshSession={async () => ({ authenticated: true, role: 'REPORTER' })} />)
    fireEvent.click(await screen.findByLabelText('IT'))
    const submit = screen.getByRole('button', { name: 'Submit request' })
    fireEvent.click(submit)

    expect(await screen.findByRole('button', { name: 'Submitting…' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Submitting…' }))
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)

    resolveSubmission?.(response({
      requestId: 'request-pending', requestedCategories: ['IT'], approvedCategories: null,
      comments: null, requestedAt: '2026-10-19T08:00:00Z', status: 'PENDING', decidedAt: null,
    }, 201))
    expect(await screen.findByText('Your promotion request was submitted.')).toBeInTheDocument()
  })

  it('keeps the form unavailable when history fails and offers refresh', async () => {
    const fetchMock = vi.fn(async () => response({ code: 'PROMOTION_UNAVAILABLE' }, 503))
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionPanel onRefreshSession={async () => ({ authenticated: true, role: 'REPORTER' })} />)

    expect(await screen.findByText(
      'Request history must load before you can submit another request.',
    )).toHaveAttribute('role', 'alert')
    expect(screen.queryByRole('button', { name: 'Submit request' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })

  it('preserves the selected form values after a recoverable submission failure', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-value; path=/'
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method === 'POST'
      ? response({ code: 'PROMOTION_UNAVAILABLE', message: 'Try again later.' }, 503)
      : response({ requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1 }))
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionPanel onRefreshSession={async () => ({ authenticated: true, role: 'REPORTER' })} />)
    const category = await screen.findByLabelText('Facilities')
    const comments = screen.getByLabelText('Additional comments (optional)')
    fireEvent.click(category)
    fireEvent.change(comments, { target: { value: 'Keep this rationale' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Try again later.')
    expect(category).toBeChecked()
    expect(comments).toHaveValue('Keep this rationale')
  })
})

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
