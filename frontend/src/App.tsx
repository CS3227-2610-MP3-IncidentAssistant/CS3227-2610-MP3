import { useCallback, useEffect, useState } from 'react'
import { AdminDashboard } from './admin/AdminDashboard'
import { AdminShell } from './admin/AdminShell'
import {
  isSecuritySessionExpired,
  loadSession,
  logout,
  registerAccount,
  roleLogin,
  type SessionState,
} from './api'
import { useServiceHealth } from './health/useServiceHealth'
import { ReporterDashboard } from './reporter/ReporterDashboard'
import { ReporterShell } from './reporter/ReporterShell'

type Portal = 'REPORTER' | 'ADMINISTRATOR'

export function App() {
  const serviceHealth = useServiceHealth()
  const sessionState = useSession()
  const [portal, setPortal] = useState<Portal | null>(null)

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
    await logout(session)
    sessionState.clear()
    setPortal(null)
  }
  if (session.authenticated && session.role === 'ADMINISTRATOR') {
    return (
      <AdminShell
        serviceHealth={serviceHealth}
        loginName={session.loginName ?? 'Administrator'}
        onLogout={signOut}
      >
        <AdminDashboard />
      </AdminShell>
    )
  }
  if (session.authenticated && session.role === 'REPORTER') {
    return (
      <ReporterShell
        serviceHealth={serviceHealth}
        loginName={session.loginName ?? 'Reporter'}
        onLogout={signOut}
      >
        <ReporterDashboard session={session} />
      </ReporterShell>
    )
  }
  if (!portal) {
    return <RoleEntry onSelect={setPortal} />
  }
  return (
    <PortalLogin
      portal={portal}
      session={session}
      onBack={() => setPortal(null)}
      onRefreshSession={sessionState.refresh}
    />
  )
}

function useSession() {
  const [session, setSession] = useState<SessionState | null>(null)
  const [failed, setFailed] = useState(false)
  const receive = useCallback((next: SessionState) => {
    setSession(next)
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
          <button
            className="secondary-button"
            type="button"
            disabled
            aria-describedby="responder-status"
          >
            Responder
          </button>
          <span id="responder-status" className="help-text">
            Responder web workflow is in development.
          </span>
          <button className="secondary-button" type="button" onClick={() => onSelect('ADMINISTRATOR')}>
            Administrator
          </button>
        </div>
      </section>
    </main>
  )
}

function PortalLogin({ portal, session, onBack, onRefreshSession }: {
  portal: Portal
  session: SessionState
  onBack: () => void
  onRefreshSession: () => Promise<SessionState>
}) {
  const [registering, setRegistering] = useState(false)
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const label = portal === 'REPORTER' ? 'Reporter' : 'Administrator'

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      await roleLogin(loginName, password, portal, session.csrfToken)
      setPassword('')
      await onRefreshSession()
    } catch {
      setPassword('')
      setError('Sign-in failed. Check your credentials and portal, then try again.')
    } finally {
      setSubmitting(false)
    }
  }
  if (registering) {
    return (
      <ReporterRegistration
        session={session}
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

function ReporterRegistration({ session, onRefreshSession, onReturn }: {
  session: SessionState
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
      try {
        await registerAccount(loginName, password, confirmation, session)
      } catch (error) {
        if (!isSecuritySessionExpired(error)) throw error
        const refreshedSession = await onRefreshSession()
        await registerAccount(loginName, password, confirmation, refreshedSession)
      }
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
