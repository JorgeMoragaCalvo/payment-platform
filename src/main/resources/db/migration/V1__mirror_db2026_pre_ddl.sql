-- Local mirror of the four tables this module reads that already exist in the production database,
-- reproducing them AS THEY ARE TODAY — before the coordinated schema change.
--
-- This is only the columns the payment feature touches, not full production parity: the real
-- `empresas` and `users` tables are far wider. Nothing here is ever applied to production; these
-- tables already exist there.
--
-- Deliberately NOT included, because V2 adds them exactly as the production change will:
--   * users.role
--   * suscriptor_payments.external_reference
--   * suscriptor_payments.amount widened to decimal(12,2)
--
-- No foreign keys anywhere, matching production: `imported_by`, `reconciled_by` and `user_id` all
-- point at `users`, which has logical deletes, and an FK would block deleting a user.

CREATE TABLE empresas (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rut              VARCHAR(255) NOT NULL,
  RazonSocial      VARCHAR(255) NULL,
  nombre_fantasia  VARCHAR(250) NULL,
  proximoPago      DATE         NULL,
  tipoPlan         VARCHAR(45)  NULL,
  estado           VARCHAR(45)  NOT NULL DEFAULT '1',
  created_at       TIMESTAMP    NULL,
  updated_at       TIMESTAMP    NULL,
  deleted_at       TIMESTAMP    NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE datos_plan (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  empresa_id        BIGINT UNSIGNED NULL,
  plan_id           INT          NULL,
  monto_plan        INT          NULL,
  monto_hardware    INT          NULL,
  fecha_vencimiento DATE         NULL,
  periodo_plan      VARCHAR(45)  NULL,
  periodo_days      INT          NOT NULL DEFAULT 30,
  estado            INT          NOT NULL DEFAULT 1,
  created_at        TIMESTAMP    NULL,
  updated_at        TIMESTAMP    NULL,
  PRIMARY KEY (id),
  KEY datos_plan_empresa_id_index (empresa_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- `deleted_at` matters and is easy to miss: production has it, with hundreds of logically deleted
-- rows, and the User entity filters on it so a terminated employee cannot authenticate. The
-- previous local mirror did not declare it, which meant local runs could not reproduce that at all.
CREATE TABLE users (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name              VARCHAR(255) NOT NULL,
  email             VARCHAR(255) NOT NULL,
  email_verified_at TIMESTAMP    NULL,
  password          VARCHAR(255) NOT NULL,
  remember_token    VARCHAR(100) NULL,
  empresa_id        BIGINT UNSIGNED NULL,
  status            TINYINT      NOT NULL DEFAULT 1,
  created_at        TIMESTAMP    NULL,
  updated_at        TIMESTAMP    NULL,
  deleted_at        TIMESTAMP    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY users_email_unique (email),
  KEY users_empresa_id_index (empresa_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The existing payment audit trail. `amount` is decimal(8,2) here because that is what production
-- has today: it caps at 999.999,99 while 28 active plans charge more, which is precisely why V2
-- widens it. `user_id` is NOT NULL, also matching production — a null must fail before the
-- customer is charged, not after.
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
  comprobante_tipo            VARCHAR(10)  NULL,
  comprobante_nombre_original VARCHAR(255) NULL,
  ip_address                  VARCHAR(45)  NULL,
  user_agent                  VARCHAR(500) NULL,
  created_at                  TIMESTAMP    NULL,
  updated_at                  TIMESTAMP    NULL,
  PRIMARY KEY (id),
  KEY suscriptor_payments_empresa_id_index (empresa_id),
  KEY suscriptor_payments_user_id_index (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
