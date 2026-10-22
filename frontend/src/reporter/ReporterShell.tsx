import { useState, type ReactNode } from 'react'
import { RoleShell, type RoleNavigationItem } from '../shared/RoleShell'

export function ReporterShell({ children, promotionContent, loginName, onLogout, onUpdatePassword }: {
  children: ReactNode
  promotionContent: ReactNode
  loginName: string
  onLogout: () => Promise<void>
  onUpdatePassword?: () => void
}) {
  const [activePage, setActivePage] = useState<'incidents' | 'promotion'>('incidents')
  const navigation: RoleNavigationItem[] = [
    { label: 'My incidents', current: activePage === 'incidents', onSelect: () => setActivePage('incidents') },
    { label: 'Promotion', current: activePage === 'promotion', onSelect: () => setActivePage('promotion') },
  ]

  return (
    <RoleShell
      actorInitial="R"
      brandSubtitle="Reporter workspace"
      loginName={loginName}
      navigationId="reporter-navigation"
      navigationItems={navigation}
      navigationLabel="Reporter navigation"
      onLogout={onLogout}
      onUpdatePassword={onUpdatePassword}
      roleLabel="Reporter"
    >
      <main className="content">{activePage === 'incidents' ? children : promotionContent}</main>
    </RoleShell>
  )
}
