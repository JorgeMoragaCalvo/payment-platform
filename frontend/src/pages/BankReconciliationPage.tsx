import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Pagination } from '../components/Pagination'
import { api, ApiError } from '../lib/api'
import { COPY, formatDate } from '../lib/copy'
import type { Confirmation, ImportResponse, Movement, MovementPage, ReconciliationConfig, Settlements } from '../lib/types'

type Tab = 'movimientos' | 'importar' | 'liquidaciones'

const TAB_LABEL: Record<Tab, string> = {
  movimientos: 'Movimientos',
  importar: 'Importar cartola',
  liquidaciones: 'Liquidaciones Transbank',
}

/** Chilean peso formatting, the way the Blade's number_format(…, 0, ',', '.') did it. */
function clp(n: number): string {
  return '$' + Math.abs(n).toLocaleString('es-CL', { maximumFractionDigits: 0 })
}

/**
 * The reconciliation screen: import a statement, review the deposits it contains, confirm which
 * customer each one paid for, and compare the card processor's payouts against what was charged.
 * Tabs are client state backed by `?tab=`, as the Livewire component's query string was.
 */
export function BankReconciliationPage() {
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab | null) ?? 'movimientos'
  const [importMessage, setImportMessage] = useState<string | null>(null)

  const config = useQuery({
    queryKey: ['bank-reconciliation', 'config'],
    queryFn: () => api.get<ReconciliationConfig>('/api/bank-reconciliation/config'),
  })

  const counts = useQuery({
    queryKey: ['bank-movements', 'counts'],
    queryFn: () => api.get<MovementPage>('/api/bank-reconciliation/movements?status=&page=0').then((p) => p.counts),
  })

  function selectTab(next: Tab) {
    setParams(next === 'movimientos' ? {} : { tab: next })
  }

  const pending = counts.data ? counts.data.suggested + counts.data.unmatched : 0

  return (
    <>
      <ul className="pa-tabs">
        {(Object.keys(TAB_LABEL) as Tab[]).map((key) => (
          <li key={key}>
            <button type="button" className={`pa-tabs__link ${tab === key ? 'is-active' : ''}`}
                    aria-current={tab === key ? 'page' : undefined} onClick={() => selectTab(key)}>
              {TAB_LABEL[key]}
              {key === 'movimientos' && pending > 0 && <span className="badge badge-primary">{pending}</span>}
            </button>
          </li>
        ))}
      </ul>

      {importMessage && <div className="alert alert-success" role="alert">{importMessage}</div>}

      {tab === 'movimientos' && <MovementsTab />}
      {tab === 'importar' && (
        <ImportTab config={config.data ?? null} onImported={(msg) => { setImportMessage(msg); selectTab('movimientos') }} />
      )}
      {tab === 'liquidaciones' && <SettlementsTab />}
    </>
  )
}

// ------------------------------------------------------------------------------------ import

