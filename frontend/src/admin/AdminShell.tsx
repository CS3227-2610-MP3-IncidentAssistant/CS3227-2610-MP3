import { useState, type ReactNode } from 'react'
import { RoleShell, type RoleNavigationItem } from '../shared/RoleShell'

type AdminShellProps = {
  children: ReactNode
  accountsContent: ReactNode
  sloContent: ReactNode
  statisticsContent: ReactNode
  loginName: string
  onLogout: () => Promise<void>
  onUpdatePassword?: () => void
}

export function AdminShell({ children, accountsContent, sloContent, statisticsContent, loginName, onLogout, onUpdatePassword }: AdminShellProps) {
  const [activePage, setActivePage] = useState<'dashboard' | 'accounts' | 'slo' | 'statistics'>('dashboard')
  const navigation: RoleNavigationItem[] = [
    { label: 'Dashboard', current: activePage === 'dashboard', onSelect: () => setActivePage('dashboard') },
    { label: 'Accounts', current: activePage === 'accounts', onSelect: () => setActivePage('accounts') },
    { label: 'SLO configuration', current: activePage === 'slo', onSelect: () => setActivePage('slo') },
    { label: 'Statistics', current: activePage === 'statistics', onSelect: () => setActivePage('statistics') },
  ]
  return (
    <RoleShell
      actorInitial="A"
      brandSubtitle="Operations console"
      loginName={loginName}
      navigationId="administrator-navigation"
      navigationItems={navigation}
      navigationLabel="Administrator navigation"
      onLogout={onLogout}
      onUpdatePassword={onUpdatePassword}
      roleLabel="Administrator"
    >
      {activePage === 'dashboard' ? children
        : activePage === 'accounts' ? accountsContent
          : activePage === 'slo' ? sloContent : statisticsContent}
    </RoleShell>
  )
}
