/**
 * What the API sends, mirroring the response records in the backend's `web/` package. Kept as
 * plain types: if a field changes there, the compiler points at every consumer here.
 */
import type { PaymentStatus } from './paymentStatus'

/** `CustomerPresenter.Summary` — one row of the staff list. */
export type CustomerSummary = {
  id: number
  name: string
  formattedRut: string
  paymentDate: string | null
  daysPastDue: number | null
  paymentStatus: PaymentStatus
  planType: string
  isActive: boolean
}

/** `CustomerPresenter.Detail` — the staff detail panel. */
export type CustomerDetail = CustomerSummary & {
  chargeAmount: number | null
  isSuspendable: boolean
  daysUntilSuspendable: number | null
  canPay: boolean
}

/** `CustomerPresenter.MyAccount` — the customer's own view. */
export type MyAccount = {
  companyName: string
  formattedRut: string
  paymentDate: string | null
  daysPastDue: number | null
  paymentStatus: PaymentStatus
  planType: string
  isActive: boolean
  daysUntilSuspendable: number | null
  canPay: boolean
}

export type StatusCounts = { onTime: number; dueSoon: number; overdue: number }

/** `CustomerController.CustomerPage` */
export type CustomerPage = {
  customers: CustomerSummary[]
  page: number
  totalPages: number
  totalCount: number
  counts: StatusCounts
}

export type CheckoutResponse = { redirectUrl: string }

// ---------------------------------------------------------------- bank reconciliation

export type MovementStatus = 'unmatched' | 'suggested' | 'matched' | 'ignored'

/** `BankReconciliationController.MovementDto` */
export type Movement = {
  id: number
  postedAt: string
  description: string
  reference: string | null
  amountClp: number
  direction: 'credit' | 'debit'
  status: MovementStatus
  empresaId: number | null
  customerName: string | null
  matchConfidence: number | null
  matchReason: string | null
  autoConfirmed: boolean
  hasPayment: boolean
  isResolved: boolean
}

export type MovementCounts = {
  suggested: number
  unmatched: number
  auto: number
  matched: number
  ignored: number
  pending: number
}

export type MovementPage = {
  movements: Movement[]
  page: number
  totalPages: number
  totalCount: number
  counts: MovementCounts
}

export type Confirmation = {
  movement: Movement
  customerId: number
  customerName: string
  customerRut: string
  chargeAmount: number | null
  shortfall: number
}

export type ReconciliationConfig = {
  banks: { key: string; label: string }[]
  autoConfirmEnabled: boolean
}

export type ImportResponse = { message: string; movementCount: number; autoConfirmedCount: number }

export type SettlementRow = {
  deposit: Movement
  chargeDate: string
  charged: number
  expected: number
  deposited: number
  difference: number
  matches: boolean
}

export type Settlements = {
  rows: SettlementRow[]
  deposits: number
  mismatched: number
  totalDifference: number
}
