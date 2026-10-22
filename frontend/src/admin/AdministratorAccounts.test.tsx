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
      { id: 'admin-id', loginName: 'operations-admin', role: 'ADMINISTRATOR', status: 'ENABLED', version: 0, categories: [] },
      { id: 'reporter-id', loginName: 'case-reporter', role: 'REPORTER', status: 'ENABLED', version: 0, categories: [] },
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
    expect(within(confirmation).getByText(/historical incident references are retained/i)).toBeInTheDocument()
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
        accounts: [{ id: 'target-id', loginName: 'target', role: 'RESPONDER', status: 'ENABLED', version: 0, categories: ['IT'] }],
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

  it('configures categories for enabled responders from a preselected popup', async () => {
    let responderCategories = ['IT']
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/v1/admin/accounts?')) return response({
        accounts: [
          { id: 'responder-id', loginName: 'triage-responder', role: 'RESPONDER', status: 'ENABLED', version: 4, categories: responderCategories },
          { id: 'reporter-id', loginName: 'case-reporter', role: 'REPORTER', status: 'ENABLED', version: 0, categories: [] },
        ],
        page: 0, size: 25, totalElements: 2, totalPages: 1,
      })
      if (url.startsWith('/api/v1/admin/promotion-requests')) {
        return response({ requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      if (url === '/api/v1/admin/accounts/responder-id/responder-categories' && init?.method === 'PUT') {
        responderCategories = JSON.parse(String(init.body)).categories
        return new Response(null, { status: 204 })
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorAccounts currentLoginName="admin"
      onRefreshSession={async () => ({ authenticated: true, role: 'ADMINISTRATOR' })} />)

    const responderRow = (await screen.findByText('triage-responder')).closest('tr')!
    const reporterRow = screen.getByText('case-reporter').closest('tr')!
    expect(within(reporterRow).queryByRole('button', { name: /Configure categories/ })).not.toBeInTheDocument()
    fireEvent.click(within(responderRow).getByRole('button', { name: 'Configure categories for triage-responder' }))

    const dialog = screen.getByRole('dialog', { name: 'Configure categories' })
    expect(within(dialog).getByRole('checkbox', { name: 'IT' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: 'Human relations' })).not.toBeChecked()
    expect(within(dialog).getByRole('button', { name: 'No changes' })).toBeDisabled()
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'IT' }))
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Facilities' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save categories' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/accounts/responder-id/responder-categories',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ expectedVersion: 4, categories: ['FACILITIES'] }),
        headers: expect.any(Headers),
      }),
    ))
    expect(screen.queryByRole('dialog', { name: 'Configure categories' })).not.toBeInTheDocument()
    expect(await screen.findByRole('status')).toHaveTextContent("triage-responder's responder categories were updated")
    await waitFor(() => expect(within(screen.getByText('triage-responder').closest('tr')!).getByText('Facilities')).toBeInTheDocument())
  })

  it('allows clearing all categories and keeps the popup open after a failed update', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/v1/admin/accounts?')) return response({
        accounts: [{ id: 'responder-id', loginName: 'responder', role: 'RESPONDER', status: 'ENABLED', version: 2, categories: ['IT'] }],
        page: 0, size: 25, totalElements: 1, totalPages: 1,
      })
      if (url.startsWith('/api/v1/admin/promotion-requests')) {
        return response({ requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      if (url.endsWith('/responder-categories') && init?.method === 'PUT') {
        return response({ code: 'ACCOUNT_UPDATE_UNAVAILABLE', message: 'Responder access could not be updated' }, 503)
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorAccounts currentLoginName="admin"
      onRefreshSession={async () => ({ authenticated: true, role: 'ADMINISTRATOR' })} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Configure categories for responder' }))
    const dialog = screen.getByRole('dialog', { name: 'Configure categories' })
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'IT' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save categories' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/accounts/responder-id/responder-categories',
      expect.objectContaining({ body: JSON.stringify({ expectedVersion: 2, categories: [] }) }),
    ))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('could not be updated')
    expect(screen.getByRole('dialog', { name: 'Configure categories' })).toBeInTheDocument()
  })

  it('keeps the popup open and explains how to recover from a stale account version', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/v1/admin/accounts?')) return response({
        accounts: [{
          id: 'responder-id', loginName: 'responder', role: 'RESPONDER', status: 'ENABLED',
          version: 7, categories: ['IT'],
        }],
        page: 0, size: 25, totalElements: 1, totalPages: 1,
      })
      if (url.startsWith('/api/v1/admin/promotion-requests')) {
        return response({ requests: [], page: 0, size: 25, totalElements: 0, totalPages: 1 })
      }
      if (url.endsWith('/responder-categories') && init?.method === 'PUT') {
        return response({ code: 'CONFLICT', message: 'The account changed. Refresh before trying again' }, 409)
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AdministratorAccounts currentLoginName="admin"
      onRefreshSession={async () => ({ authenticated: true, role: 'ADMINISTRATOR' })} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Configure categories for responder' }))
    const dialog = screen.getByRole('dialog', { name: 'Configure categories' })
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Facilities' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save categories' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/accounts/responder-id/responder-categories',
      expect.objectContaining({
        body: JSON.stringify({ expectedVersion: 7, categories: ['IT', 'FACILITIES'] }),
      }),
    ))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'The account changed. Refresh the directory before trying again.',
    )
    expect(dialog).toBeInTheDocument()
  })

  it('keeps the popup open when deletion is rejected', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/v1/admin/accounts?')) return response({
        accounts: [{ id: 'target-id', loginName: 'target', role: 'REPORTER', status: 'ENABLED', version: 0, categories: [] }],
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
