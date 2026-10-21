import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Pagination } from './Pagination'

afterEach(cleanup)

describe('Pagination', () => {
  it('moves to adjacent zero-based pages and exposes the collection label', () => {
    const onPageChange = vi.fn()
    render(<Pagination page={1} totalPages={3} ariaLabel="Account pages" onPageChange={onPageChange} />)

    expect(screen.getByRole('navigation', { name: 'Account pages' })).toHaveTextContent('Page 2 of 3')
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))

    expect(onPageChange).toHaveBeenNthCalledWith(1, 0)
    expect(onPageChange).toHaveBeenNthCalledWith(2, 2)
  })

  it('hides single-page controls and disables navigation while loading', () => {
    const { rerender } = render(<Pagination page={0} totalPages={1} ariaLabel="Incident pages"
      onPageChange={vi.fn()} />)
    expect(screen.queryByRole('navigation', { name: 'Incident pages' })).not.toBeInTheDocument()

    rerender(<Pagination page={0} totalPages={2} ariaLabel="Incident pages" disabled
      onPageChange={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })
})
