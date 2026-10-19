import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ReporterDashboard } from './ReporterDashboard'

const session = { authenticated: true, loginName: 'reporter', role: 'REPORTER' }
const refreshSession = async () => session
const aiUrl = '/api/v1/reporter/ai/description'
function response(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}
function setup(
  ai: () => Promise<Response> = async () => response({ description: 'Improved draft' }),
  onRefreshSession = refreshSession,
) {
  const fetchMock = vi.fn(async (url: RequestInfo | URL) => {
    if (String(url) === aiUrl) return ai()
    if (String(url) === '/api/v1/reporter/incidents') return response({ id: 'created' }, 201)
    return response({ incidents: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
  })
  vi.stubGlobal('fetch', fetchMock)
  const view = render(<ReporterDashboard key={session.loginName} onRefreshSession={onRefreshSession} />)
  return { fetchMock, ...view }
}
function fill() {
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Outage' } })
  fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'IT' } })
  fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Original draft' } })
}
function improve() { fireEvent.click(screen.getByRole('button', { name: 'Improve description' })) }
beforeEach(() => { document.cookie = 'XSRF-TOKEN=token; Path=/' })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0' })

describe('Reporter AI description review', () => {
  it('requires all draft fields, sends only three fields with CSRF, and applies only after review', async () => {
    const { fetchMock } = setup()
    expect(screen.getByRole('button', { name: 'Improve description' })).toBeDisabled()
    fill(); fireEvent.click(screen.getByLabelText('Submit anonymously')); improve()
    expect(await screen.findByText('Improved draft')).toBeInTheDocument()
    expect(screen.getByLabelText('Description')).toHaveValue('Original draft')
    const call = fetchMock.mock.calls.find(([url]) => String(url) === aiUrl) as unknown as [string, RequestInit]
    expect(call[1]).toMatchObject({ method: 'POST', credentials: 'same-origin', signal: expect.any(AbortSignal) })
    expect(new Headers(call[1].headers).get('X-XSRF-TOKEN')).toBe('token')
    expect(JSON.parse(String(call[1].body))).toEqual({ title: 'Outage', category: 'IT', description: 'Original draft' })
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/v1/reporter/incidents')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Apply suggestion' }))
    expect(screen.getByLabelText('Description')).toHaveValue('Improved draft')
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/v1/reporter/incidents')).toBe(false)
  })
  it('renders malicious HTML, Markdown and URLs literally and discards without changing the draft', async () => {
    const malicious = '<img src=x onerror=alert(1)> [click](https://evil.test) <script>alert(1)</script>'
    const { container, fetchMock } = setup(async () => response({ description: malicious }))
    fill(); improve()
    expect(await screen.findByText(malicious)).toBeInTheDocument()
    expect(container.querySelector('img, script, a')).toBeNull()
    expect(fetchMock.mock.calls).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }))
    expect(screen.getByLabelText('Description')).toHaveValue('Original draft')
    expect(screen.queryByText(malicious)).not.toBeInTheDocument()
  })
  it.each(['cancel', 'description', 'title', 'category', 'anonymous', 'submit', 'unmount'])('ignores late responses after %s', async (change) => {
    let finish!: (value: Response) => void
    const { unmount, fetchMock } = setup(() => new Promise((resolve) => { finish = resolve }))
    fill(); improve()
    const call = fetchMock.mock.calls.find(([url]) => String(url) === aiUrl) as unknown as [string, RequestInit]
    if (change === 'cancel') fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    if (change === 'description') fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Newer text' } })
    if (change === 'title') fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'New title' } })
    if (change === 'category') fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'FACILITIES' } })
    if (change === 'anonymous') fireEvent.click(screen.getByLabelText('Submit anonymously'))
    if (change === 'submit') fireEvent.click(screen.getByRole('button', { name: 'Submit incident' }))
    if (change === 'unmount') unmount()
    expect(call[1].signal?.aborted).toBe(true)
    await act(async () => { finish(response({ description: 'Late answer' })) })
    expect(screen.queryByText('Late answer')).not.toBeInTheDocument()
    if (change === 'description') expect(screen.getByLabelText('Description')).toHaveValue('Newer text')
  })
  it.each(['AI_RATE_LIMITED', 'AI_TIMEOUT', 'AI_UNAVAILABLE', 'AI_BUDGET_EXHAUSTED', 'SECURITY_SESSION_EXPIRED'])('handles %s safely and leaves submission available', async (code) => {
    const { fetchMock } = setup(async () => response({ code, message: 'secret provider content' }, 403))
    fill(); improve()
    expect(await screen.findByRole('alert')).not.toHaveTextContent('secret provider content')
    expect(screen.getByRole('button', { name: 'Submit incident' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Submit incident' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/v1/reporter/incidents')).toBe(true))
  })
  it.each([{}, { description: '' }, { description: 'x'.repeat(10001) }, { description: '中'.repeat(6000) }])('rejects malformed or oversized successful output', async (body) => {
    setup(async () => response(body)); fill(); improve()
    expect(await screen.findByRole('alert')).toHaveTextContent('valid suggestion')
    expect(screen.queryByRole('button', { name: 'Apply suggestion' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Description')).toHaveValue('Original draft')
  })
  it('keeps the newer request active when an older cancelled request completes', async () => {
    const finish: Array<(value: Response) => void> = []
    setup(() => new Promise((resolve) => { finish.push(resolve) }))
    fill(); improve()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    improve()
    await act(async () => { finish[0](response({ description: 'Old suggestion' })) })
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
    expect(screen.queryByText('Old suggestion')).not.toBeInTheDocument()
    await act(async () => { finish[1](response({ description: 'New suggestion' })) })
    expect(await screen.findByText('New suggestion')).toBeInTheDocument()
  })
  it('uses a generic message on network failure without showing error content', async () => {
    setup(async () => { throw new Error('private network details') })
    fill(); improve()
    expect(await screen.findByRole('alert')).toHaveTextContent('unavailable')
    expect(screen.getByRole('alert')).not.toHaveTextContent('private network details')
    expect(screen.getByRole('button', { name: 'Submit incident' })).toBeEnabled()
  })
  it('refreshes stale CSRF once and sends the new cookie on the retry', async () => {
    let calls = 0
    const refresh = vi.fn(async () => {
      document.cookie = 'XSRF-TOKEN=fresh-token; Path=/'
      return session
    })
    const { fetchMock } = setup(async () => ++calls === 1
      ? response({ code: 'SECURITY_SESSION_EXPIRED' }, 403)
      : response({ description: 'Refreshed suggestion' }), refresh)
    fill(); improve()
    expect(await screen.findByText('Refreshed suggestion')).toBeInTheDocument()
    expect(refresh).toHaveBeenCalledTimes(1)
    const callsWithOptions = fetchMock.mock.calls as unknown as Array<[RequestInfo | URL, RequestInit]>
    const attempts = callsWithOptions.filter(([url]) => String(url) === aiUrl)
    expect(attempts).toHaveLength(2)
    expect(new Headers(attempts[0][1].headers).get('X-XSRF-TOKEN')).toBe('token')
    expect(new Headers(attempts[1][1].headers).get('X-XSRF-TOKEN')).toBe('fresh-token')
    expect(attempts[1][1].body).toBe(attempts[0][1].body)
  })
  it('cancels during CSRF refresh without retrying or accepting a late suggestion', async () => {
    let finish!: (value: typeof session) => void
    const refresh = vi.fn(() => new Promise<typeof session>((resolve) => { finish = resolve }))
    const { fetchMock } = setup(async () => response({ code: 'SECURITY_SESSION_EXPIRED' }, 403), refresh)
    fill(); improve()
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await act(async () => { finish(session) })
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === aiUrl)).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Apply suggestion' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Description')).toHaveValue('Original draft')
  })
  it('resets the draft and cancels requests when the signed-in identity changes', async () => {
    const { rerender } = setup(); fill(); improve()
    await screen.findByText('Improved draft')
    rerender(<ReporterDashboard key="other" onRefreshSession={async () => ({ ...session, loginName: 'other' })} />)
    expect(screen.getByLabelText('Description')).toHaveValue('')
    expect(screen.queryByText('Improved draft')).not.toBeInTheDocument()
  })
})
