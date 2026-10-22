type PaginationProps = {
  page: number
  totalPages: number
  ariaLabel: string
  disabled?: boolean
  onPageChange: (page: number) => void
}

export function Pagination({ page, totalPages, ariaLabel, disabled = false, onPageChange }: PaginationProps) {
  if (totalPages <= 1) return null

  return <nav className="pagination" aria-label={ariaLabel}>
    <button type="button" disabled={disabled || page === 0} onClick={() => onPageChange(page - 1)}>
      Previous
    </button>
    <span>Page {page + 1} of {totalPages}</span>
    <button
      type="button"
      disabled={disabled || page + 1 >= totalPages}
      onClick={() => onPageChange(page + 1)}
    >
      Next
    </button>
  </nav>
}