function ImportTab({ config, onImported }: { config: ReconciliationConfig | null; onImported: (message: string) => void }) {
  const queryClient = useQueryClient()
  const [bank, setBank] = useState('chile')
  const [file, setFile] = useState<File | null>(null)
  const [errors, setErrors] = useState<{ cartola?: string; bank?: string }>({})

  const importCartola = useMutation({
    mutationFn: () => {
      const form = new FormData()
      form.append('cartola', file!)
      form.append('bank', bank)
      return api.postForm<ImportResponse>('/api/bank-reconciliation/import', form)
    },
    onSuccess: (data) => {
      setFile(null)
      queryClient.invalidateQueries({ queryKey: ['bank-movements'] })
      queryClient.invalidateQueries({ queryKey: ['bank-reconciliation'] })
      onImported(data.message)
    },
    onError: (e) => {
      if (e instanceof ApiError) setErrors({ cartola: e.for('cartola'), bank: e.for('bank') })
      else setErrors({ cartola: COPY.genericError })
    },
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    setErrors({})
    if (!file) {
      setErrors({ cartola: 'Seleccione el archivo de la cartola.' })
      return
    }
    importCartola.mutate()
  }

  return (
    <div className="pa-card">
      <div className="pa-card__header"><strong>Importar cartola</strong></div>
      <div className="pa-card__body">
        <p className="text-muted">
          Descargue la cartola desde el portal de su banco y guárdela como <strong>CSV</strong> (en Excel:
          Archivo → Guardar como → CSV). Los movimientos ya importados no se duplican.
        </p>

        {config?.autoConfirmEnabled && (
          <p className="text-muted">
            Los depósitos que traen el <strong>RUT del pagador en la glosa</strong> y el <strong>monto exacto del
            plan</strong>, sin otra empresa igual de probable, se concilian solos: el pago queda registrado al importar
            y el movimiento aparece como <em>Conciliado automáticamente</em>. El resto espera su revisión en Movimientos.
          </p>
        )}

        <form onSubmit={submit} noValidate>
          <div className="form-group">
            <label htmlFor="recon-bank">Banco</label>
            <select id="recon-bank" className={`form-control ${errors.bank ? 'is-invalid' : ''}`} value={bank} onChange={(e) => setBank(e.target.value)}>
              {(config?.banks ?? []).map((b) => <option key={b.key} value={b.key}>{b.label}</option>)}
            </select>
            {errors.bank && <div className="invalid-feedback d-block">{errors.bank}</div>}
          </div>

          <div className="form-group">
            <label htmlFor="recon-file">Archivo de la cartola</label>
            <input type="file" id="recon-file" accept=".csv,.txt" className={`form-control-file ${errors.cartola ? 'is-invalid' : ''}`}
                   onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            {errors.cartola && <div className="invalid-feedback d-block">{errors.cartola}</div>}
          </div>

          <button type="submit" className="btn btn-primary" disabled={importCartola.isPending}>
            {importCartola.isPending ? 'Importando…' : 'Importar'}
          </button>
        </form>
      </div>
    </div>
  )
}

// --------------------------------------------------------------------------------- movements

function MovementsTab() {
  const queryClient = useQueryClient()
  const [statusFilter, setStatusFilter] = useState('')
  const [page, setPage] = useState(0)
  const [confirming, setConfirming] = useState<{ movementId: number; empresaId: number } | null>(null)
  const [assigningId, setAssigningId] = useState<number | null>(null)
  const [assignSearch, setAssignSearch] = useState('')
  const [assignError, setAssignError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const list = useQuery({
    queryKey: ['bank-movements', statusFilter, page],
    queryFn: () => api.get<MovementPage>(`/api/bank-reconciliation/movements?status=${encodeURIComponent(statusFilter)}&page=${page}`),
  })

  const confirmation = useQuery({
    queryKey: ['bank-movement-confirmation', confirming],
    queryFn: () => api.get<Confirmation>(`/api/bank-reconciliation/movements/${confirming!.movementId}/confirmation?empresaId=${confirming!.empresaId}`),
    enabled: confirming !== null,
  })

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['bank-movements'] })
    queryClient.invalidateQueries({ queryKey: ['bank-reconciliation', 'pending-count'] })
  }

  function onError(e: unknown) {
    setActionError(e instanceof ApiError ? e.message : COPY.genericError)
  }

  const confirm = useMutation({
    mutationFn: () => api.post<Movement>(`/api/bank-reconciliation/movements/${confirming!.movementId}/confirm`, { empresaId: confirming!.empresaId }),
    onSuccess: () => { setConfirming(null); invalidate() },
    onError,
  })
  const ignore = useMutation({
    mutationFn: (id: number) => api.post<Movement>(`/api/bank-reconciliation/movements/${id}/ignore`),
    onSuccess: invalidate, onError,
  })
  const returnToQueue = useMutation({
    mutationFn: (id: number) => api.post<Movement>(`/api/bank-reconciliation/movements/${id}/return-to-queue`),
    onSuccess: invalidate, onError,
  })
  const assign = useMutation({
    mutationFn: (id: number) => api.post<Confirmation>(`/api/bank-reconciliation/movements/${id}/assign`, { search: assignSearch }),
    onSuccess: (preview) => {
      setConfirming({ movementId: preview.movement.id, empresaId: preview.customerId })
      setAssigningId(null); setAssignSearch(''); setAssignError(null)
    },
    onError: (e) => setAssignError(e instanceof ApiError ? e.for('assignSearch') ?? e.for('search') ?? e.message : COPY.genericError),
  })

  function startConfirm(m: Movement) {
    setActionError(null)
    setAssigningId(null)
    setConfirming({ movementId: m.id, empresaId: m.empresaId! })
  }
  function startAssign(id: number) {
    setActionError(null)
    setConfirming(null)
    setAssigningId(id); setAssignSearch(''); setAssignError(null)
  }

  const c = list.data?.counts

  return (
    <div className="pa-card">
      <div className="pa-card__header">
        <strong>Movimientos</strong>
        {c && (
          <select className="form-control form-control-sm w-auto" value={statusFilter} aria-label="Filtrar por estado"
                  onChange={(e) => { setStatusFilter(e.target.value); setPage(0) }}>
            <option value="">Pendientes y automáticos ({c.suggested + c.unmatched + c.auto})</option>
            <option value="suggested">Con sugerencia ({c.suggested})</option>
            <option value="unmatched">Sin sugerencia ({c.unmatched})</option>
            <option value="auto">Conciliados automáticamente ({c.auto})</option>
            <option value="matched">Conciliados ({c.matched})</option>
            <option value="ignored">Ignorados ({c.ignored})</option>
          </select>
        )}
      </div>

      {actionError && <div className="pa-card__body pb-0"><div className="alert alert-danger mb-0" role="alert">{actionError}</div></div>}

      {list.isPending && <div className="pa-card__body text-muted">{COPY.loading}</div>}

      {list.data && list.data.movements.length === 0 && (
        <div className="pa-card__body">
          <div className="alert alert-secondary mb-0" role="alert">No hay movimientos en este estado. Importe una cartola para comenzar.</div>
        </div>
      )}

      {list.data && list.data.movements.length > 0 && (
        <>
          <div className="table-responsive">
            <table className="table table-sm mb-0">
              <thead><tr><th>Fecha</th><th>Descripción</th><th className="text-right">Monto</th><th>Sugerencia</th><th className="text-right">Acción</th></tr></thead>
              <tbody>
                {list.data.movements.map((m) => (
                  <MovementRows key={m.id} m={m}
                    confirmation={confirming?.movementId === m.id ? confirmation.data ?? null : null}
                    isAssigning={assigningId === m.id}
                    assignSearch={assignSearch} assignError={assignError}
                    busy={confirm.isPending || ignore.isPending || returnToQueue.isPending || assign.isPending}
                    onStartConfirm={() => startConfirm(m)}
                    onConfirm={() => confirm.mutate()}
                    onCancelConfirm={() => setConfirming(null)}
                    onIgnore={() => ignore.mutate(m.id)}
                    onReturnToQueue={() => returnToQueue.mutate(m.id)}
                    onStartAssign={() => startAssign(m.id)}
                    onAssignSearchChange={setAssignSearch}
                    onSubmitAssign={() => assign.mutate(m.id)}
                    onCancelAssign={() => { setAssigningId(null); setAssignSearch(''); setAssignError(null) }}
                  />
                ))}
              </tbody>
            </table>
          </div>
          <div className="pa-card__body">
            <Pagination page={page} totalPages={list.data.totalPages} onPageChange={setPage} />
          </div>
        </>
      )}
    </div>
  )
}

