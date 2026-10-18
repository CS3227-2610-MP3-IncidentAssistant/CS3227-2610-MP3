import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'

const reporterSession = {
  authenticated: true,
  loginName: 'case-reporter',
  role: 'REPORTER',
  csrfHeader: 'X-CSRF-TOKEN',
  csrfToken: 'reporter-csrf',
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('Reporter walking slice', () => {
  it('provides the shared responsive navigation menu', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(reporterSession)
      if (url.startsWith('/api/v1/reporter/incidents?')) {
        return response({ incidents: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)

    const menuButton = await screen.findByRole('button', { name: 'Menu' })
    expect(menuButton).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(menuButton)
    expect(menuButton).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByLabelText('Reporter navigation')).toHaveClass('sidebar--open')
  })

  it('renders only the authenticated Reporter incidents and explicit anonymity state', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(reporterSession)
      if (url.startsWith('/api/v1/reporter/incidents?')) return response({
        incidents: [{
          id: 'incident-owned', title: 'Private outage', category: 'IT', status: 'SUBMITTED',
          anonymous: true, createdAt: '2026-10-18T09:30:00Z', submittedAt: '2026-10-18T09:30:00Z',
          assigneeLabel: null, version: 0,
        }],
        page: 0, size: 25, totalElements: 1, totalPages: 1,
      })
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Report and track incidents' })).toBeInTheDocument()
    expect(await screen.findByText('Private outage')).toBeInTheDocument()
    expect(screen.getByText('Anonymous')).toBeInTheDocument()
    expect(screen.getByText('case-reporter')).toBeInTheDocument()
  })

  it('submits the explicit anonymity value with CSRF and refreshes the owned list', async () => {
    vi.stubGlobal('crypto', { randomUUID: () => '30000000-0000-0000-0000-000000000001' })
    let listCalls = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(reporterSession)
      if (url.startsWith('/api/v1/reporter/incidents?')) {
        listCalls++
        return response({ incidents: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      if (url === '/api/v1/reporter/incidents' && init?.method === 'POST') return response({ id: 'created' }, 201)
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Printer outage' } })
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'FACILITIES' } })
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Printer is offline.' } })
    fireEvent.click(screen.getByLabelText('Submit anonymously'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit incident' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Incident submitted')
    await waitFor(() => expect(listCalls).toBe(2))
    const submission = fetchMock.mock.calls.find(([url]) => String(url) === '/api/v1/reporter/incidents')
    expect(submission?.[1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'reporter-csrf' })
    expect(JSON.parse(String(submission?.[1]?.body))).toMatchObject({
      title: 'Printer outage', description: 'Printer is offline.', category: 'FACILITIES', anonymous: true,
      submissionKey: '30000000-0000-0000-0000-000000000001',
    })
  })
})

function response(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}
