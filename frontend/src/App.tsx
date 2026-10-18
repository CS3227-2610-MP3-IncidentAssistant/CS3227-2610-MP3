import { useCallback, useEffect, useState } from 'react'
import { AdminDashboard } from './admin/AdminDashboard'
import { AdminShell } from './admin/AdminShell'
import { administratorLogin, loadSession, logout, type SessionState } from './api'
import { useServiceHealth } from './health/useServiceHealth'

export function App() {
  const serviceHealth = useServiceHealth()
  const sessionState = useAdministratorSession()

  if (sessionState.failed) {
    return <SessionFailure onRetry={sessionState.reload} />
  }
  if (!sessionState.session) {
    return <main className="auth-layout"><p role="status">Checking your session…</p></main>
  }
  if (!isAdministrator(sessionState.session)) {
    return <AdministratorLogin session={sessionState.session} onAuthenticated={sessionState.reload} />
  }

  const session = sessionState.session
  async function signOut() {
    await logout(session)
    sessionState.clear()
  }

  return <AdminShell
    serviceHealth={serviceHealth}
    loginName={session.loginName ?? 'Administrator'}
    onLogout={signOut}
  >
    <AdminDashboard />
  </AdminShell>
}

function useAdministratorSession() {
  const [session, setSession] = useState<SessionState | null>(null)
  const [failed, setFailed] = useState(false)

  const receive = useCallback((next: SessionState) => {
    setSession(next)
    setFailed(false)
  }, [])
  const reject = useCallback((error: unknown) => {
    if (!(error instanceof DOMException && error.name === 'AbortError')) setFailed(true)
  }, [])
  const reload = useCallback((signal?: AbortSignal) => {
    return loadSession(signal).then(receive).catch(reject)
  }, [receive, reject])

  useEffect(() => {
    const controller = new AbortController()
    void Promise.resolve().then(() => reload(controller.signal))
    return () => controller.abort()
  }, [reload])

  const clear = useCallback(() => {
    setSession((current) => current
      ? { ...current, authenticated: false, loginName: undefined, role: undefined }
      : current)
  }, [])
  return { session, failed, reload: () => reload(), clear }
}

function isAdministrator(session: SessionState) {
  return session.authenticated && session.role === 'ADMINISTRATOR'
}

function AdministratorLogin({ session, onAuthenticated }: {
  session: SessionState
  onAuthenticated: () => Promise<void>
}) {
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      await administratorLogin(loginName, password, session.csrfToken)
      setPassword('')
      await onAuthenticated()
    } catch {
      setPassword('')
      setError('Sign-in failed. Check your credentials and portal, then try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return <main className="auth-layout">
    <section className="auth-card" aria-labelledby="login-heading">
      <p className="eyebrow">Incident Desk</p>
      <h1 id="login-heading">Administrator sign in</h1>
      <p>Use an enabled administrator account. This portal does not create accounts.</p>
      {error && <div className="form-error" role="alert">{error}</div>}
      <form onSubmit={submit}>
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
    </section>
  </main>
}

function SessionFailure({ onRetry }: { onRetry: () => void }) {
  return <main className="auth-layout">
    <section className="auth-card" role="alert">
      <h1>Session unavailable</h1>
      <p>The service could not check your session. No protected data was loaded.</p>
      <button className="primary-button" type="button" onClick={onRetry}>Try again</button>
    </section>
  </main>
}