function MovementRows(props: {
  m: Movement
  confirmation: Confirmation | null
  isAssigning: boolean
  assignSearch: string
  assignError: string | null
  busy: boolean
  onStartConfirm: () => void
  onConfirm: () => void
  onCancelConfirm: () => void
  onIgnore: () => void
  onReturnToQueue: () => void
  onStartAssign: () => void
  onAssignSearchChange: (v: string) => void
  onSubmitAssign: () => void
  onCancelAssign: () => void
}) {
  const { m, confirmation } = props
  const isDebit = m.direction === 'debit'

  return (
    <>
      <tr className={isDebit ? 'text-muted' : ''}>
        <td className="text-nowrap">{formatDate(m.postedAt)}</td>
        <td>
          {m.description}
          {m.reference && <><br /><small className="text-muted">Ref. {m.reference}</small></>}
        </td>
        <td className="text-right text-nowrap">{isDebit ? '-' : ''}{clp(m.amountClp)}</td>
        <td>
          {m.customerName ? (
            <>
              <strong>{m.customerName}</strong><br />
              <small className="text-muted">{m.matchConfidence != null && `${m.matchConfidence}%`}</small>
              {m.matchReason && <><br /><small className="text-muted">{m.matchReason}</small></>}
            </>
          ) : m.matchReason ? (
            <span className="badge badge-info">{m.matchReason}</span>
          ) : (
            <span className="text-muted">—</span>
          )}
        </td>
        <td className="text-right text-nowrap">
          {/* A resolved row has no actions, and that is the whole UI for an auto-confirmed one. A
              recorded payment also suppresses "Devolver a la lista": money moved, and that is not
              a queue correction. */}
          {m.isResolved ? (
            <>
              <span className={`badge ${m.status === 'matched' ? 'badge-success' : 'badge-secondary'}`}>
                {m.status !== 'matched' ? 'Ignorado' : m.autoConfirmed ? 'Conciliado automáticamente' : 'Conciliado'}
              </span>
              {!m.hasPayment && (
                <><br /><button type="button" className="btn btn-sm btn-link p-0" onClick={props.onReturnToQueue} disabled={props.busy}>Devolver a la lista</button></>
              )}
            </>
          ) : isDebit ? (
            <button type="button" className="btn btn-sm btn-outline-secondary" onClick={props.onIgnore} disabled={props.busy}>Ignorar</button>
          ) : (
            <>
              {m.empresaId != null && (
                <button type="button" className="btn btn-sm btn-success mr-1" onClick={props.onStartConfirm} disabled={props.busy}>Conciliar</button>
              )}
              <button type="button" className="btn btn-sm btn-outline-primary mr-1" onClick={props.onStartAssign} disabled={props.busy}>Asignar…</button>
              <button type="button" className="btn btn-sm btn-outline-secondary" onClick={props.onIgnore} disabled={props.busy}>Ignorar</button>
            </>
          )}
        </td>
      </tr>

      {confirmation && (
        <tr className="bg-light">
          <td colSpan={5}>
            <div className="d-flex flex-wrap align-items-center">
              <span className="mr-3">¿Registrar {clp(m.amountClp)} como pago de <strong>{confirmation.customerName}</strong>?</span>
              <button type="button" className="btn btn-sm btn-success mr-2" onClick={props.onConfirm} disabled={props.busy}>Sí, conciliar</button>
              <button type="button" className="btn btn-sm btn-outline-secondary" onClick={props.onCancelConfirm}>Cancelar</button>
            </div>
            {confirmation.shortfall > 0 && confirmation.chargeAmount != null && (
              /* The period is a full one whatever the amount, so a short transfer has to be a visible choice. */
              <div className="alert alert-warning mt-2 mb-0 py-2">
                Depósito {clp(m.amountClp)}, cargo del plan {clp(confirmation.chargeAmount)} (faltan {clp(confirmation.shortfall)}).
                Al conciliar se renueva el período completo.
              </div>
            )}
          </td>
        </tr>
      )}

      {props.isAssigning && (
        <tr className="bg-light">
          <td colSpan={5}>
            <form className="form-inline" onSubmit={(e) => { e.preventDefault(); props.onSubmitAssign() }} noValidate>
              <label className="mr-2" htmlFor={`assign-${m.id}`}>Asignar a RUT o ID:</label>
              <input type="text" id={`assign-${m.id}`} className={`form-control form-control-sm mr-2 ${props.assignError ? 'is-invalid' : ''}`}
                     placeholder="Ej: 12.345.678-5 o 42" value={props.assignSearch} onChange={(e) => props.onAssignSearchChange(e.target.value)} />
              <button type="submit" className="btn btn-sm btn-primary mr-2" disabled={props.busy}>Continuar</button>
              <button type="button" className="btn btn-sm btn-outline-secondary" onClick={props.onCancelAssign}>Cancelar</button>
              {props.assignError && <div className="invalid-feedback d-block w-100">{props.assignError}</div>}
            </form>
          </td>
        </tr>
      )}
    </>
  )
}

