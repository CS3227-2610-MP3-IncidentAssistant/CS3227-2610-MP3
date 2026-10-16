import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

describe('administrator application shell', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('renders the current dashboard and labels future destinations as unavailable', async () => {
    render(<App />)

    expect(screen.getByRole('heading', { level: 1, name: 'Company incident overview' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: /Accounts/ })).toBeDisabled()
    expect(await screen.findByText('Service available')).toBeInTheDocument()
  })

  it('does not invent protected incident metrics before the API is connected', () => {
    render(<App />)

    expect(screen.getByText('No protected data loaded')).toBeInTheDocument()
    expect(screen.getByText('Incident data is not connected yet')).toBeInTheDocument()
    expect(screen.getByLabelText('Open incidents unavailable')).toHaveTextContent('—')
  })

  it('reports a safely unavailable service when the health request fails', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Network unavailable'))

    render(<App />)

    expect(await screen.findByText('Service unavailable')).toBeInTheDocument()
  })
})
