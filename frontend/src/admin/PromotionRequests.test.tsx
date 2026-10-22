import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PromotionRequests } from './PromotionRequests'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

describe('Administrator promotion review', () => {
  it('only submits selected categories from the pending request', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-value; path=/'
    const request = {
      requestId: 'request-2',
      requesterLabel: 'reporter-two',
      requestedCategories: ['IT', 'FACILITIES'],
      approvedCategories: null,
      comments: 'Relevant experience',
      requestedAt: '2026-10-19T08:00:00Z',
      status: 'PENDING',
      decidedAt: null,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/decision') && init?.method === 'POST') {
        return response({ ...request, approvedCategories: ['IT'], status: 'APPROVED' })
      }
      if (url.startsWith('/api/v1/admin/promotion-requests')) {
        return response({ requests: [request], page: 0, size: 25, totalElements: 1, totalPages: 1 })
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionRequests onRefreshSession={async () => ({ authenticated: true, role: 'ADMINISTRATOR' })} />)
    expect(await screen.findByRole('heading', { name: 'reporter-two' })).toBeInTheDocument()
    expect(screen.getByLabelText('IT')).toBeChecked()
    expect(screen.getByLabelText('Facilities')).toBeChecked()
    fireEvent.click(screen.getByLabelText('Facilities'))
    fireEvent.click(screen.getByRole('button', { name: 'Approve selected categories' }))
    expect(screen.getByRole('dialog')).toHaveTextContent(
      'Promote reporter-two and grant exactly IT access?',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Confirm promotion' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/promotion-requests/request-2/decision',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ decision: 'APPROVE', approvedCategories: ['IT'] }),
      }),
    ))
    expect(await screen.findByRole('status')).toHaveFocus()
  })

  it('requires a category selection before approving', async () => {
    const fetchMock = vi.fn(async () => response({
      requests: [{
        requestId: 'request-3',
        requesterLabel: 'reporter-three',
        requestedCategories: ['FACILITIES'],
        approvedCategories: null,
        comments: null,
        requestedAt: '2026-10-19T08:00:00Z',
        status: 'PENDING',
        decidedAt: null,
      }],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
    }))
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionRequests onRefreshSession={async () => ({ authenticated: true, role: 'ADMINISTRATOR' })} />)
    fireEvent.click(await screen.findByLabelText('Facilities'))
    fireEvent.click(await screen.findByRole('button', { name: 'Approve selected categories' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Select at least one category')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('confirms rejection without offering a category grant', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-value; path=/'
    const request = {
      requestId: 'request-4',
      requesterLabel: 'reporter-four',
      requestedCategories: ['IT'],
      approvedCategories: null,
      comments: null,
      requestedAt: '2026-10-19T08:00:00Z',
      status: 'PENDING',
      decidedAt: null,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/decision') && init?.method === 'POST') {
        return response({ ...request, approvedCategories: [], status: 'REJECTED' })
      }
      return response({ requests: [request], page: 0, size: 25, totalElements: 1, totalPages: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<PromotionRequests onRefreshSession={async () => ({ authenticated: true, role: 'ADMINISTRATOR' })} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }))

    expect(screen.getByRole('dialog')).toHaveTextContent(
      "The request will close without changing their role or category access.",
    )
    fireEvent.click(screen.getByRole('button', { name: 'Confirm rejection' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/promotion-requests/request-4/decision',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ decision: 'REJECT' }),
      }),
    ))
  })
})

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
