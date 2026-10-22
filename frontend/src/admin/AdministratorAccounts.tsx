import { useEffect, useRef, useState } from 'react'
import {
  ApiError,
  loadAdministratorAccounts,
  resetAdministratorAccountPassword,
  replaceResponderCategories,
  tombstoneAdministratorAccount,
  type AdministratorAccount,
  type IncidentCategory,
  type SessionRefresher,
  type TemporaryPasswordResponse,
} from '../api'
import { Pagination } from '../shared/Pagination'
import { PromotionRequests } from './PromotionRequests'

const categories: IncidentCategory[] = ['IT', 'HUMAN_RELATIONS', 'FACILITIES']
const categoryNames: Record<IncidentCategory, string> = {
  IT: 'IT',
  HUMAN_RELATIONS: 'Human relations',
  FACILITIES: 'Facilities',
}

export function AdministratorAccounts({ currentLoginName, onRefreshSession }: {
  currentLoginName: string
  onRefreshSession: SessionRefresher
}) {
  return <main id="main-content" className="content">
    <header className="page-header">
      <div>
        <p className="eyebrow">Accounts</p>
        <h1>Account administration</h1>
        <p>Review access requests and manage active company accounts.</p>
      </div>
    </header>
    <AccountDirectory currentLoginName={currentLoginName} onRefreshSession={onRefreshSession} />
    <PromotionRequests onRefreshSession={onRefreshSession} />
  </main>
}

