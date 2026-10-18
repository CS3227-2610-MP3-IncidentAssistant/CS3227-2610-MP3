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

describe('web authentication and administrator slice', () => {
  it('shows role entry before the role-specific login without loading protected data', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(anonymousSession)
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Administrator' }))
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
    fireEvent.click(await screen.findByRole('button', { name: 'Administrator' }))
    fireEvent.change(await screen.findByLabelText('Login name'), { target: { value: 'unknown' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'incorrect' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in failed')
    await waitFor(() => expect(screen.getByLabelText('Password')).toHaveValue(''))
  })

  it.each([
    ['Reporter', '/api/v1/auth/reporter/login'],
    ['Administrator', '/api/v1/auth/administrator/login'],
  ])('refreshes an expired security session and retries %s sign-in once', async (portal, loginUrl) => {
    let sessionRequests = 0
    const loginTokens: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        return response({
          ...anonymousSession,
          csrfToken: sessionRequests === 1 ? 'stale-csrf' : 'fresh-csrf',
        })
      }
      if (url === loginUrl && init?.method === 'POST') {
        const body = init.body as URLSearchParams
        loginTokens.push(body.get('_csrf') ?? '')
        return loginTokens.length === 1
          ? response({
              code: 'SECURITY_SESSION_EXPIRED',
              message: 'Your security session expired. Try again.',
            }, 403)
          : response({ code: 'AUTHENTICATION_FAILED', message: 'Sign-in failed' }, 401)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: portal }))
    fireEvent.change(await screen.findByLabelText('Login name'), { target: { value: 'retry-user' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'retry-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in failed')
    expect(loginTokens).toEqual(['stale-csrf', 'fresh-csrf'])
    expect(sessionRequests).toBe(2)
    expect(screen.getByLabelText('Login name')).toHaveValue('retry-user')
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })

  it('completes Reporter sign-in after refreshing an expired security session', async () => {
    let sessionRequests = 0
    const loginTokens: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        if (sessionRequests === 3) {
          return response({
            authenticated: true,
            loginName: 'retry-reporter',
            role: 'REPORTER',
            csrfHeader: 'X-CSRF-TOKEN',
            csrfToken: 'authenticated-csrf',
          })
        }
        return response({
          ...anonymousSession,
          csrfToken: sessionRequests === 1 ? 'stale-csrf' : 'fresh-csrf',
        })
      }
      if (url === '/api/v1/auth/reporter/login' && init?.method === 'POST') {
        const body = init.body as URLSearchParams
        loginTokens.push(body.get('_csrf') ?? '')
        return loginTokens.length === 1
          ? response({
              code: 'SECURITY_SESSION_EXPIRED',
              message: 'Your security session expired. Try again.',
            }, 403)
          : response({}, 204)
      }
      if (url.startsWith('/api/v1/reporter/incidents')) {
        return response({ incidents: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Reporter' }))
    fireEvent.change(await screen.findByLabelText('Login name'), { target: { value: 'retry-reporter' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'retry-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Report and track incidents' })).toBeInTheDocument()
    expect(loginTokens).toEqual(['stale-csrf', 'fresh-csrf'])
    expect(sessionRequests).toBe(3)
  })

  it('creates a Reporter account without exposing a role selector', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(anonymousSession)
      if (url === '/api/v1/auth/reporter/register' && init?.method === 'POST') return response({}, 201)
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Reporter' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create Reporter account' }))
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'migration-admin' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'temporary-secret' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'temporary-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Reporter account created')
    expect(screen.getByRole('heading', { name: 'Create Reporter account' })).toBeInTheDocument()
    const registrationCall = fetchMock.mock.calls.find(([url]) => String(url) === '/api/v1/auth/reporter/register')
    expect(registrationCall?.[1]?.body).toBe(JSON.stringify({
      loginName: 'migration-admin',
      password: 'temporary-secret',
      passwordConfirmation: 'temporary-secret',
    }))
  })

  it('refreshes an expired security session and retries registration once', async () => {
    let sessionRequests = 0
    const registrationTokens: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        return response({
          ...anonymousSession,
          csrfToken: sessionRequests === 1 ? 'stale-csrf' : 'fresh-csrf',
        })
      }
      if (url === '/api/v1/auth/reporter/register' && init?.method === 'POST') {
        const token = (init.headers as Record<string, string>)['X-CSRF-TOKEN']
        registrationTokens.push(token)
        return token === 'stale-csrf'
          ? response({
              code: 'SECURITY_SESSION_EXPIRED',
              message: 'Your security session expired. Try again.',
            }, 403)
          : response({}, 201)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Reporter' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create Reporter account' }))
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'retry-reporter' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'retry-secret' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'retry-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Reporter account created')
    expect(registrationTokens).toEqual(['stale-csrf', 'fresh-csrf'])
    expect(sessionRequests).toBe(2)
  })

  it('preserves registration fields when security-session recovery fails', async () => {
    let sessionRequests = 0
    let registrationRequests = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        return response({
          ...anonymousSession,
          csrfToken: sessionRequests === 1 ? 'stale-csrf' : 'fresh-csrf',
        })
      }
      if (url === '/api/v1/auth/reporter/register' && init?.method === 'POST') {
        registrationRequests += 1
        return registrationRequests === 1
          ? response({
              code: 'SECURITY_SESSION_EXPIRED',
              message: 'Your security session expired. Try again.',
            }, 403)
          : response({ message: 'The account could not be created. Try again.' }, 503)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Reporter' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create Reporter account' }))
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'preserved-reporter' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'preserved-secret' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'preserved-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be created')
    expect(screen.getByLabelText('Login name')).toHaveValue('preserved-reporter')
    expect(screen.getByLabelText('Password')).toHaveValue('preserved-secret')
    expect(screen.getByLabelText('Confirm password')).toHaveValue('preserved-secret')
    expect(registrationRequests).toBe(2)
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
    fireEvent.click(await screen.findByRole('button', { name: 'Reporter' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create Reporter account' }))
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'reporter' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'one' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'two' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Passwords do not match')
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/v1/auth/reporter/register')).toBe(false)
  })

  it('shows the legacy duplicate-login message returned by registration', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(anonymousSession)
      if (url === '/api/v1/auth/reporter/register' && init?.method === 'POST') {
        return response({
          code: 'DUPLICATE_LOGIN_NAME',
          message: 'That case-sensitive login name is already registered.',
        }, 409)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Reporter' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create Reporter account' }))
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
