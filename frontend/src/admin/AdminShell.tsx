import { type ReactNode, useState } from 'react'
import type { HealthState } from '../health/useServiceHealth'

type AdminShellProps = {
  children: ReactNode
  serviceHealth: HealthState
  loginName: string
  onLogout: () => Promise<void>
}

const navigation = [
  { label: 'Dashboard', current: true },
  { label: 'Accounts', current: false },
  { label: 'SLO configuration', current: false },
  { label: 'Audit log', current: false },
  { label: 'Statistics', current: false },
]

const healthLabels: Record<HealthState, string> = {
  checking: 'Checking service',
  available: 'Service available',
  unavailable: 'Service unavailable',
}

export function AdminShell({ children, serviceHealth, loginName, onLogout }: AdminShellProps) {
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="app-shell">
      <AdminNavigation
        menuOpen={menuOpen}
        serviceHealth={serviceHealth}
        onNavigate={() => setMenuOpen(false)}
      />

      <div className="workspace">
        <AdminTopBar menuOpen={menuOpen} loginName={loginName} onLogout={onLogout}
          onMenuToggle={() => setMenuOpen((open) => !open)} />
        {children}
      </div>
    </div>
  )
}

type AdminNavigationProps = {
  menuOpen: boolean
  onNavigate: () => void
  serviceHealth: HealthState
}

function AdminNavigation({ menuOpen, onNavigate, serviceHealth }: AdminNavigationProps) {
  return (
    <aside
      id="admin-navigation"
      className={menuOpen ? 'sidebar sidebar--open' : 'sidebar'}
      aria-label="Administrator navigation"
    >
      <div className="brand">
        <span className="brand__mark" aria-hidden="true">ID</span>
        <span>
          <strong>Incident Desk</strong>
          <small>Operations console</small>
        </span>
      </div>

      <nav>
        <ul className="nav-list">
          {navigation.map((item) => (
            <li key={item.label}>
              <button
                className={item.current ? 'nav-link nav-link--current' : 'nav-link'}
                type="button"
                aria-current={item.current ? 'page' : undefined}
                aria-disabled={!item.current}
                disabled={!item.current}
                onClick={onNavigate}
              >
                {item.label}
                {!item.current && <span className="nav-link__soon">Soon</span>}
              </button>
            </li>
          ))}
        </ul>
      </nav>

      <div className="sidebar__status">
        <span className={`status-dot status-dot--${serviceHealth}`} aria-hidden="true" />
        <span>
          <strong>{healthLabels[serviceHealth]}</strong>
          <small>Spring Boot health</small>
        </span>
      </div>
    </aside>
  )
}

type AdminTopBarProps = {
  menuOpen: boolean
  loginName: string
  onLogout: () => Promise<void>
  onMenuToggle: () => void
}

function AdminTopBar({ menuOpen, loginName, onLogout, onMenuToggle }: AdminTopBarProps) {
  return (
    <header className="topbar">
      <button
        className="menu-button"
        type="button"
        aria-expanded={menuOpen}
        aria-controls="admin-navigation"
        onClick={onMenuToggle}
      >
        <span aria-hidden="true">☰</span>
        <span>Menu</span>
      </button>
      <div className="actor">
        <span className="role-badge">Administrator</span>
        <span className="actor__name">{loginName}</span>
        <span className="avatar" aria-hidden="true">A</span>
        <button className="ghost-button" type="button" onClick={() => void onLogout()}>Sign out</button>
      </div>
    </header>
  )
}
