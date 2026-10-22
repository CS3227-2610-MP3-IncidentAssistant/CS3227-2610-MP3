import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PasswordChangeDialog } from './PasswordChangeDialog'

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=csrf-value; path=/'
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

describe('Password change dialog', () => {
  it('validates confirmation in the browser and sends only current and new passwords', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    const onChanged = vi.fn(async () => undefined)
    render(<PasswordChangeDialog open required={false} onCancel={vi.fn()} onChanged={onChanged}
      onRequiredDismiss={vi.fn()} refreshSession={async () => ({ authenticated: true })} />)

    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'current-secret' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'twelve-characters' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'different-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Update password' }))
    expect(await screen.findByText('New passwords do not match.')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'twelve-characters' } })
    fireEvent.click(screen.getByRole('button', { name: 'Update password' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/password', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ currentPassword: 'current-secret', newPassword: 'twelve-characters' }),
    })))
    expect(onChanged).toHaveBeenCalledOnce()
  })

  it('maps current-password, new-password, and persistence errors to their affordances', async () => {
    const responses = [
      response({ code: 'AUTHENTICATION_FAILED', message: 'Sign-in failed' }, 401),
      response({ code: 'VALIDATION_FAILED', fieldErrors: { newPassword: 'Use at least 12 characters.' } }, 400),
      response({ code: 'PERSISTENCE_FAILURE', message: 'Unavailable' }, 503),
    ]
    vi.stubGlobal('fetch', vi.fn(async () => responses.shift()!))
    render(<PasswordChangeDialog open required={false} onCancel={vi.fn()} onChanged={vi.fn(async () => undefined)}
      onRequiredDismiss={vi.fn()} refreshSession={async () => ({ authenticated: true })} />)

    const submit = () => {
      fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'current-secret' } })
      fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'twelve-characters' } })
      fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'twelve-characters' } })
      fireEvent.click(screen.getByRole('button', { name: 'Update password' }))
    }
    submit()
    expect(await screen.findByText('Current password is incorrect.')).toBeInTheDocument()
    submit()
    expect(await screen.findByText('Use at least 12 characters.')).toBeInTheDocument()
    submit()
    expect(await screen.findByRole('alert')).toHaveTextContent('The password could not be updated. Try again.')
  })

  it('cannot be cancelled in required mode', () => {
    const onCancel = vi.fn()
    const onRequiredDismiss = vi.fn()
    render(<PasswordChangeDialog open required onCancel={onCancel} onChanged={vi.fn(async () => undefined)}
      onRequiredDismiss={onRequiredDismiss} refreshSession={async () => ({ authenticated: true })} />)
    const dialog = screen.getByRole('dialog', { name: 'Replace your temporary password to continue' })

    expect(within(dialog).queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
    fireEvent(dialog, new Event('cancel', { cancelable: true }))
    expect(onCancel).not.toHaveBeenCalled()
    expect(dialog).toBeInTheDocument()

    fireEvent(dialog, new Event('close'))
    expect(onRequiredDismiss).toHaveBeenCalledOnce()
  })
})

function response(body: unknown, status: number) {
  return { ok: false, status, json: async () => body } as Response
}
