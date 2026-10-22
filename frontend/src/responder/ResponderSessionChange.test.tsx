import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from '../App'

vi.mock('../shared/PasswordChangeDialog', () => ({
  PasswordChangeDialog: ({ onChanged }: { onChanged: () => Promise<unknown> }) =>
    <button type="button" onClick={() => void onChanged()}>Refresh session</button>,
}))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it.each([
  ['a different account', 'second-responder'],
  ['the same account', 'first-responder'],
])('discards the previous responder queue when a refreshed session belongs to %s', async (_case, secondLogin) => {
  let sessionReads = 0
  let finishSecondQueue: ((response: Response) => void) | undefined
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/health') return Promise.resolve(response({ status: 'UP' }))
    if (url === '/api/v1/auth/session') {
      sessionReads++
      return Promise.resolve(response({
        authenticated: true,
        loginName: sessionReads === 1 ? 'first-responder' : secondLogin,
        role: 'RESPONDER',
        responderCategories: ['IT'],
      }))
    }
    if (url === '/api/v1/responder/incidents/query') {
      if (sessionReads === 1) return Promise.resolve(response(queue('First account incident')))
      return new Promise<Response>((resolve) => { finishSecondQueue = resolve })
    }
    throw new Error(`Unexpected request: ${url}`)
  }))

  render(<App />)
  expect(await screen.findByText('First account incident')).toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: 'Refresh session' }))
  await waitFor(() => expect(finishSecondQueue).toBeDefined())
  expect(screen.queryByText('First account incident')).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('Loading incidents')

  await act(async () => { finishSecondQueue?.(response(queue('Second account incident'))) })
  expect(await screen.findByText('Second account incident')).toBeInTheDocument()
})

function queue(title: string) {
  return {
    incidents: [{
      id: '80000000-0000-0000-0000-000000000001', title, category: 'IT', status: 'SUBMITTED',
      submittedAt: '2026-10-20T01:00:00Z', queueEnteredAt: '2026-10-20T01:00:00Z',
    }],
    page: 0, size: 25, totalElements: 1, totalPages: 1, categoriesConfigured: true,
  }
}

function response(body: unknown) {
  return { ok: true, status: 200, json: async () => body } as Response
}
