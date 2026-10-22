import { useEffect, useRef, useState } from 'react'
import { ApiError, changeOwnPassword, type SessionRefresher } from '../api'

export function PasswordChangeDialog({ open, required, onCancel, onChanged, onRequiredDismiss, refreshSession }: {
  open: boolean
  required: boolean
  onCancel: () => void
  onChanged: () => Promise<unknown>
  onRequiredDismiss: () => void
  refreshSession: SessionRefresher
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [currentError, setCurrentError] = useState('')
  const [newError, setNewError] = useState('')
  const [confirmationError, setConfirmationError] = useState('')
  const [formError, setFormError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (open && !element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    } else if (!open && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [open])

  useEffect(() => () => {
    setCurrentPassword('')
    setNewPassword('')
    setConfirmation('')
  }, [])

  function clearSecrets() {
    setCurrentPassword('')
    setNewPassword('')
    setConfirmation('')
  }

  function cancel() {
    clearSecrets()
    setCurrentError('')
    setNewError('')
    setConfirmationError('')
    setFormError('')
    onCancel()
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setCurrentError('')
    setNewError('')
    setConfirmationError('')
    setFormError('')
    if (newPassword !== confirmation) {
      setConfirmationError('New passwords do not match.')
      return
    }
    setSubmitting(true)
    try {
      await changeOwnPassword(currentPassword, newPassword, refreshSession)
      clearSecrets()
      await onChanged()
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && error.code === 'AUTHENTICATION_FAILED') {
        setCurrentError('Current password is incorrect.')
      } else if (error instanceof ApiError && error.status === 400 && error.code === 'VALIDATION_FAILED'
        && error.fieldErrors?.newPassword) {
        setNewError(error.fieldErrors.newPassword)
      } else {
        setFormError('The password could not be updated. Try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return <dialog
    aria-describedby="password-change-description"
    aria-labelledby="password-change-title"
    className="confirmation-dialog password-dialog"
    ref={dialog}
    onCancel={(event) => {
      event.preventDefault()
      if (!required && !submitting) cancel()
    }}
    onClose={() => {
      if (required && open) onRequiredDismiss()
    }}
  >
    {open && <div className="confirmation-dialog__content">
      <p className="eyebrow">Account security</p>
      <h2 id="password-change-title">
        {required ? 'Replace your temporary password to continue' : 'Choose a new password'}
      </h2>
      <p id="password-change-description">
        Enter your current password, then choose a new password with at least 12 characters.
      </p>
      <form className="password-form" onSubmit={(event) => void submit(event)}>
        <label htmlFor="current-password">Current password</label>
        <input id="current-password" type="password" autoComplete="current-password" required
          disabled={submitting} aria-invalid={Boolean(currentError)} aria-describedby={currentError ? 'current-password-error' : undefined}
          value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} />
        {currentError && <span className="field-error" id="current-password-error">{currentError}</span>}

        <label htmlFor="new-password">New password</label>
        <input id="new-password" type="password" autoComplete="new-password" required minLength={12} maxLength={128}
          disabled={submitting} aria-invalid={Boolean(newError)} aria-describedby="new-password-help new-password-error"
          value={newPassword} onChange={(event) => setNewPassword(event.target.value)} />
        <span className="help-text" id="new-password-help">Use at least 12 characters.</span>
        {newError && <span className="field-error" id="new-password-error">{newError}</span>}

        <label htmlFor="confirm-new-password">Confirm new password</label>
        <input id="confirm-new-password" type="password" autoComplete="new-password" required minLength={12} maxLength={128}
          disabled={submitting} aria-invalid={Boolean(confirmationError)} aria-describedby={confirmationError ? 'confirm-new-password-error' : undefined}
          value={confirmation} onChange={(event) => setConfirmation(event.target.value)} />
        {confirmationError && <span className="field-error" id="confirm-new-password-error">{confirmationError}</span>}

        {formError && <p className="form-error" role="alert">{formError}</p>}
        <div className="confirmation-dialog__actions">
          {!required && <button className="ghost-button" type="button" disabled={submitting} onClick={cancel}>Cancel</button>}
          <button className="primary-button" type="submit" disabled={submitting}>
            {submitting ? 'Updating…' : 'Update password'}
          </button>
        </div>
      </form>
    </div>}
  </dialog>
}
