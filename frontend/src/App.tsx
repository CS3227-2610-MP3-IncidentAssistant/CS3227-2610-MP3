import { AdminDashboard } from './admin/AdminDashboard'
import { AdminShell } from './admin/AdminShell'
import { useServiceHealth } from './health/useServiceHealth'

export function App() {
  const serviceHealth = useServiceHealth()

  return (
    <AdminShell serviceHealth={serviceHealth}>
      <AdminDashboard />
    </AdminShell>
  )
}
