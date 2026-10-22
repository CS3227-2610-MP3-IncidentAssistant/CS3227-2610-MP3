import { useCallback, useEffect, useState } from 'react'
import { AdminDashboard } from './admin/AdminDashboard'
import { AdministratorIncidentDetail } from './admin/AdministratorIncidentDetail'
import { AdminShell } from './admin/AdminShell'
import { AdministratorAccounts } from './admin/AdministratorAccounts'
import { AdministratorSloConfiguration } from './admin/AdministratorSloConfiguration'
import { AdministratorStatistics } from './admin/AdministratorStatistics'
import {
  loadSession,
  logout,
  registerAccount,
  roleLogin,
  type SessionRefresher,
  type SessionState,
} from './api'
import { ReporterDashboard } from './reporter/ReporterDashboard'
import { ReporterShell } from './reporter/ReporterShell'
import { PromotionPanel } from './reporter/PromotionPanel'
import { ResponderPortal } from './responder/ResponderPortal'
import { PasswordChangeDialog } from './shared/PasswordChangeDialog'

type Portal = 'REPORTER' | 'RESPONDER' | 'ADMINISTRATOR'

export function App() {
  const sessionState = useSession()
  const [portal, setPortal] = useState<Portal | null>(null)
  const [passwordDialogOpen, setPasswordDialogOpen] = useState(false)

  if (sessionState.failed) {
    return <SessionFailure onRetry={sessionState.reload} />
  }
  if (!sessionState.session) {
    return (
      <main className="auth-layout">
        <p role="status">Checking your session…</p>
      </main>
    )
  }

  const session = sessionState.session
  async function signOut() {
    await logout(sessionState.refresh)
    sessionState.clear()
    setPortal(null)
  }
  if (session.authenticated && session.credentialTemporary) {
    return <PasswordChangeDialog
      open
      required
      onCancel={() => undefined}
      onChanged={sessionState.refresh}
      onRequiredDismiss={() => void signOut()}
      refreshSession={sessionState.refresh}
    />
  }
  const passwordDialog = <PasswordChangeDialog
    open={passwordDialogOpen}
    required={false}
    onCancel={() => setPasswordDialogOpen(false)}
    onChanged={async () => {
      await sessionState.refresh()
      setPasswordDialogOpen(false)
    }}
    onRequiredDismiss={() => undefined}
    refreshSession={sessionState.refresh}
  />
  if (session.authenticated && session.role === 'ADMINISTRATOR') {
    return (
      <><AdminShell
        accountsContent={<AdministratorAccounts
          currentLoginName={session.loginName ?? ''}
          onRefreshSession={sessionState.refresh}
        />}
        sloContent={<AdministratorSloConfiguration onRefreshSession={sessionState.refresh} />}
        statisticsContent={<AdministratorStatistics onRefreshSession={sessionState.refresh} />}
        loginName={session.loginName ?? 'Administrator'}
        onLogout={signOut}
        onUpdatePassword={() => setPasswordDialogOpen(true)}
      >
        <AdministratorIncidentWorkspace refreshSession={sessionState.refresh} />
      </AdminShell>{passwordDialog}</>
    )
  }
  if (session.authenticated && session.role === 'REPORTER') {
    return (
      <><ReporterShell
        promotionContent={<PromotionPanel onRefreshSession={sessionState.refresh} />}
        loginName={session.loginName ?? 'Reporter'}
        onLogout={signOut}
        onUpdatePassword={() => setPasswordDialogOpen(true)}
      >
        <ReporterDashboard key={session.loginName} onRefreshSession={sessionState.refresh} />
      </ReporterShell>{passwordDialog}</>
    )
  }
  if (session.authenticated && session.role === 'RESPONDER') {
    return (
      <><ResponderPortal
        key={sessionState.revision}
        categories={session.responderCategories ?? []}
        loginName={session.loginName ?? 'Responder'}
        onLogout={signOut}
        onUpdatePassword={() => setPasswordDialogOpen(true)}
      />{passwordDialog}</>
    )
  }
  if (!portal) {
    return <RoleEntry onSelect={setPortal} />
  }
  return (
    <PortalLogin
      portal={portal}
      onBack={() => setPortal(null)}
      onRefreshSession={sessionState.refresh}
    />
  )
}

function useSession() {
  const [session, setSession] = useState<SessionState | null>(null)
  const [revision, setRevision] = useState(0)
  const [failed, setFailed] = useState(false)
  const receive = useCallback((next: SessionState) => {
    setSession(next)
    setRevision((current) => current + 1)
    setFailed(false)
  }, [])
  const reload = useCallback(
    (signal?: AbortSignal) => loadSession(signal).then(receive).catch((error) => {
      if (!(error instanceof DOMException && error.name === 'AbortError')) {
        setFailed(true)
      }
    }),
    [receive],
  )
  const refresh = useCallback(async () => {
    const next = await loadSession()
    receive(next)
    return next
  }, [receive])

  useEffect(() => {
    const controller = new AbortController()
    void reload(controller.signal)
    return () => controller.abort()
  }, [reload])
  return {
    session,
    revision,
    failed,
    reload: () => reload(),
    refresh,
    clear: () => setSession((current) => current
      ? { ...current, authenticated: false, loginName: undefined, role: undefined }
      : current),
  }
}

