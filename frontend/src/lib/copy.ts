/**
 * The Spanish copy, taken verbatim from the two Blade views (`payment-alert.blade.php` and
 * `my-payment-status.blade.php`). In the Laravel app the staff view and the customer view rendered
 * different markup but the feature tests asserted on the wording — so the wording is the contract,
 * and it lives here once.
 *
 * The two views deliberately differ in voice: staff read about a customer ("El servicio", the
 * customer's ID, "el estado del cliente"), the customer reads about themselves ("Su servicio", "su
 * tarjeta"). Every function that diverges takes a `voice` instead of being duplicated.
 */

export type Voice = 'staff' | 'customer'

function dias(n: number): string {
  return n === 1 ? 'día' : 'días'
}

/** dd-mm-yyyy, the format both Blade views use (`->format('d-m-Y')`). */
export function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const [y, m, d] = iso.split('-')
  return `${d}-${m}-${y}`
}

export const NO_PAYMENT_DATE = 'Sin fecha de pago registrada.'

/**
 * The due-date line. Staff: "Fecha de pago: 12-03-2026 — vence en 2 días." Customer: "Vence
 * 12-03-2026 (vence en 2 días)". Same facts, the wording each view already uses.
 */
export function dueDateSentence(voice: Voice, paymentDate: string | null, daysPastDue: number | null): string {
  if (!paymentDate || daysPastDue === null) return NO_PAYMENT_DATE

  const date = formatDate(paymentDate)
  const n = Math.abs(daysPastDue)
  const relative =
    daysPastDue > 0 ? `${n} ${dias(n)} de atraso`
    : daysPastDue === 0 ? 'vence hoy'
    : `vence en ${n} ${dias(n)}`

  return voice === 'staff'
    ? `Fecha de pago: ${date} — ${relative}.`
    : `Vence ${date} (${relative})`
}

/** Shown while overdue and still active. Identical wording in both views except the pronoun. */
export function suspensionNotice(voice: Voice, isSuspendable: boolean, daysUntilSuspendable: number | null): string {
  const service = voice === 'staff' ? 'El servicio' : 'Su servicio'
  if (isSuspendable) return `${service} ya puede suspenderse por falta de pago.`
  const n = daysUntilSuspendable ?? 0
  return `Tiene ${n} ${dias(n)} para regularizar el pago antes de la suspensión del servicio.`
}

export function suspendedNote(voice: Voice): string {
  return voice === 'staff'
    ? 'Servicio suspendido.'
    : 'Servicio suspendido. Al pagar, su servicio se reactivará automáticamente.'
}

/**
 * The five Webpay outcomes. `error` is deliberately distinct from `declined`: after a commit
 * exception the charge may still exist, so this copy must never suggest simply retrying.
 */
export type PaymentResult = 'success' | 'declined' | 'aborted' | 'error' | 'failed'

export const PAYMENT_RESULTS: readonly PaymentResult[] = ['success', 'declined', 'aborted', 'error', 'failed']

export type Tone = 'success' | 'warning' | 'danger'

export const PAYMENT_RESULT_TONE: Record<PaymentResult, Tone> = {
  success: 'success',
  declined: 'danger',
  aborted: 'warning',
  error: 'danger',
  failed: 'danger',
}

const PAYMENT_RESULT_TEXT: Record<Voice, Record<PaymentResult, string>> = {
  staff: {
    success: 'Pago realizado con éxito. El estado del cliente se actualizó.',
    declined: 'El pago fue rechazado por el emisor de la tarjeta. No se realizó ningún cargo.',
    aborted: 'El pago se canceló o el formulario expiró. No se realizó ningún cargo.',
    error: 'No se pudo confirmar el pago con Transbank. Verifique el estado antes de reintentar.',
    failed: 'El pago no pudo procesarse. Intente nuevamente.',
  },
  customer: {
    success: 'Pago realizado con éxito. Su estado se actualizó.',
    declined: 'Su tarjeta fue rechazada por el banco emisor. No se realizó ningún cargo.',
    aborted: 'Canceló el pago o el formulario expiró. No se realizó ningún cargo.',
    error: 'No pudimos confirmar su pago. Revise su cartola antes de reintentar o contáctenos.',
    failed: 'El pago no pudo procesarse. Intente nuevamente.',
  },
}

export function paymentResultText(voice: Voice, result: PaymentResult): string {
  return PAYMENT_RESULT_TEXT[voice][result]
}

export function isPaymentResult(value: unknown): value is PaymentResult {
  return typeof value === 'string' && (PAYMENT_RESULTS as readonly string[]).includes(value)
}

export const COPY = {
  lookupLabel: 'RUT o ID de cliente',
  lookupButton: 'Buscar',
  lookupBusy: 'Buscando…',
  notFound: (term: string) => `No se encontró un cliente para ${term}.`,
  pay: 'Pagar con Webpay',
  payBusy: 'Redirigiendo…',
  suspend: 'Suspender servicio',
  suspendConfirm: '¿Confirmar suspensión?',
  suspendYes: 'Sí, suspender',
  cancel: 'Cancelar',
  reactivate: 'Reactivar servicio',
  suspendedBadge: 'Suspendido',
  logout: 'Cerrar sesión',
  plan: (planType: string) => `Plan: ${planType}`,
  rutLabel: 'RUT',
  dueLabel: 'Vencimiento',
  noCompany: 'No hay una empresa asociada a su cuenta.',
  loading: 'Cargando…',
  genericError: 'Ocurrió un error. Intente nuevamente.',
} as const
