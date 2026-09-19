/**
 * The payment status as the API sends it (lowercase wire value) and everything the two views derive
 * from it. One module, so there is exactly one place that maps a status to a label or a colour.
 */
export type PaymentStatus = 'on_time' | 'due_soon' | 'overdue'

export const PAYMENT_STATUSES: readonly PaymentStatus[] = ['on_time', 'due_soon', 'overdue']

/**
 * Labels as the Laravel app shows them today (`PaymentStatus::label()`), not the shorter ones the
 * React design doc proposed. These are what the staff have been reading on screen.
 */
export const STATUS_LABEL: Record<PaymentStatus, string> = {
  on_time: 'Pago al día',
  due_soon: 'Pago próximo a vencer',
  overdue: 'Pago atrasado',
}

export const STATUS_ALERT_CLASS: Record<PaymentStatus, string> = {
  on_time: 'alert-success',
  due_soon: 'alert-orange',
  overdue: 'alert-danger',
}

export const STATUS_BADGE_CLASS: Record<PaymentStatus, string> = {
  on_time: 'badge-success',
  due_soon: 'badge-orange',
  overdue: 'badge-danger',
}

export function isPaymentStatus(value: unknown): value is PaymentStatus {
  return typeof value === 'string' && (PAYMENT_STATUSES as readonly string[]).includes(value)
}
