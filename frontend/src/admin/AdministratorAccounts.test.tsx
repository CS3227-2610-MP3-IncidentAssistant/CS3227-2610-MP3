import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AdministratorAccounts } from './AdministratorAccounts'

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=csrf-value; path=/'
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

describe('Administrator account directory', () => {
  it('requires an explicit destructive confirmation and never offers self-deletion', async () => {
    const accounts = [
      { id: 'admin-id', loginName: 'operations-admin', role: 'ADMINISTRATOR', status: 'ENABLED', categories: [] },
      { id: 'reporter-id', loginName: 'case-reporter', role: 'REPORTER', status: 'ENABLED', categories: [] },
    ]
    let deleted = false
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/v1/admin/accounts?')) {
        return response({
          accounts: deleted ? accounts.slice(0, 1) : accounts,
          page: 0,
          size: 25,
          totalElements: deleted ? 1 : 2,
          totalPages: 1,
        })
      }
      if (url === '/api/v1/admin/promotion-requests?page=0&size=25') {
        return response({ requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      if (url === '/api/v1/admin/accounts/reporter-id/tombstone' && init?.method === 'POST') {
        deleted = true
        return new Response(null, { status: 204 })
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorAccounts
      currentLoginName="operations-admin"
      onRefreshSession={async () => ({ authenticated: true, loginName: 'operations-admin', role: 'ADMINISTRATOR' })}
    />)

    const ownRow = (await screen.findByText('operations-admin')).closest('tr')!
    expect(within(ownRow).getByText('Current account')).toBeInTheDocument()
    expect(within(ownRow).queryByRole('button', { name: /Delete account/ })).not.toBeInTheDocument()

    const reporterRow = screen.getByText('case-reporter').closest('tr')!
    const deleteButton = within(reporterRow).getByRole('button', { name: 'Delete account case-reporter' })
    expect(deleteButton).toHaveTextContent('Delete')
    expect(deleteButton).toHaveClass('danger-outline-button')
    fireEvent.click(deleteButton)
    const confirmation = screen.getByRole('dialog', { name: 'This action cannot be undone' })
    expect(within(confirmation).getByText(/incident and audit history is retained/i)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)

    fireEvent.click(within(confirmation).getByRole('button', { name: 'Delete account' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/accounts/reporter-id/tombstone',
      expect.objectContaining({ method: 'POST', headers: expect.any(Headers) }),
    ))
    await waitFor(() => expect(screen.queryByText('case-reporter')).not.toBeInTheDocument())
    expect(await screen.findByRole('status')).toHaveTextContent('Historical records were retained')
  })

  it('cancels without sending a mutation', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.startsWith('/api/v1/admin/accounts?')) return response({
        accounts: [{ id: 'target-id', loginName: 'target', role: 'RESPONDER', status: 'ENABLED', categories: ['IT'] }],
        page: 0, size: 25, totalElements: 1, totalPages: 1,
      })
      if (url.startsWith('/api/v1/admin/promotion-requests')) {
        return response({ requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorAccounts currentLoginName="admin"
      onRefreshSession={async () => ({ authenticated: true, role: 'ADMINISTRATOR' })} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Delete account target' }))
    const confirmation = screen.getByRole('dialog', { name: 'This action cannot be undone' })
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete account target' }))
    const reopened = screen.getByRole('dialog', { name: 'This action cannot be undone' })
    fireEvent(reopened, new Event('cancel', { cancelable: true }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('keeps the popup open when deletion is rejected', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/v1/admin/accounts?')) return response({
        accounts: [{ id: 'target-id', loginName: 'target', role: 'REPORTER', status: 'ENABLED', categories: [] }],
        page: 0, size: 25, totalElements: 1, totalPages: 1,
      })
      if (url.startsWith('/api/v1/admin/promotion-requests')) {
        return response({ requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      if (url === '/api/v1/admin/accounts/target-id/tombstone' && init?.method === 'POST') {
        return response({ code: 'CONFLICT', message: 'Conflict' }, 409)
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorAccounts currentLoginName="admin"
      onRefreshSession={async () => ({ authenticated: true, role: 'ADMINISTRATOR' })} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Delete account target' }))
    const confirmation = screen.getByRole('dialog', { name: 'This action cannot be undone' })
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Delete account' }))

    expect(await within(confirmation).findByRole('alert')).toHaveTextContent('cannot be deleted')
    expect(screen.getByRole('dialog', { name: 'This action cannot be undone' })).toBeInTheDocument()
    expect(screen.getByText('target')).toBeInTheDocument()
  })
})

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
