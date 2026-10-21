import { useState, type ReactNode } from 'react'
import type { HealthState } from '../health/useServiceHealth'
import { RoleShell, type RoleNavigationItem } from '../shared/RoleShell'

type AdminShellProps = {
  children: ReactNode
  accountsContent: ReactNode
  sloContent: ReactNode
  serviceHealth: HealthState
  loginName: string
  onLogout: () => Promise<void>
}

const disabledNavigation: RoleNavigationItem[] = [
  { label: 'Audit log', current: false, unavailableLabel: 'Soon' },
  { label: 'Statistics', current: false, unavailableLabel: 'Soon' },
]

export function AdminShell({ children, accountsContent, sloContent, serviceHealth, loginName, onLogout }: AdminShellProps) {
  const [activePage, setActivePage] = useState<'dashboard' | 'accounts' | 'slo'>('dashboard')
  const navigation: RoleNavigationItem[] = [
    { label: 'Dashboard', current: activePage === 'dashboard', onSelect: () => setActivePage('dashboard') },
    { label: 'Accounts', current: activePage === 'accounts', onSelect: () => setActivePage('accounts') },
    { label: 'SLO configuration', current: activePage === 'slo', onSelect: () => setActivePage('slo') },
    ...disabledNavigation,
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
      roleLabel="Administrator"
      serviceHealth={serviceHealth}
    >
      {activePage === 'dashboard' ? children : activePage === 'accounts' ? accountsContent : sloContent}
    </RoleShell>
  )
}
