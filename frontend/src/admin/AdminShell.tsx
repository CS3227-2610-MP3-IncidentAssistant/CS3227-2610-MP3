import type { ReactNode } from 'react'
import type { HealthState } from '../health/useServiceHealth'
import { RoleShell, type RoleNavigationItem } from '../shared/RoleShell'

type AdminShellProps = {
  children: ReactNode
  serviceHealth: HealthState
  loginName: string
  onLogout: () => Promise<void>
}

const navigation: RoleNavigationItem[] = [
  { label: 'Dashboard', current: true },
  { label: 'Accounts', current: false, unavailableLabel: 'Soon' },
  { label: 'SLO configuration', current: false, unavailableLabel: 'Soon' },
  { label: 'Audit log', current: false, unavailableLabel: 'Soon' },
  { label: 'Statistics', current: false, unavailableLabel: 'Soon' },
]

export function AdminShell({ children, serviceHealth, loginName, onLogout }: AdminShellProps) {
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
      {children}
    </RoleShell>
  )
}
