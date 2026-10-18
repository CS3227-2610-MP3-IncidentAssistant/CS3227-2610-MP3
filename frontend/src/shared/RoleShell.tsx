import { type ReactNode, useState } from 'react'
import type { HealthState } from '../health/useServiceHealth'

export type RoleNavigationItem = {
  label: string
  current: boolean
  unavailableLabel?: string
}

type RoleShellProps = {
  actorInitial: string
  brandSubtitle: string
  children: ReactNode
  loginName: string
  navigationId: string
  navigationItems: RoleNavigationItem[]
  navigationLabel: string
  onLogout: () => Promise<void>
  roleLabel: string
  serviceHealth: HealthState
}

const healthLabels: Record<HealthState, string> = {
  checking: 'Checking service',
  available: 'Service available',
  unavailable: 'Service unavailable',
}

export function RoleShell({
  actorInitial,
  brandSubtitle,
  children,
  loginName,
  navigationId,
  navigationItems,
  navigationLabel,
  onLogout,
  roleLabel,
  serviceHealth,
}: RoleShellProps) {
  const [menuOpen, setMenuOpen] = useState(false)

  function closeMenu() {
    setMenuOpen(false)
  }

  function toggleMenu() {
    setMenuOpen((open) => !open)
  }

  function signOut() {
    void onLogout()
  }

  return (
    <div className="app-shell">
      <aside
        id={navigationId}
        className={menuOpen ? 'sidebar sidebar--open' : 'sidebar'}
        aria-label={navigationLabel}
      >
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">ID</span>
          <span>
            <strong>Incident Desk</strong>
            <small>{brandSubtitle}</small>
          </span>
        </div>

        <nav>
          <ul className="nav-list">
            {navigationItems.map((item) => (
              <li key={item.label}>
                <button
                  className={item.current ? 'nav-link nav-link--current' : 'nav-link'}
                  type="button"
                  aria-current={item.current ? 'page' : undefined}
                  aria-disabled={!item.current}
                  disabled={!item.current}
                  onClick={closeMenu}
                >
                  {item.label}
                  {item.unavailableLabel && (
                    <span className="nav-link__soon">{item.unavailableLabel}</span>
                  )}
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

      <div className="workspace">
        <header className="topbar">
          <button
            className="menu-button"
            type="button"
            aria-expanded={menuOpen}
            aria-controls={navigationId}
            onClick={toggleMenu}
          >
            <span aria-hidden="true">☰</span>
            <span>Menu</span>
          </button>
          <div className="actor">
            <span className="role-badge">{roleLabel}</span>
            <span className="actor__name">{loginName}</span>
            <span className="avatar" aria-hidden="true">{actorInitial}</span>
            <button className="ghost-button" type="button" onClick={signOut}>Sign out</button>
          </div>
        </header>
        {children}
      </div>
    </div>
  )
}
