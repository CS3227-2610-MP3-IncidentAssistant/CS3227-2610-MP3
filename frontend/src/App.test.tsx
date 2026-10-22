import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

const anonymousSession = {
  authenticated: false,
}

const administratorSession = {
  authenticated: true,
  loginName: 'operations-admin',
  role: 'ADMINISTRATOR',
}

const emptyDashboard = {
  incidents: [], page: 0, size: 25, totalElements: 0, totalPages: 1,
  sloTargets: [
    { category: 'FACILITIES', timeToClaimMinutes: null, timeInProgressMinutes: null, reopenRatePercent: null, effectiveAt: null },
    { category: 'HUMAN_RELATIONS', timeToClaimMinutes: null, timeInProgressMinutes: null, reopenRatePercent: null, effectiveAt: null },
    { category: 'IT', timeToClaimMinutes: 30, timeInProgressMinutes: 120, reopenRatePercent: 5, effectiveAt: '2026-10-18T00:00:00Z' },
  ],
}

beforeEach(() => setCsrfCookie('test-csrf'))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  clearCsrfCookie()
})

describe('web authentication and administrator slice', () => {
  it('blocks role workflows behind the required password dialog for a temporary credential', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response({ ...administratorSession, credentialTemporary: true })
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)

    const dialog = await screen.findByRole('dialog', { name: 'Replace your temporary password to continue' })
    expect(within(dialog).queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
    fireEvent(dialog, new Event('cancel', { cancelable: true }))
    expect(screen.getByRole('dialog', { name: 'Replace your temporary password to continue' })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/v1/admin/'))).toBe(false)
  })

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

  it('lets an approved account sign in through Responder and sees only granted categories', async () => {
    let sessionRequests = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        return sessionRequests === 1
          ? response(anonymousSession)
          : response({
              authenticated: true,
              loginName: 'approved-responder',
              role: 'RESPONDER',
              responderCategories: ['IT'],
            })
      }
      if (url === '/api/v1/auth/responder/login' && init?.method === 'POST') return response({}, 204)
      if (url === '/api/v1/responder/incidents/query' && init?.method === 'POST') {
        return response({
          incidents: [], page: 0, size: 25, totalElements: 0, totalPages: 1, categoriesConfigured: true,
        })
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Responder' }))
    expect(await screen.findByRole('heading', { name: 'Responder sign in' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'approved-responder' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'valid-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Incident queue' })).toBeInTheDocument()
    expect(await screen.findByLabelText('IT')).toBeInTheDocument()
    expect(screen.queryByText('Facilities')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/responder/login',
      expect.objectContaining({ method: 'POST' }),
    )
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
    expect(await screen.findByText('0h 30m')).toBeInTheDocument()
    expect(await screen.findByText('No incidents match')).toBeInTheDocument()
  })

  it('opens an incident detail from the administrator dashboard and returns to the list', async () => {
    const incidentId = '30000000-0000-0000-0000-000000000009'
    const populatedDashboard = {
      ...emptyDashboard,
      incidents: [{
        id: incidentId,
        title: 'Lift stuck between floors',
        category: 'FACILITIES',
        status: 'SUBMITTED',
        reporterLabel: 'Anonymous reporter',
        assigneeLabel: null,
        createdAt: '2026-10-20T08:00:00Z',
      }],
      totalElements: 1,
    }
    const detail = {
      id: incidentId,
      version: 3,
      title: 'Lift stuck between floors',
      description: 'Trapped between two and three.',
      category: 'FACILITIES',
      status: 'SUBMITTED',
      anonymous: true,
      reporterLabel: 'Anonymous reporter',
      assigneeLabel: null,
      createdAt: '2026-10-20T08:00:00Z',
      submittedAt: '2026-10-20T08:01:00Z',
      withdrawnAt: null,
      queueEnteredAt: '2026-10-20T08:01:00Z',
      firstAssignedAt: null,
      latestAssignedAt: null,
      slo: null,
      resolutionHistory: [],
      comments: [],
      attachments: [],
      eligibleResponders: [],
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') return response(administratorSession)
      if (url.startsWith('/api/v1/admin/dashboard')) return response(populatedDashboard)
      if (url === `/api/v1/admin/incidents/${incidentId}`) return response(detail)
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: 'View details for Lift stuck between floors' }))

    expect(await screen.findByRole('heading', { level: 2, name: 'Lift stuck between floors' })).toBeInTheDocument()
    expect(screen.getByText('Trapped between two and three.')).toBeInTheDocument()
    // The anonymous reporter is never deanonymized in the detail view.
    expect(screen.getAllByText('Anonymous reporter').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole('button', { name: 'Back to dashboard' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Company incident overview' })).toBeInTheDocument()
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

  it('does not refresh or retry an authorization failure that is not a CSRF error', async () => {
    let sessionRequests = 0
    let loginRequests = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        return response(anonymousSession)
      }
      if (url === '/api/v1/auth/administrator/login' && init?.method === 'POST') {
        loginRequests += 1
        return response({ code: 'UNAVAILABLE', message: 'Resource unavailable' }, 403)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Administrator' }))
    fireEvent.change(await screen.findByLabelText('Login name'), { target: { value: 'blocked-user' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'blocked-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in failed')
    expect(loginRequests).toBe(1)
    expect(sessionRequests).toBe(1)
  })

  it.each([
    ['Reporter', '/api/v1/auth/reporter/login'],
    ['Administrator', '/api/v1/auth/administrator/login'],
  ])('refreshes an expired security session and retries %s sign-in once', async (portal, loginUrl) => {
    setCsrfCookie('stale-csrf')
    let sessionRequests = 0
    const loginTokens: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        if (sessionRequests === 2) setCsrfCookie('fresh-csrf')
        return response(anonymousSession)
      }
      if (url === loginUrl && init?.method === 'POST') {
        loginTokens.push(requestHeader(init, 'X-XSRF-TOKEN'))
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
    setCsrfCookie('stale-csrf')
    let sessionRequests = 0
    const loginTokens: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        if (sessionRequests === 3) {
          setCsrfCookie('authenticated-csrf')
          return response({
            authenticated: true,
            loginName: 'retry-reporter',
            role: 'REPORTER',
          })
        }
        if (sessionRequests === 2) setCsrfCookie('fresh-csrf')
        return response(anonymousSession)
      }
      if (url === '/api/v1/auth/reporter/login' && init?.method === 'POST') {
        loginTokens.push(requestHeader(init, 'X-XSRF-TOKEN'))
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
    setCsrfCookie('stale-csrf')
    let sessionRequests = 0
    const registrationTokens: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        if (sessionRequests === 2) setCsrfCookie('fresh-csrf')
        return response(anonymousSession)
      }
      if (url === '/api/v1/auth/reporter/register' && init?.method === 'POST') {
        const token = requestHeader(init, 'X-XSRF-TOKEN')
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
    setCsrfCookie('stale-csrf')
    let sessionRequests = 0
    let registrationRequests = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        if (sessionRequests === 2) setCsrfCookie('fresh-csrf')
        return response(anonymousSession)
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

  it('refreshes a stale cross-tab token and retries logout once', async () => {
    setCsrfCookie('stale-tab-token')
    let sessionRequests = 0
    const logoutTokens: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        if (sessionRequests === 2) setCsrfCookie('current-tab-token')
        return response(administratorSession)
      }
      if (url.startsWith('/api/v1/admin/dashboard')) return response(emptyDashboard)
      if (url === '/api/v1/auth/logout' && init?.method === 'POST') {
        logoutTokens.push(requestHeader(init, 'X-XSRF-TOKEN'))
        return logoutTokens.length === 1
          ? response({ code: 'SECURITY_SESSION_EXPIRED', message: 'Security session expired.' }, 403)
          : response({}, 204)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Choose your portal' })).toBeInTheDocument()
    expect(logoutTokens).toEqual(['stale-tab-token', 'current-tab-token'])
    expect(sessionRequests).toBe(2)
  })

  it('clears protected state without retrying logout when refresh is already anonymous', async () => {
    let sessionRequests = 0
    let logoutRequests = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/health') return response({ status: 'UP' })
      if (url === '/api/v1/auth/session') {
        sessionRequests += 1
        return response(sessionRequests === 1 ? administratorSession : anonymousSession)
      }
      if (url.startsWith('/api/v1/admin/dashboard')) return response(emptyDashboard)
      if (url === '/api/v1/auth/logout' && init?.method === 'POST') {
        logoutRequests += 1
        return response({ code: 'SECURITY_SESSION_EXPIRED', message: 'Security session expired.' }, 403)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Choose your portal' })).toBeInTheDocument()
    expect(logoutRequests).toBe(1)
    expect(sessionRequests).toBe(2)
  })
})

function response(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

function requestHeader(init: RequestInit | undefined, name: string): string {
  return new Headers(init?.headers).get(name) ?? ''
}

function setCsrfCookie(value: string) {
  document.cookie = `XSRF-TOKEN=${encodeURIComponent(value)}; Path=/`
}

function clearCsrfCookie() {
  document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0'
}
