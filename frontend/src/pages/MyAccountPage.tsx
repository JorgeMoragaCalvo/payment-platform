import { useMutation, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { PaymentResultBanner } from '../components/PaymentResultBanner'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { COPY, dueDateSentence, NO_PAYMENT_DATE, suspendedNote, suspensionNotice } from '../lib/copy'
import { STATUS_LABEL } from '../lib/paymentStatus'
import type { CheckoutResponse, MyAccount } from '../lib/types'
import './my-account.css'

/**
 * The customer's own page: a sticky status bar with the company name, a status-coloured dot and the
 * pay button; everything else — status label, RUT, due date, plan, the suspension notices — lives in
 * a native <details>/<summary> disclosure. No JavaScript is needed for that and it must not be
 * "upgraded" to a Bootstrap dropdown, which needs Bootstrap's JS that this app does not load.
 *
 * The panel is rendered whether open or closed: a <details> has no other option, and the copy inside
 * is what the tests assert on.
 */
export function MyAccountPage() {
  const auth = useAuth()
  const [params] = useSearchParams()

  const account = useQuery({
    queryKey: ['my-account'],
    queryFn: () => api.get<MyAccount>('/api/my-account'),
    retry: (count, err) => !(err instanceof ApiError && err.status === 404) && count < 2,
  })

  const pay = useMutation({
    mutationFn: () => api.post<CheckoutResponse>('/api/my-account/webpay-checkout'),
    onSuccess: (data) => { window.location.assign(data.redirectUrl) },
  })

  const customer = account.data ?? null
  const noCompany = account.isError && account.error instanceof ApiError && account.error.status === 404
  const status = customer?.paymentStatus

  return (
    <>
      <h1 className="sr-only">Mi cuenta</h1>

      <header className={`pa-bar ${status ? `pa-bar--${status}` : ''}`}>
        <div className="pa-bar__inner">
          <div className="pa-brand">
            <span className="pa-brand__mark" aria-hidden="true" />
            <span>Uvopos</span>
          </div>

          {account.isPending && <div className="pa-info"><span className="pa-detail">{COPY.loading}</span></div>}

          {noCompany && <div className="pa-info"><span className="pa-detail">{COPY.noCompany}</span></div>}

          {customer && (
            <details className="pa-menu">
              <summary className="pa-menu__trigger" title="Ver detalle de su cuenta">
                <span className="pa-menu__dot" aria-hidden="true" />
                <span className="pa-menu__name">{customer.companyName}</span>
                <svg className="pa-menu__chevron" width="14" height="14" viewBox="0 0 24 24" fill="none"
                     stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="m6 9 6 6 6-6" />
                </svg>
              </summary>

              <div className="pa-menu__panel">
                <span className="pa-status">{STATUS_LABEL[customer.paymentStatus]}</span>

                <div className="pa-facts">
                  <div className="pa-facts__row">
                    <span className="pa-facts__label">{COPY.rutLabel}</span>
                    {customer.formattedRut}
                  </div>
                  <div className="pa-facts__row">
                    <span className="pa-facts__label">{COPY.dueLabel}</span>
                    {customer.paymentDate
                      ? dueDateSentence('customer', customer.paymentDate, customer.daysPastDue)
                      : NO_PAYMENT_DATE}
                  </div>
                  <div className="pa-facts__row">{COPY.plan(customer.planType)}</div>
                </div>

                {customer.paymentStatus === 'overdue' && customer.isActive && (
                  <div className="pa-notice">
                    {suspensionNotice('customer', customer.daysUntilSuspendable !== null && customer.daysUntilSuspendable <= 0, customer.daysUntilSuspendable)}
                  </div>
                )}

                {!customer.isActive && <div className="pa-notice">{suspendedNote('customer')}</div>}
              </div>
            </details>
          )}

          {customer?.canPay && (
            <div className="pa-action">
              <button type="button" className="pa-pay" onClick={() => pay.mutate()} disabled={pay.isPending}>
                {pay.isPending ? COPY.payBusy : COPY.pay}
              </button>
            </div>
          )}
        </div>
      </header>

      {pay.isError && (
        <div className="pa-result pa-result--danger" role="alert">
          <div className="pa-strip">{pay.error instanceof ApiError ? pay.error.message : COPY.genericError}</div>
        </div>
      )}

      <PaymentResultBanner result={params.get('payment')} voice="customer" variant="strip" />

      <div className="pa-page">
        <div className="pa-session">
          <span className="pa-session__user">{auth.user?.name}</span>
          <button type="button" className="pa-session__logout" onClick={() => auth.logout()}>{COPY.logout}</button>
        </div>
      </div>
    </>
  )
}
