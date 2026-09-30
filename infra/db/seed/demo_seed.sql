-- Demo/reference data for local development and the demo — NOT a Flyway
-- migration, so it never runs automatically and never reaches a real
-- environment by accident. Idempotent: safe to run repeatedly.
--
--   docker compose -f infra/docker/docker-compose.yml exec -T postgres \
--       psql -U trace -d trace < infra/db/seed/demo_seed.sql
--
-- Covers the two investigation cases the engine's tests and the demo use:
--   * EXC references INV-10492 / PO-7821 / VEND-3391 — TDD §3.1's example:
--     invoice 52000 vs PO 45000, a 15.6% overage, well outside tolerance.
--   * INV-10493 / PO-7822 / VEND-4410 — invoice 45500 vs PO 45000, an
--     ~1.1% overage, inside the default 2% tolerance (TDD §3.2).
--
-- INC-8721 is the "similar past incident" from TDD §3.1/§3.2. This script
-- only inserts the incident row; its embedding (incident_embeddings) is
-- created by the engine's seeding script, since it needs the embedding model.
-- Policy chunks are seeded the same way.

INSERT INTO vendors (vendor_id, name, category, risk_level) VALUES
    ('VEND-3391', 'ABC Technologies', 'IT Services', 'LOW'),
    ('VEND-4410', 'Northwind Supplies', 'Office Supplies', 'LOW')
ON CONFLICT (vendor_id) DO NOTHING;

INSERT INTO purchase_orders (po_id, vendor_id, approved_amount, currency, status) VALUES
    ('PO-7821', 'VEND-3391', 45000.00, 'INR', 'OPEN'),
    ('PO-7822', 'VEND-4410', 45000.00, 'INR', 'OPEN')
ON CONFLICT (po_id) DO NOTHING;

INSERT INTO invoices (invoice_id, vendor_id, po_id, invoice_amount, invoice_date, status) VALUES
    ('INV-10492', 'VEND-3391', 'PO-7821', 52000.00, DATE '2026-09-15', 'PENDING'),
    ('INV-10493', 'VEND-4410', 'PO-7822', 45500.00, DATE '2026-09-15', 'PENDING')
ON CONFLICT (invoice_id) DO NOTHING;

INSERT INTO incidents (incident_id, exception_id, root_cause, resolution, decision, evidence, owner,
                       outcome, resolved_at) VALUES
    ('INC-8721', NULL, 'PO_INVOICE_AMOUNT_MISMATCH', 'Vendor issued corrected invoice.',
     'Payment blocked until correction.', '["PO-7100", "INV-9931"]'::jsonb, 'USR-204',
     'RESOLVED', TIMESTAMPTZ '2026-08-02 10:00:00+00')
ON CONFLICT (incident_id) DO NOTHING;