function RoleEntry({ onSelect }: { onSelect: (portal: Portal) => void }) {
  return (
    <main className="auth-layout">
      <section className="auth-card" aria-labelledby="role-entry-heading">
        <p className="eyebrow">Incident Desk</p>
        <h1 id="role-entry-heading">Choose your portal</h1>
        <p>Sign in through the portal that matches your current account role.</p>
        <div className="portal-list">
          <button className="primary-button" type="button" onClick={() => onSelect('REPORTER')}>
            Reporter
          </button>
          <button className="secondary-button" type="button" onClick={() => onSelect('RESPONDER')}>
            Responder
          </button>
          <button className="secondary-button" type="button" onClick={() => onSelect('ADMINISTRATOR')}>
            Administrator
          </button>
        </div>
      </section>
    </main>
  )
}

function PortalLogin({ portal, onBack, onRefreshSession }: {
  portal: Portal
  onBack: () => void
  onRefreshSession: () => Promise<SessionState>
}) {
  const [registering, setRegistering] = useState(false)
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const portalLabels: Record<Portal, string> = {
    REPORTER: 'Reporter',
    RESPONDER: 'Responder',
    ADMINISTRATOR: 'Administrator',
  }
  const label = portalLabels[portal]

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      await roleLogin(loginName, password, portal, onRefreshSession)
      await onRefreshSession()
    } catch {
      setError('Sign-in failed. Check your credentials and portal, then try again.')
    } finally {
      setPassword('')
      setSubmitting(false)
    }
  }
  if (registering) {
    return (
      <ReporterRegistration
        onRefreshSession={onRefreshSession}
        onReturn={() => setRegistering(false)}
      />
    )
  }
  return (
    <main className="auth-layout">
      <section className="auth-card" aria-labelledby="login-heading">
        <p className="eyebrow">Incident Desk</p>
        <h1 id="login-heading">{label} sign in</h1>
        <p>Use an enabled {label.toLowerCase()} account.</p>
        {error && <div className="form-error" role="alert">{error}</div>}
        <form onSubmit={(event) => void submit(event)}>
          <label htmlFor="login-name">Login name</label>
          <input
            id="login-name"
            autoComplete="username"
            required
            maxLength={128}
            value={loginName}
            onChange={(event) => setLoginName(event.target.value)}
          />
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <button className="primary-button" type="submit" disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        {portal === 'REPORTER' && (
          <button
            className="ghost-button auth-switch"
            type="button"
            onClick={() => setRegistering(true)}
          >
            Create Reporter account
          </button>
        )}
        <button className="ghost-button auth-switch" type="button" onClick={onBack}>
          Choose another portal
        </button>
      </section>
    </main>
  )
}

function ReporterRegistration({ onRefreshSession, onReturn }: {
  onRefreshSession: () => Promise<SessionState>
  onReturn: () => void
}) {
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [feedback, setFeedback] = useState('')
  const [successful, setSuccessful] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFeedback('')
    setSuccessful(false)
    if (password !== confirmation) {
      setFeedback('Passwords do not match.')
      return
    }
    setSubmitting(true)
    try {
      await registerAccount(loginName, password, confirmation, onRefreshSession)
      setPassword('')
      setConfirmation('')
      setSuccessful(true)
      setFeedback('Reporter account created. Return to sign in when you are ready.')
    } catch (error) {
      setFeedback(error instanceof Error ? error.message : 'The account could not be created. Try again.')
    } finally {
      setSubmitting(false)
    }
  }
  return (
    <main className="auth-layout">
      <section className="auth-card" aria-labelledby="registration-heading">
        <p className="eyebrow">Reporter portal</p>
        <h1 id="registration-heading">Create Reporter account</h1>
        {feedback && (
          <div
            className={successful ? 'form-success' : 'form-error'}
            role={successful ? 'status' : 'alert'}
          >
            {feedback}
          </div>
        )}
        <form onSubmit={(event) => void submit(event)}>
          <label htmlFor="registration-login-name">Login name</label>
          <input
            id="registration-login-name"
            autoComplete="username"
            required
            maxLength={128}
            value={loginName}
            onChange={(event) => setLoginName(event.target.value)}
          />
          <label htmlFor="registration-password">Password</label>
          <input
            id="registration-password"
            type="password"
            autoComplete="new-password"
            required
            maxLength={128}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <label htmlFor="registration-confirmation">Confirm password</label>
          <input
            id="registration-confirmation"
            type="password"
            autoComplete="new-password"
            required
            maxLength={128}
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
          <button className="primary-button" type="submit" disabled={submitting}>
            {submitting ? 'Creating account…' : 'Create account'}
          </button>
        </form>
        <button className="ghost-button auth-switch" type="button" onClick={onReturn}>
          Return to sign in
        </button>
      </section>
    </main>
  )
}

function SessionFailure({ onRetry }: { onRetry: () => void }) {
  return (
    <main className="auth-layout">
      <section className="auth-card" role="alert">
        <h1>Session unavailable</h1>
        <p>The service could not check your session. No protected data was loaded.</p>
        <button className="primary-button" type="button" onClick={onRetry}>Try again</button>
      </section>
    </main>
  )
}

/**
 * Switches the Administrator workspace between the incident list and one
 * incident's detail. Selecting an incident carries only its identifier; the
 * detail view loads its own authorized representation from the server.
 */
function AdministratorIncidentWorkspace({ refreshSession }: { refreshSession: SessionRefresher }) {
  const [openIncidentId, setOpenIncidentId] = useState<string | null>(null)

  if (openIncidentId === null) {
    return <AdminDashboard onOpenIncident={setOpenIncidentId} />
  }
  return (
    <main id="main-content" className="content">
      <AdministratorIncidentDetail
        incidentId={openIncidentId}
        onBack={() => setOpenIncidentId(null)}
        refreshSession={refreshSession}
      />
    </main>
  )
}
