/** Replaces Laravel's vendor pagination view. Pages are 0-indexed, matching Spring's Pageable. */
export function Pagination({ page, totalPages, onPageChange }: {
  page: number
  totalPages: number
  onPageChange: (page: number) => void
}) {
  if (totalPages <= 1) return null

  return (
    <nav aria-label="Paginación">
      <ul className="pagination mb-0">
        <li className={`page-item ${page === 0 ? 'disabled' : ''}`}>
          <button type="button" className="page-link" onClick={() => onPageChange(page - 1)} disabled={page === 0}>
            Anterior
          </button>
        </li>
        {Array.from({ length: totalPages }, (_, i) => (
          <li key={i} className={`page-item ${i === page ? 'active' : ''}`}>
            <button type="button" className="page-link" onClick={() => onPageChange(i)} aria-current={i === page ? 'page' : undefined}>
              {i + 1}
            </button>
          </li>
        ))}
        <li className={`page-item ${page >= totalPages - 1 ? 'disabled' : ''}`}>
          <button type="button" className="page-link" onClick={() => onPageChange(page + 1)} disabled={page >= totalPages - 1}>
            Siguiente
          </button>
        </li>
      </ul>
    </nav>
  )
}
