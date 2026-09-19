import { useQuery } from '@tanstack/react-query'
import { NavLink, Outlet } from 'react-router-dom'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { COPY } from '../lib/copy'
import './admin.css'

/**
 * The chrome shared by the two staff pages: sidebar, header with the user chip and logout, content.
 * The sidebar's badge is the reconciliation to-do count — pending movements only, never the
 * auto-confirmed ones — and the layout owns that query so the badge stays current across routes.
 */
export function AdminLayout() {
  const auth = useAuth()

  const pending = useQuery({
    queryKey: ['bank-reconciliation', 'pending-count'],
    queryFn: () => api.get<{ pending: number }>('/api/bank-reconciliation/pending-count'),
    refetchInterval: 60_000,
  })

  const pendingCount = pending.data?.pending ?? 0
  const name = auth.user?.name ?? ''

  return (
    <div className="pa-shell">
      <aside className="pa-sidebar">
        <div className="pa-sidebar__brand">
          <span className="pa-sidebar__mark" aria-hidden="true" />
          <span className="pa-sidebar__brand-name">Uvopos</span>
        </div>

        <nav className="pa-sidebar__nav">
          <NavLink to="/payment-alert" className={({ isActive }) => `pa-nav-item ${isActive ? 'is-active' : ''}`}>
            <span className="pa-nav-item__label">Alerta de pago</span>
          </NavLink>
          <NavLink to="/conciliacion-bancaria" className={({ isActive }) => `pa-nav-item ${isActive ? 'is-active' : ''}`}>
            <span className="pa-nav-item__label">Conciliación bancaria</span>
            {pendingCount > 0 && <span className="pa-nav-item__badge">{pendingCount}</span>}
          </NavLink>
        </nav>
      </aside>

      <main className="pa-main">
        <div className="pa-main__inner">
          <header className="pa-header">
            <div className="pa-header__actions">
              <div className="pa-user-chip">
                <span className="pa-user-chip__avatar" aria-hidden="true">{name.charAt(0).toUpperCase()}</span>
                <span className="pa-user-chip__name">{name}</span>
                <span className="pa-user-chip__role">Administrador</span>
              </div>
              <button type="button" className="pa-logout" onClick={() => auth.logout()}>{COPY.logout}</button>
            </div>
          </header>

          <Outlet />

          <footer className="pa-foot">Uvopos · Panel de administración</footer>
        </div>
      </main>
    </div>
  )
}