function AccountDirectory({ currentLoginName, onRefreshSession }: {
  currentLoginName: string
  onRefreshSession: SessionRefresher
}) {
  const [accounts, setAccounts] = useState<AdministratorAccount[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [actionError, setActionError] = useState('')
  const [confirmingAccount, setConfirmingAccount] = useState<AdministratorAccount | null>(null)
  const [resettingAccount, setResettingAccount] = useState<AdministratorAccount | null>(null)
  const [revealedPassword, setRevealedPassword] = useState<{
    account: AdministratorAccount
    response: TemporaryPasswordResponse
  } | null>(null)
  const [configuringAccount, setConfiguringAccount] = useState<AdministratorAccount | null>(null)
  const [submittingId, setSubmittingId] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    loadAdministratorAccounts(page, controller.signal)
      .then((result) => {
        setAccounts(result.accounts)
        setTotalPages(Math.max(1, result.totalPages))
        setLoadError(false)
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) setLoadError(true)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [page, revision])

  function reload() {
    setLoading(true)
    setLoadError(false)
    setRevision((current) => current + 1)
  }

  async function confirmTombstone(account: AdministratorAccount) {
    setSubmittingId(account.id)
    setFeedback('')
    setActionError('')
    try {
      await tombstoneAdministratorAccount(account.id, onRefreshSession)
      setConfirmingAccount(null)
      setFeedback(`${account.loginName} was deleted. Historical records were retained.`)
      reload()
    } catch (error) {
      setActionError(error instanceof ApiError && error.status === 409
        ? 'The account changed or cannot be deleted. Refresh before trying again.'
        : error instanceof Error ? error.message : 'The account could not be deleted.')
    } finally {
      setSubmittingId(null)
    }
  }

  async function saveCategories(account: AdministratorAccount, selectedCategories: IncidentCategory[]) {
    setSubmittingId(account.id)
    setFeedback('')
    setActionError('')
    try {
      await replaceResponderCategories(account.id, account.version, selectedCategories, onRefreshSession)
      setConfiguringAccount(null)
      setFeedback(`${account.loginName}'s responder categories were updated.`)
      reload()
    } catch (error) {
      setActionError(error instanceof ApiError && error.status === 409
        ? 'The account changed. Refresh the directory before trying again.'
        : error instanceof Error ? error.message : 'Responder categories could not be updated.')
    } finally {
      setSubmittingId(null)
    }
  }

  async function confirmPasswordReset(account: AdministratorAccount) {
    setSubmittingId(account.id)
    setFeedback('')
    setActionError('')
    try {
      const response = await resetAdministratorAccountPassword(account.id, onRefreshSession)
      setResettingAccount(null)
      setRevealedPassword({ account, response })
    } catch (error) {
      setActionError(passwordResetError(error))
    } finally {
      setSubmittingId(null)
    }
  }

  function closePasswordReveal() {
    setRevealedPassword(null)
  }

  return <section className="incident-panel account-directory" aria-labelledby="account-directory-heading">
    <div className="section-heading">
      <div>
        <p className="eyebrow">Directory</p>
        <h2 id="account-directory-heading">Active accounts</h2>
      </div>
      <button className="secondary-button" type="button" onClick={reload}>Refresh</button>
    </div>
    {feedback && <p className="account-feedback" role="status">{feedback}</p>}
    {loading && <p className="panel-state" role="status">Loading accounts…</p>}
    {!loading && loadError && <div className="panel-state" role="alert">
      Accounts are temporarily unavailable. Refresh to try again.
    </div>}
    {!loading && !loadError && accounts.length === 0 && <div className="empty-state">
      <h3>No active accounts</h3>
    </div>}
    {!loading && !loadError && accounts.length > 0 && <div className="table-region" role="region" aria-label="Active accounts" tabIndex={0}>
      <table>
        <thead><tr><th scope="col">Login name</th><th scope="col">Role</th><th scope="col">Status</th><th scope="col">Responder categories</th><th scope="col">Actions</th></tr></thead>
        <tbody>{accounts.map((account) => <tr key={account.id}>
          <th scope="row">{account.loginName}</th>
          <td>{displayEnum(account.role)}</td>
          <td>{displayEnum(account.status)}</td>
          <td>{account.categories.length > 0 ? account.categories.map(displayEnum).join(', ') : 'None'}</td>
          <td><AccountActions
            account={account}
            currentLoginName={currentLoginName}
            onConfigure={() => {
              setFeedback('')
              setActionError('')
              setConfiguringAccount(account)
            }}
            onDelete={() => {
              setFeedback('')
              setActionError('')
              setConfirmingAccount(account)
            }}
            onResetPassword={() => {
              setFeedback('')
              setActionError('')
              setResettingAccount(account)
            }}
          /></td>
        </tr>)}</tbody>
      </table>
    </div>}
    {!loadError && <Pagination page={page} totalPages={totalPages} ariaLabel="Account pages"
      disabled={loading} onPageChange={setPage} />}
    <DeleteAccountDialog
      account={confirmingAccount}
      error={actionError}
      submitting={submittingId === confirmingAccount?.id}
      onCancel={() => {
        setActionError('')
        setConfirmingAccount(null)
      }}
      onConfirm={() => {
        if (confirmingAccount) void confirmTombstone(confirmingAccount)
      }}
    />
    <ResetPasswordDialog
      account={resettingAccount}
      error={actionError}
      submitting={submittingId === resettingAccount?.id}
      onCancel={() => {
        setActionError('')
        setResettingAccount(null)
      }}
      onConfirm={() => {
        if (resettingAccount) void confirmPasswordReset(resettingAccount)
      }}
    />
    <TemporaryPasswordRevealDialog revealed={revealedPassword} onClose={closePasswordReveal} />
    <ConfigureCategoriesDialog
      account={configuringAccount}
      error={actionError}
      submitting={submittingId === configuringAccount?.id}
      onCancel={() => {
        setActionError('')
        setConfiguringAccount(null)
      }}
      onConfirm={(selectedCategories) => {
        if (configuringAccount) void saveCategories(configuringAccount, selectedCategories)
      }}
    />
  </section>
}

function AccountActions({ account, currentLoginName, onConfigure, onDelete, onResetPassword }: {
  account: AdministratorAccount
  currentLoginName: string
  onConfigure: () => void
  onDelete: () => void
  onResetPassword: () => void
}) {
  if (account.loginName === currentLoginName) return <span>Current account</span>
  if (account.status !== 'ENABLED') return <span>Unavailable</span>
  return <div className="account-actions">
    {account.role === 'RESPONDER' && <button
      aria-label={`Configure categories for ${account.loginName}`}
      className="secondary-button"
      type="button"
      onClick={onConfigure}
    >Configure categories</button>}
    <button
      aria-label={`Reset password for ${account.loginName}`}
      className="secondary-button"
      type="button"
      onClick={onResetPassword}
    >Reset password</button>
    <button
      aria-label={`Delete account ${account.loginName}`}
      className="danger-outline-button"
      type="button"
      onClick={onDelete}
    >Delete</button>
  </div>
}

function ResetPasswordDialog({ account, error, submitting, onCancel, onConfirm }: {
  account: AdministratorAccount | null
  error: string
  submitting: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (account && !element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    } else if (!account && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [account])

  return <dialog
    aria-describedby="temporary-password-description"
    aria-labelledby="temporary-password-title"
    className="confirmation-dialog"
    ref={dialog}
    onCancel={(event) => {
      event.preventDefault()
      if (!submitting) onCancel()
    }}
  >
    {account && <div className="confirmation-dialog__content">
      <p className="eyebrow">Reset password</p>
      <h2 id="temporary-password-title">The temporary password expires in 24 hours.</h2>
      <p id="temporary-password-description">
        Generate a one-time temporary password for {account.loginName}? Any earlier password will stop working immediately.
      </p>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="confirmation-dialog__actions">
        <button className="ghost-button" type="button" disabled={submitting} onClick={onCancel}>Cancel</button>
        <button className="primary-button" type="button" disabled={submitting} onClick={onConfirm}>
          {submitting ? 'Generating…' : 'Generate temporary password'}
        </button>
      </div>
    </div>}
  </dialog>
}

function TemporaryPasswordRevealDialog({ revealed, onClose }: {
  revealed: { account: AdministratorAccount; response: TemporaryPasswordResponse } | null
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const secretField = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (revealed && !element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    } else if (!revealed && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [revealed])

  function clearAndClose() {
    if (secretField.current) secretField.current.value = ''
    onClose()
  }

  useEffect(() => () => {
    if (secretField.current) secretField.current.value = ''
  }, [])

  return <dialog
    aria-describedby="temporary-password-reveal-description"
    aria-labelledby="temporary-password-reveal-title"
    className="confirmation-dialog"
    ref={dialog}
    onCancel={(event) => {
      event.preventDefault()
      clearAndClose()
    }}
  >
    {revealed && <div className="confirmation-dialog__content">
      <p className="eyebrow">Temporary password</p>
      <h2 id="temporary-password-reveal-title">Copy this password now — it will not be shown again</h2>
      <p id="temporary-password-reveal-description">
        Deliver it securely to {revealed.account.loginName}. They must replace it immediately after signing in.
      </p>
      <label className="visually-hidden" htmlFor="temporary-password-value">Temporary password</label>
      <input id="temporary-password-value" className="temporary-password-field" readOnly
        ref={secretField} value={revealed.response.temporaryPassword} />
      <div className="confirmation-dialog__actions">
        <button className="primary-button" type="button" onClick={clearAndClose}>Done</button>
      </div>
    </div>}
  </dialog>
}

function ConfigureCategoriesDialog({ account, error, submitting, onCancel, onConfirm }: {
  account: AdministratorAccount | null
  error: string
  submitting: boolean
  onCancel: () => void
  onConfirm: (categories: IncidentCategory[]) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [selectedCategories, setSelectedCategories] = useState<IncidentCategory[]>([])

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (account && !element.open) {
      setSelectedCategories(account.categories)
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    } else if (!account && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [account])

  function toggleCategory(category: IncidentCategory) {
    setSelectedCategories((current) => current.includes(category)
      ? current.filter((selected) => selected !== category)
      : [...current, category])
  }

  const unchanged = sameCategorySet(selectedCategories, account?.categories ?? [])

  return <dialog
    aria-describedby="configure-categories-description"
    aria-labelledby="configure-categories-title"
    className="confirmation-dialog"
    ref={dialog}
    onCancel={(event) => {
      event.preventDefault()
      if (!submitting) onCancel()
    }}
  >
    {account && <div className="confirmation-dialog__content">
      <p className="eyebrow">Responder access</p>
      <h2 id="configure-categories-title">Configure categories</h2>
      <p id="configure-categories-description">
        Choose the incident categories {account.loginName} can access as a responder.
      </p>
      <fieldset className="category-choice">
        <legend>Incident categories</legend>
        {categories.map((category) => <label key={category}>
          <input
            type="checkbox"
            checked={selectedCategories.includes(category)}
            disabled={submitting}
            onChange={() => toggleCategory(category)}
          />
          {categoryNames[category]}
        </label>)}
      </fieldset>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="confirmation-dialog__actions">
        <button className="ghost-button" type="button" disabled={submitting} onClick={onCancel}>Cancel</button>
        <button className="primary-button" type="button" disabled={submitting || unchanged}
          onClick={() => onConfirm(selectedCategories)}>
          {submitting ? 'Saving…' : unchanged ? 'No changes' : 'Save categories'}
        </button>
      </div>
    </div>}
  </dialog>
}

function DeleteAccountDialog({ account, error, submitting, onCancel, onConfirm }: {
  account: AdministratorAccount | null
  error: string
  submitting: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (account && !element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    } else if (!account && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [account])

  return <dialog
    aria-describedby="delete-account-description"
    aria-labelledby="delete-account-title"
    className="confirmation-dialog"
    ref={dialog}
    onCancel={(event) => {
      event.preventDefault()
      if (!submitting) onCancel()
    }}
  >
    {account && <div className="confirmation-dialog__content">
      <p className="eyebrow">Delete account</p>
      <h2 id="delete-account-title">This action cannot be undone</h2>
      <p id="delete-account-description">
        Delete {account.loginName}? Their login will be disabled while historical incident references are retained.
      </p>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="confirmation-dialog__actions">
        <button className="ghost-button" type="button" disabled={submitting} onClick={onCancel}>Cancel</button>
        <button className="danger-button" type="button" disabled={submitting} onClick={onConfirm}>
          {submitting ? 'Deleting…' : 'Delete account'}
        </button>
      </div>
    </div>}
  </dialog>
}

function displayEnum(value: string) {
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./, (letter) => letter.toUpperCase())
}

function sameCategorySet(left: IncidentCategory[], right: IncidentCategory[]) {
  return left.length === right.length && left.every((category) => right.includes(category))
}

function passwordResetError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 404 && error.code === 'UNAVAILABLE') {
      return 'The account is unavailable. Refresh the directory before trying again.'
    }
    if (error.status === 409 && (error.code === 'INVALID_STATE' || error.code === 'CONFLICT')) {
      return 'The account changed or cannot be reset. Refresh before trying again.'
    }
    if (error.status === 503 && error.code === 'PERSISTENCE_FAILURE') {
      return 'The temporary password could not be generated. Try again.'
    }
    return error.message
  }
  return 'The temporary password could not be generated. Try again.'
}
