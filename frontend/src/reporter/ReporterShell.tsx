import type { ReactNode } from 'react'
import type { HealthState } from '../health/useServiceHealth'
import { RoleShell, type RoleNavigationItem } from '../shared/RoleShell'

const navigation: RoleNavigationItem[] = [
  { label: 'My incidents', current: true },
  { label: 'Promotion', current: false, unavailableLabel: 'Next' },
]

export function ReporterShell({ children, loginName, serviceHealth, onLogout }: {
  children: ReactNode
  loginName: string
  serviceHealth: HealthState
  onLogout: () => Promise<void>
}) {
  return (
    <RoleShell
      actorInitial="R"
      brandSubtitle="Reporter workspace"
      loginName={loginName}
      navigationId="reporter-navigation"
      navigationItems={navigation}
      navigationLabel="Reporter navigation"
      onLogout={onLogout}
      roleLabel="Reporter"
      serviceHealth={serviceHealth}
    >
      <main className="content">{children}</main>
    </RoleShell>
  )
}
