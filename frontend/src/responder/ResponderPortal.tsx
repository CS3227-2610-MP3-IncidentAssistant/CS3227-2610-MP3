import type { IncidentCategory } from '../api'
import type { HealthState } from '../health/useServiceHealth'
import { RoleShell, type RoleNavigationItem } from '../shared/RoleShell'

const categoryNames: Record<IncidentCategory, string> = {
  IT: 'IT',
  HUMAN_RELATIONS: 'Human relations',
  FACILITIES: 'Facilities',
}

export function ResponderPortal({ categories, loginName, onLogout, serviceHealth }: {
  categories: IncidentCategory[]
  loginName: string
  onLogout: () => Promise<void>
  serviceHealth: HealthState
}) {
  const navigation: RoleNavigationItem[] = [{ label: 'Access', current: true }]
  return (
    <RoleShell
      actorInitial="R"
      brandSubtitle="Responder workspace"
      loginName={loginName}
      navigationId="responder-navigation"
      navigationItems={navigation}
      navigationLabel="Responder navigation"
      onLogout={onLogout}
      roleLabel="Responder"
      serviceHealth={serviceHealth}
    >
      <main id="main-content" className="content">
        <header className="page-header">
          <div>
            <p className="eyebrow">Responder workspace</p>
            <h1>Approved incident categories</h1>
            <p>Your account can access only incidents in these categories.</p>
          </div>
        </header>
        <section className="incident-panel" aria-labelledby="responder-categories-heading">
          <h2 id="responder-categories-heading">Your access</h2>
          {categories.length === 0 ? (
            <p role="status">No incident categories are currently assigned to this account.</p>
          ) : (
            <ul>
              {categories.map((category) => <li key={category}>{categoryNames[category]}</li>)}
            </ul>
          )}
        </section>
      </main>
    </RoleShell>
  )
}
