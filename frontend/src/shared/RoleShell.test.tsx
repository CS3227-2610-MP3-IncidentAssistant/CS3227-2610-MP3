import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RoleShell } from './RoleShell'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('responsive role navigation', () => {
  it('hides closed narrow navigation and restores focus to Menu after selection', () => {
    const viewport = mockViewport(true)
    const onSelect = vi.fn()
    renderShell(onSelect)

    const sidebar = document.getElementById('test-navigation')!
    const menu = screen.getByRole('button', { name: 'Menu' })
    expect(sidebar).toHaveAttribute('inert')
    expect(menu).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(menu)
    expect(sidebar).not.toHaveAttribute('inert')
    expect(menu).toHaveAttribute('aria-expanded', 'true')

    const queue = within(sidebar).getByRole('button', { name: 'Queue' })
    queue.focus()
    fireEvent.click(queue)
    expect(onSelect).toHaveBeenCalledOnce()
    expect(sidebar).toHaveAttribute('inert')
    expect(menu).toHaveFocus()

    act(() => viewport.resize(false))
    expect(sidebar).not.toHaveAttribute('inert')
  })
})

function renderShell(onSelect: () => void) {
  render(
    <RoleShell
      actorInitial="R"
      brandSubtitle="Responder workspace"
      loginName="test-responder"
      navigationId="test-navigation"
      navigationItems={[{ label: 'Queue', current: true, onSelect }]}
      navigationLabel="Responder navigation"
      onLogout={async () => {}}
      roleLabel="Responder"
      serviceHealth="available"
    >
      <main>Queue content</main>
    </RoleShell>,
  )
}

function mockViewport(initiallyNarrow: boolean) {
  let matches = initiallyNarrow
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    get matches() { return matches },
    addEventListener: (_event: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_event: string, listener: () => void) => listeners.delete(listener),
  })))
  return {
    resize(narrow: boolean) {
      matches = narrow
      listeners.forEach((listener) => listener())
    },
  }
}
