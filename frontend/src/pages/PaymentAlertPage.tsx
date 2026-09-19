import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Pagination } from '../components/Pagination'
import { PaymentResultBanner } from '../components/PaymentResultBanner'
import { api, ApiError } from '../lib/api'
import { COPY, dueDateSentence, formatDate, suspendedNote, suspensionNotice } from '../lib/copy'
import { PAYMENT_STATUSES, STATUS_ALERT_CLASS, STATUS_BADGE_CLASS, STATUS_LABEL, type PaymentStatus } from '../lib/paymentStatus'
import type { CheckoutResponse, CustomerDetail, CustomerPage } from '../lib/types'

const TILE_CAPTION: Record<PaymentStatus, string> = {
  on_time: 'Sin acciones pendientes',
  due_soon: 'Requieren seguimiento',
  overdue: 'Requieren su atención',
}

/**
 * The staff page: every customer colour-coded by status, the RUT/id lookup, and the actions.
 * `?search=` and `?payment=` are restored from the query string because that is how the Webpay
 * return leg brings staff back to the customer they were looking at.
 */
export function PaymentAlertPage() {
  const [params] = useSearchParams()
  const queryClient = useQueryClient()

  const [statusFilter, setStatusFilter] = useState('')
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState(params.get('search') ?? '')
  const [term, setTerm] = useState(params.get('search') ?? '')
  const [confirmingSuspend, setConfirmingSuspend] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const list = useQuery({
    queryKey: ['customers', 'list', statusFilter, page],
    queryFn: () => api.get<CustomerPage>(`/api/customers?status=${encodeURIComponent(statusFilter)}&page=${page}`),
  })

  const lookup = useQuery({
    queryKey: ['customers', 'lookup', term],
    queryFn: () => api.post<CustomerDetail>('/api/customers/lookup', { search: term }),
    enabled: term.trim().length > 0,
    retry: false,
  })

  useEffect(() => { setConfirmingSuspend(false); setActionError(null) }, [term])

  const customer = lookup.data ?? null
  const lookupError = lookup.error instanceof ApiError ? lookup.error : null
  const notFound = lookupError?.status === 404
  const invalidTerm = lookupError && lookupError.status !== 404 ? lookupError.for('search') ?? lookupError.message : null

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ['customers'] })
  }

  const pay = useMutation({
    mutationFn: () => api.post<CheckoutResponse>(`/api/customers/${customer!.id}/webpay-checkout`, { search: term }),
    onSuccess: (data) => { window.location.assign(data.redirectUrl) },
    onError: (e) => setActionError(e instanceof ApiError ? e.message : COPY.genericError),
  })

  const suspend = useMutation({
    mutationFn: () => api.post<CustomerDetail>(`/api/customers/${customer!.id}/suspend`),
    onSuccess: () => { setConfirmingSuspend(false); refresh() },
    onError: (e) => setActionError(e instanceof ApiError ? e.message : COPY.genericError),
  })

  const reactivate = useMutation({
    mutationFn: () => api.post<CustomerDetail>(`/api/customers/${customer!.id}/reactivate`),
    onSuccess: refresh,
    onError: (e) => setActionError(e instanceof ApiError ? e.message : COPY.genericError),
  })

  function submitLookup(e: FormEvent) {
    e.preventDefault()
    setTerm(search.trim())
  }

  function selectRow(id: number) {
    setSearch(String(id))
    setTerm(String(id))
  }

  const counts = list.data?.counts
  const total = counts ? counts.onTime + counts.dueSoon + counts.overdue : 0

  return (
    <>
      {counts && (
        <div className="pa-stats">
          <button type="button" className={`pa-stat pa-stat--info ${statusFilter === '' ? 'is-active' : ''}`}
                  aria-pressed={statusFilter === ''} onClick={() => { setStatusFilter(''); setPage(0) }}>
            <span className="pa-stat__label">Todos</span>
            <span className="pa-stat__value">{total}</span>
            <span className="pa-stat__meta">clientes registrados</span>
          </button>
          {PAYMENT_STATUSES.map((status) => {
            const count = { on_time: counts.onTime, due_soon: counts.dueSoon, overdue: counts.overdue }[status]
            return (
              <button key={status} type="button" className={`pa-stat pa-stat--${status} ${statusFilter === status ? 'is-active' : ''}`}
                      aria-pressed={statusFilter === status} onClick={() => { setStatusFilter(status); setPage(0) }}>
                <span className="pa-stat__label">{STATUS_LABEL[status]}</span>
                <span className="pa-stat__value">{count}</span>
                <span className="pa-stat__meta">{TILE_CAPTION[status]}</span>
              </button>
            )
          })}
        </div>
      )}

      <div className="pa-card">
        <div className="pa-card__header"><h2 className="pa-card__title">Estado de pago del cliente</h2></div>
        <div className="pa-card__body">
          <form onSubmit={submitLookup} className="form-inline mb-3" noValidate>
            <label htmlFor="payment-alert-search" className="mr-2">{COPY.lookupLabel}</label>
            <input id="payment-alert-search" className={`form-control mr-2 ${invalidTerm ? 'is-invalid' : ''}`}
                   value={search} onChange={(e) => setSearch(e.target.value)} maxLength={20} />
            <button type="submit" className="btn btn-primary" disabled={lookup.isFetching}>
              {lookup.isFetching ? COPY.lookupBusy : COPY.lookupButton}
            </button>
            {invalidTerm && <div className="invalid-feedback d-block w-100">{invalidTerm}</div>}
          </form>

          <PaymentResultBanner result={params.get('payment')} voice="staff" variant="alert" />

          {notFound && (
            <div className="alert alert-secondary mb-0" role="alert">
              No se encontró un cliente para <strong>{term}</strong>.
            </div>
          )}

          {customer && (
            <>
              <div className={`alert ${STATUS_ALERT_CLASS[customer.paymentStatus]} mb-3`} role="alert">
                <h5 className="alert-heading mb-2">{STATUS_LABEL[customer.paymentStatus]}</h5>
                <p className="mb-1">
                  <strong>{customer.name}</strong>
                  {' '}&mdash; RUT {customer.formattedRut} (ID {customer.id}){' '}
                  <span className="badge badge-light">{COPY.plan(customer.planType)}</span>
                </p>
                <p className="mb-0">{dueDateSentence('staff', customer.paymentDate, customer.daysPastDue)}</p>

                {customer.paymentStatus === 'overdue' && customer.isActive && (
                  <p className="mb-0 mt-2"><strong>{suspensionNotice('staff', customer.isSuspendable, customer.daysUntilSuspendable)}</strong></p>
                )}
                {!customer.isActive && <p className="mb-0 mt-2"><strong>{suspendedNote('staff')}</strong></p>}
              </div>

              {actionError && <div className="alert alert-danger" role="alert">{actionError}</div>}

              <div className="d-flex flex-wrap" style={{ gap: '.5rem' }}>
                {customer.canPay && (
                  <button type="button" className="btn btn-primary" onClick={() => pay.mutate()} disabled={pay.isPending}>
                    {pay.isPending ? COPY.payBusy : COPY.pay}
                  </button>
                )}

                {customer.isSuspendable && !confirmingSuspend && (
                  <button type="button" className="btn btn-outline-danger" onClick={() => setConfirmingSuspend(true)}>{COPY.suspend}</button>
                )}
                {customer.isSuspendable && confirmingSuspend && (
                  <>
                    <span className="align-self-center">{COPY.suspendConfirm}</span>
                    <button type="button" className="btn btn-danger" onClick={() => suspend.mutate()} disabled={suspend.isPending}>{COPY.suspendYes}</button>
                    <button type="button" className="btn btn-outline-secondary" onClick={() => setConfirmingSuspend(false)}>{COPY.cancel}</button>
                  </>
                )}

                {!customer.isActive && (
                  <button type="button" className="btn btn-outline-success" onClick={() => reactivate.mutate()} disabled={reactivate.isPending}>{COPY.reactivate}</button>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      <div className="pa-card">
        <div className="pa-card__header">
          <h2 className="pa-card__title">Todos los clientes</h2>
          {statusFilter && <small className="text-muted">{STATUS_LABEL[statusFilter as PaymentStatus]}</small>}
        </div>

        {list.isPending && <div className="pa-card__body text-muted">{COPY.loading}</div>}

        {list.data && list.data.customers.length === 0 && (
          <div className="pa-card__body"><div className="alert alert-secondary mb-0" role="alert">No hay clientes en este estado.</div></div>
        )}

        {list.data && list.data.customers.length > 0 && (
          <>
            <table className="table table-sm table-hover pa-table mb-0">
              <thead><tr><th>Cliente</th><th>RUT</th><th>Plan</th><th>Vence</th><th>Estado</th></tr></thead>
              <tbody>
                {list.data.customers.map((c) => (
                  <tr key={c.id} onClick={() => selectRow(c.id)} className={customer?.id === c.id ? 'table-active' : ''}>
                    <td>{c.name}</td>
                    <td>{c.formattedRut}</td>
                    <td>{c.planType}</td>
                    <td className={`pa-due pa-due--${c.paymentStatus}`}>{formatDate(c.paymentDate)}</td>
                    <td>
                      <span className={`badge ${STATUS_BADGE_CLASS[c.paymentStatus]}`}>{STATUS_LABEL[c.paymentStatus]}</span>
                      {!c.isActive && <span className="badge badge-dark ml-1">{COPY.suspendedBadge}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="pa-card__body">
              <Pagination page={page} totalPages={list.data.totalPages} onPageChange={setPage} />
            </div>
          </>
        )}
      </div>
    </>
  )
}
