-- Local mirror of the four tables this module reads that already exist in the production database,
-- reproducing them AS THEY ARE TODAY — before the coordinated schema change in V2.
--
-- Every column below was read back from the production schema rather than guessed, and the types,
-- nullability, defaults, indexes, engine and collation match it exactly for the columns this
-- feature touches. That fidelity is the whole point: with `ddl-auto=validate`, a green startup
-- against this mirror is evidence the entity mappings also fit production. The previous version of
-- this file was written from a development-only mirror and got four things wrong.
--
-- It is NOT full parity. The real tables are much wider (`users` has 24 columns, `datos_plan` 22),
-- and the extra ones are irrelevant here — except `isUvo`/`nivelUvo`, which are included because
-- the staff-exclusion condition in the user repository references them.
--
-- Nothing here is ever applied to production; these tables already exist there.

CREATE TABLE empresas (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rut              VARCHAR(255) NOT NULL,
  RazonSocial      VARCHAR(255) NULL,
  nombre_fantasia  VARCHAR(250) NULL,
  proximoPago      DATE         NULL,
  tipoPlan         VARCHAR(45)  NULL,
  estado           VARCHAR(45)  NULL,
  created_at       TIMESTAMP    NULL,
  updated_at       TIMESTAMP    NULL,
  deleted_at       TIMESTAMP    NULL,
  PRIMARY KEY (id)
  -- No index on `rut`, matching production: the RUT lookup strips punctuation in SQL, which
  -- defeats any index anyway. Fine at ~1.200 rows; worth revisiting if the table grows.
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- `id` and `empresa_id` are INT here, not BIGINT UNSIGNED. That is what production has, and it is
-- why the entity maps this table's key as an Integer and its company reference as a plain column
-- rather than an association: `empresa_id INT` cannot be joined type-safely to
-- `empresas.id BIGINT UNSIGNED`.
CREATE TABLE datos_plan (
  id                INT          NOT NULL AUTO_INCREMENT,
  empresa_id        INT          NULL,
  plan_id           INT          NULL,
  monto_plan        INT          NULL,
  monto_hardware    INT          NULL,
  fecha_vencimiento DATE         NULL,
  periodo_plan      VARCHAR(45)  NULL,
  periodo_days      INT          NULL DEFAULT 30,
  estado            INT          NULL,
  created_at        TIMESTAMP    NULL,
  updated_at        TIMESTAMP    NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Two details that are easy to get wrong and both matter:
--   * There is NO `email_verified_at`. The framework default suggests one; production does not have
--     it, and mapping it makes startup validation fail.
--   * `deleted_at` exists and is populated: hundreds of users are logically deleted, and the User
--     entity filters on it so a terminated employee cannot authenticate.
-- `empresa_id` is a signed BIGINT while `empresas.id` is unsigned — also production's, left as is.
CREATE TABLE users (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name              VARCHAR(255) NOT NULL,
  email             VARCHAR(255) NOT NULL,
  password          VARCHAR(255) NOT NULL,
  remember_token    VARCHAR(100) NULL,
  empresa_id        BIGINT       NULL,
  status            TINYINT      NULL DEFAULT 1,
  isUvo             TINYINT      NULL DEFAULT 0,
  nivelUvo          VARCHAR(65)  NULL,
  created_at        TIMESTAMP    NULL,
  updated_at        TIMESTAMP    NULL,
  deleted_at        TIMESTAMP    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY users_email_unique (email),
  KEY users_empresa_id_foreign (empresa_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The existing payment audit trail. `amount` is DECIMAL(8,2) because that is what production has
-- today: it caps at 999.999,99, which is why V2 widens it. The largest amount currently recorded is
-- 847.516,00 across 2.683 rows — under the old cap, consistent with the expensive plans never
-- having been chargeable through it.
--
-- `user_id` is NOT NULL, also matching production: a null must fail before the customer is charged,
-- not after. `comprobante_tipo` is an ENUM, not a varchar.
CREATE TABLE suscriptor_payments (
  id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  amount                      DECIMAL(8,2) NOT NULL,
  user_id                     BIGINT UNSIGNED NOT NULL,
  empresa_id                  BIGINT UNSIGNED NOT NULL,
  plan_id                     INT          NULL,
  periodo_plan                VARCHAR(50)  NULL,
  notes                       VARCHAR(255) NULL,
  factura_id                  BIGINT UNSIGNED NULL,
  fecha_pago                  DATETIME     NULL,
  fecha_vencimiento_original  DATE         NULL,
  responsable                 INT          NULL,
  comprobante_path            VARCHAR(500) NULL,
  comprobante_tipo            ENUM('jpg','jpeg','png','pdf') NULL,
  comprobante_nombre_original VARCHAR(255) NULL,
  ip_address                  VARCHAR(45)  NULL,
  user_agent                  VARCHAR(500) NULL,
  created_at                  TIMESTAMP    NULL,
  updated_at                  TIMESTAMP    NULL,
-- The two foreign keys below are REAL constraints in production, not just indexes, and they are
-- reproduced here so a local test hits them too. They constrain the payment write path: the
-- `user_id` recorded on a payment must be an existing user row. That is the detail that decides
-- whether a dedicated service account for module-originated payments has to be created in `users`
-- before the first payment — otherwise the insert fails on the foreign key, after the card has
-- already been charged.
--
-- Production has a third one, factura_id -> facturas.id. It cannot be declared here because
-- `facturas` is outside this mirror; the column stays nullable and this module never writes it, so
-- the plain index is enough locally.
  PRIMARY KEY (id),
  KEY suscriptor_payments_empresa_id_fecha_pago_index (empresa_id, fecha_pago),
  KEY suscriptor_payments_factura_id_foreign (factura_id),
  KEY suscriptor_payments_fecha_pago_index (fecha_pago),
  KEY suscriptor_payments_plan_id_index (plan_id),
  CONSTRAINT suscriptor_payments_empresa_id_foreign FOREIGN KEY (empresa_id) REFERENCES empresas (id),
  CONSTRAINT suscriptor_payments_user_id_foreign FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
