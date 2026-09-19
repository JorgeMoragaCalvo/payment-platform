-- Demo data for LOCAL DEVELOPMENT ONLY. Never run this against any database that is not a throwaway:
-- it creates a live admin account with a well-known password.
--
-- Password for every account is "password". The hash is Laravel-style bcrypt ($2y$), which is the
-- exact format the production user table holds and what BCryptPasswordEncoder must accept as-is.
--
-- Apply with:
--   mysql -u uvopos -puvopos_local uvopos_local < src/main/resources/db/demo/seed.sql
--
-- The test suite empties every table before each case, so this has to be re-applied after
-- `mvnw test` whenever you want to use the app in the browser again.

SET @pw = '$2y$10$1ihj6iZMccVmpyIk89o5DeXP9K8TcKCPX1Jf7rsiDkWfhSrGuTMtq';

DELETE FROM webpay_transactions;
DELETE FROM bank_movements;
DELETE FROM bank_statements;
DELETE FROM suscriptor_payments;
DELETE FROM datos_plan;
DELETE FROM empresas;
DELETE FROM users;

-- Staff: sees /payment-alert and /conciliacion-bancaria.
INSERT INTO users (name, email, password, empresa_id, status, role, created_at, updated_at)
VALUES ('Admin Demo', 'admin@example.test', @pw, NULL, 1, 'admin', NOW(), NOW());

-- One company per payment status, due dates relative to today so the statuses stay true.
INSERT INTO empresas (rut, RazonSocial, nombre_fantasia, proximoPago, tipoPlan, estado, created_at, updated_at) VALUES
  ('76543210-3', 'Comercial Andes SpA',        'Comercial Andes SpA',   DATE_ADD(CURDATE(), INTERVAL 2 DAY),  'Mensual', '1', NOW(), NOW()),
  ('77353398-9', 'Distribuidora Central Ltda', 'Distribuidora Central', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'Anual',   '1', NOW(), NOW()),
  ('76111222-3', 'Servicios Lomas SpA',        'Servicios Lomas',       DATE_ADD(CURDATE(), INTERVAL 40 DAY), 'Mensual', '1', NOW(), NOW()),
  ('96588860-4', 'Ferretería El Tornillo',     'El Tornillo',           DATE_SUB(CURDATE(), INTERVAL 1 DAY),  'Mensual', '1', NOW(), NOW()),
  ('78901234-6', 'Panadería Los Robles',       'Los Robles',            DATE_SUB(CURDATE(), INTERVAL 20 DAY), 'Menusal', '0', NOW(), NOW());

INSERT INTO datos_plan (empresa_id, plan_id, monto_plan, monto_hardware, fecha_vencimiento, periodo_plan, periodo_days, estado, created_at, updated_at)
SELECT id, 7, 35000, 0, proximoPago, tipoPlan, 30, 1, NOW(), NOW() FROM empresas;

-- One customer login per company: demo<empresaId>@example.test.
INSERT INTO users (name, email, password, empresa_id, status, role, created_at, updated_at)
SELECT CONCAT('Usuario ', nombre_fantasia), CONCAT('demo', id, '@example.test'), @pw, id, 1, NULL, NOW(), NOW()
FROM empresas;

SELECT u.email, u.role, e.nombre_fantasia AS empresa, e.proximoPago, e.estado
FROM users u LEFT JOIN empresas e ON e.id = u.empresa_id
ORDER BY u.id;
