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
      if (url === '/api/v1/reporter/promotion-request') {
        historyRequests += 1
        return response({ requests: historyRequests === 1 ? [] : [{
          requestId: 'request-1',
          requestedCategories: ['IT'],
          approvedCategories: null,
          comments: 'I can help with IT.',
          requestedAt: '2026-10-19T08:00:00Z',
          status: 'PENDING',
          decidedAt: null,
        }] })
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
    const fetchMock = vi.fn(async () => response({ requests: [] }))
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionPanel onRefreshSession={async () => ({ authenticated: true, role: 'REPORTER' })} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Submit request' }))

    expect(await screen.findByText('Select at least one category.')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