// ------------------------------------------------------------------------------- settlements

function SettlementsTab() {
  const settlements = useQuery({
    queryKey: ['settlements'],
    queryFn: () => api.get<Settlements>('/api/bank-reconciliation/settlements'),
  })

  return (
    <div className="pa-card">
      <div className="pa-card__header"><strong>Liquidaciones Transbank</strong></div>
      <div className="pa-card__body">
        <p className="text-muted">
          Cada depósito de Transbank se compara con la suma de los pagos Webpay registrados los días anteriores.
          Una diferencia significa que un cargo no se liquidó, o que hubo una devolución fuera de la aplicación.
        </p>

        {settlements.isPending && <div className="text-muted">{COPY.loading}</div>}

        {settlements.data && settlements.data.rows.length === 0 && (
          <div className="alert alert-secondary mb-0" role="alert">No se han importado depósitos de Transbank.</div>
        )}

        {settlements.data && settlements.data.rows.length > 0 && (
          <div className="table-responsive">
            <table className="table table-sm mb-0">
              <thead><tr><th>Depósito</th><th>Cargos del</th><th className="text-right">Cobrado</th><th className="text-right">Depositado</th><th className="text-right">Diferencia</th></tr></thead>
              <tbody>
                {settlements.data.rows.map((r) => (
                  <tr key={r.deposit.id} className={r.matches ? '' : 'table-warning'}>
                    <td className="text-nowrap">{formatDate(r.deposit.postedAt)}</td>
                    <td className="text-nowrap">{formatDate(r.chargeDate)}</td>
                    <td className="text-right">{clp(r.charged)}</td>
                    <td className="text-right">{clp(r.deposited)}</td>
                    <td className="text-right">
                      {r.matches ? <span className="badge badge-success">Cuadra</span> : <strong>{r.difference < 0 ? '-' : ''}{clp(r.difference)}</strong>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}
