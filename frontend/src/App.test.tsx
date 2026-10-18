import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

const anonymousSession = {
  authenticated: false,
  csrfHeader: 'X-CSRF-TOKEN',
  csrfToken: 'test-csrf',
}

const administratorSession = {
  authenticated: true,
  loginName: 'operations-admin',
  role: 'ADMINISTRATOR',
  csrfHeader: 'X-CSRF-TOKEN',
  csrfToken: 'test-csrf',
}

const emptyDashboard = {
  incidents: [], page: 0, size: 25, totalElements: 0, totalPages: 1,
  sloTargets: [
    { category: 'FACILITIES', timeToClaimMinutes: null, timeInProgressMinutes: null, reopenRatePercent: null, effectiveAt: null },
    { category: 'HUMAN_RELATIONS', timeToClaimMinutes: null, timeInProgressMinutes: null, reopenRatePercent: null, effectiveAt: null },
    { category: 'IT', timeToClaimMinutes: 30, timeInProgressMinutes: 120, reopenRatePercent: 5, effectiveAt: '2026-10-18T00:00:00Z' },
  ],
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('administrator web slice', () => {
  it('shows the role-specific login without loading protected dashboard data', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(anonymousSession)
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Administrator sign in' })).toBeInTheDocument()
    expect(screen.queryByText('Company incidents')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/api/v1/admin'))).toBe(false)
  })

  it('renders privacy-safe empty dashboard data for an authenticated administrator', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(administratorSession)
      if (url.startsWith('/api/v1/admin/dashboard')) return response(emptyDashboard)
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Company incident overview' })).toBeInTheDocument()
    expect(screen.getByText('operations-admin')).toBeInTheDocument()
    expect(await screen.findByText('30 min')).toBeInTheDocument()
    expect(await screen.findByText('No incidents match')).toBeInTheDocument()
  })

  it('uses a generic message when administrator authentication is rejected', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(anonymousSession)
      if (url === '/api/v1/auth/administrator/login' && init?.method === 'POST') return response({}, 401)
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.change(await screen.findByLabelText('Login name'), { target: { value: 'unknown' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'incorrect' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in failed')
    await waitFor(() => expect(screen.getByLabelText('Password')).toHaveValue(''))
  })

  it('creates a temporary client-selected role account without signing it in', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(anonymousSession)
      if (url === '/api/v1/auth/register' && init?.method === 'POST') return response({}, 201)
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Create a temporary migration account' }))
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'migration-admin' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'temporary-secret' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'temporary-secret' } })
    fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'ADMINISTRATOR' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Account created')
    expect(screen.getByRole('heading', { name: 'Create account' })).toBeInTheDocument()
    const registrationCall = fetchMock.mock.calls.find(([url]) => String(url) === '/api/v1/auth/register')
    expect(registrationCall?.[1]?.body).toBe(JSON.stringify({
      loginName: 'migration-admin',
      password: 'temporary-secret',
      passwordConfirmation: 'temporary-secret',
      role: 'ADMINISTRATOR',
    }))
  })

  it('rejects mismatched registration passwords before calling the API', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(anonymousSession)
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Create a temporary migration account' }))
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'reporter' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'one' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'two' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Passwords do not match')
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/v1/auth/register')).toBe(false)
  })

  it('shows the legacy duplicate-login message returned by registration', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(anonymousSession)
      if (url === '/api/v1/auth/register' && init?.method === 'POST') {
        return response({
          code: 'DUPLICATE_LOGIN_NAME',
          message: 'That case-sensitive login name is already registered.',
        }, 409)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Create a temporary migration account' }))
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'existing' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'temporary-secret' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'temporary-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That case-sensitive login name is already registered.',
    )
  })

  it('reissues an unchanged search without leaving the dashboard loading', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(administratorSession)
      if (url.startsWith('/api/v1/admin/dashboard')) return response(emptyDashboard)
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    const search = await screen.findByRole('button', { name: 'Search' })
    fireEvent.click(search)
    await waitFor(() => expect(screen.queryByText('Loading incidents…')).not.toBeInTheDocument())
    fireEvent.click(search)
    await waitFor(() => expect(screen.queryByText('Loading incidents…')).not.toBeInTheDocument())

    expect(fetchMock.mock.calls.filter(([url]) => String(url).startsWith('/api/v1/admin/dashboard'))).toHaveLength(3)
  })
})

function response(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}
