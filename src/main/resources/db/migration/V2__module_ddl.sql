-- The six statements that have to be applied to the production database before this module ships:
-- two new columns, one column widened, and three new tables.
--
-- These are reproduced VERBATIM from the integration runbook, on purpose. Running them here is not
-- just how the local schema gets built — it is a rehearsal of the production change, so a mistake
-- in them shows up on a developer's machine rather than in a 2am maintenance window. Keep them and
-- the runbook identical; if one changes, the other is wrong.
--
-- Index names are the ones the previous framework would have generated, also on purpose: the
-- production database keeps its own migration bookkeeping, and matching names keep it honest.

-- Statement 1 — users.role
-- The flag that opens the staff pages. NULL (the default) is a plain company user, who only ever
-- sees their own account; 'admin' unlocks the full customer list and suspend/reactivate.
ALTER TABLE users ADD COLUMN role VARCHAR(20) NULL AFTER status;

-- Statement 2 — suscriptor_payments.external_reference
-- The Webpay buy order, and the bank reference for a reconciled transfer. Without it there is no
-- stable link from a payment row back to the gateway.
--
-- UNIQUE, not a plain index: it is the last defence against recording the same gateway transaction
-- twice. The callers claim their own row first, but that check is a read — against two concurrent
-- returns the constraint is the only thing that survives. MySQL allows multiple NULLs in a unique
-- index and the column is born entirely NULL, so adding the constraint cannot fail.
ALTER TABLE suscriptor_payments
  ADD COLUMN external_reference VARCHAR(100) NULL AFTER notes,
  ADD UNIQUE KEY suscriptor_payments_external_reference_unique (external_reference);

-- Statement 3 — widen suscriptor_payments.amount
-- Production is decimal(8,2), which caps at 999.999,99. There are 28 active plans charging more
-- than that, the largest 6.910.380 CLP; those payments throw on insert.
--
-- In production this is the slow statement (a table rebuild, unlike the INSTANT nullable ADD
-- COLUMNs above) and the easiest to get wrong: MODIFY rewrites the COLUMN DEFINITION IN FULL, so
-- omitting NOT NULL would silently make it nullable.
ALTER TABLE suscriptor_payments MODIFY COLUMN amount DECIMAL(12,2) NOT NULL;

-- Statement 4 — bank_statements
-- One uploaded bank statement. A new table owned by this module, not a mirror of anything.
--
-- file_hash is the SHA-256 of the uploaded bytes: re-uploading the same export is the most likely
-- staff mistake, and it would otherwise duplicate every movement in it.
CREATE TABLE bank_statements (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  bank              VARCHAR(50)  NOT NULL,
  account_number    VARCHAR(50)  NULL,
  period_start      DATE         NULL,
  period_end        DATE         NULL,
  original_filename VARCHAR(255) NOT NULL,
  stored_path       VARCHAR(500) NOT NULL,
  file_hash         VARCHAR(64)  NOT NULL,
  imported_by       BIGINT UNSIGNED NULL,
  movement_count    INT          NOT NULL DEFAULT 0,
  created_at        TIMESTAMP    NULL,
  updated_at        TIMESTAMP    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY bank_statements_file_hash_unique (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Statement 5 — bank_movements
-- One statement line, plus the reconciliation decision made about it. `auto_confirmed` came from a
-- later migration and is folded into the CREATE here: for production the table is created once.
--
-- row_hash is the fingerprint of the parsed line (date + amount + description + reference).
-- Statement exports routinely overlap on dates, so the same deposit arrives in two files; this is
-- what stops it being reconciled twice. UNIQUE for the same reason as external_reference: the
-- importer's own check is a read, and two simultaneous imports would both pass it.
--
-- status runs unmatched -> suggested -> matched, or -> ignored. Only 'matched' has a financial
-- effect. The values are lowercase, which is why the entities convert their enums explicitly
-- instead of relying on the default enum-to-string mapping.
CREATE TABLE bank_movements (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  bank_statement_id     BIGINT UNSIGNED NOT NULL,
  posted_at             DATE         NOT NULL,
  description           VARCHAR(500) NOT NULL,
  reference             VARCHAR(100) NULL,
  amount                DECIMAL(12,2) NOT NULL,
  direction             VARCHAR(10)  NOT NULL,          -- 'credit' | 'debit'
  counterparty_rut      VARCHAR(20)  NULL,
  row_hash              VARCHAR(64)  NOT NULL,
  status                VARCHAR(20)  NOT NULL DEFAULT 'unmatched',
  empresa_id            BIGINT UNSIGNED NULL,
  suscriptor_payment_id BIGINT UNSIGNED NULL,
  match_confidence      TINYINT      NULL,
  match_reason          VARCHAR(255) NULL,
  reconciled_by         BIGINT UNSIGNED NULL,
  reconciled_at         DATETIME     NULL,
  auto_confirmed        TINYINT(1)   NOT NULL DEFAULT 0,
  created_at            TIMESTAMP    NULL,
  updated_at            TIMESTAMP    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY bank_movements_row_hash_unique (row_hash),
  KEY bank_movements_bank_statement_id_index (bank_statement_id),
  KEY bank_movements_posted_at_index (posted_at),
  KEY bank_movements_counterparty_rut_index (counterparty_rut),
  KEY bank_movements_status_index (status),
  KEY bank_movements_empresa_id_index (empresa_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Statement 6 — webpay_transactions
-- One row per checkout started, written before the browser leaves for the payment gateway. It is
-- what the return leg looks the payment up by, because the gateway sends the customer back with a
-- cross-site POST that carries no session cookie.
--
-- buy_order is unique, and that is exactly what makes it the idempotency guard: a replayed return
-- finds the row already out of 'pending'.
--
-- user_id lives here because suscriptor_payments.user_id is NOT NULL and the gateway return lands
-- unauthenticated — we have to know who started the payment without being able to ask the session.
--
-- status runs pending -> authorized | declined | aborted | failed. Only 'authorized' has a
-- financial effect, and there is no UI to reverse one.
CREATE TABLE webpay_transactions (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  buy_order             VARCHAR(26)  NOT NULL,
  session_id            VARCHAR(61)  NOT NULL,
  empresa_id            BIGINT UNSIGNED NOT NULL,
  user_id               BIGINT UNSIGNED NOT NULL,
  amount                DECIMAL(12,2) NOT NULL,
  search                VARCHAR(100) NOT NULL DEFAULT '',
  return_to             VARCHAR(20)  NOT NULL DEFAULT 'payment-alert',
  status                VARCHAR(20)  NOT NULL DEFAULT 'pending',
  token                 VARCHAR(100) NULL,
  suscriptor_payment_id BIGINT UNSIGNED NULL,
  response_code         SMALLINT     NULL,
  committed_at          DATETIME     NULL,
  created_at            TIMESTAMP    NULL,
  updated_at            TIMESTAMP    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY webpay_transactions_buy_order_unique (buy_order),
  KEY webpay_transactions_empresa_id_index (empresa_id),
  KEY webpay_transactions_status_index (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
