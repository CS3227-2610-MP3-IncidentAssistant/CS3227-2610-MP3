import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminShell } from './AdminShell'

afterEach(cleanup)

describe('Administrator shell navigation', () => {
  it('does not expose internal audit records as a navigation destination', () => {
    render(
      <AdminShell
        accountsContent={<div>Accounts content</div>}
        loginName="admin"
        onLogout={vi.fn(async () => undefined)}
        serviceHealth="available"
        sloContent={<div>SLO content</div>}
      >
        <div>Dashboard content</div>
      </AdminShell>,
    )

    expect(screen.getByRole('navigation')).toHaveTextContent('Dashboard')
    expect(screen.queryByRole('button', { name: /audit/i })).not.toBeInTheDocument()
  })
})
