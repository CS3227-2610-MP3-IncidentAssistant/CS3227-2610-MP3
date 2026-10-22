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
})

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
