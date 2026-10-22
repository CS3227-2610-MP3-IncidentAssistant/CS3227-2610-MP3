import { type ReactNode, useState } from 'react'

export type RoleNavigationItem = {
  label: string
  current: boolean
  unavailableLabel?: string
  onSelect?: () => void
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
  onUpdatePassword?: () => void
  roleLabel: string
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
  onUpdatePassword,
  roleLabel,
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
                  aria-disabled={!item.current && !item.onSelect}
                  disabled={!item.current && !item.onSelect}
                  onClick={() => {
                    item.onSelect?.()
                    closeMenu()
                  }}
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

        <div className="sidebar__footer">
          <div className="sidebar__actor">
            <span className="avatar" aria-hidden="true">{actorInitial}</span>
            <span className="sidebar__identity">
              <strong>{loginName}</strong>
              <small>{roleLabel}</small>
            </span>
          </div>
          {onUpdatePassword && <button className="sidebar__account-action" type="button" onClick={onUpdatePassword}>Update password</button>}
          <button className="sidebar__sign-out" type="button" onClick={signOut}>Sign out</button>
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
        </header>
        {children}
      </div>
    </div>
  )
}
