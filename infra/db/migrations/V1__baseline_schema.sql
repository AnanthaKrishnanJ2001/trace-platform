-- Baseline PostgreSQL schema (TDD §4.1). This is the first migration in the
-- project, so it lays down every table from the TDD's "complete and ready to
-- run" DDL, not just `exceptions` — `exceptions.invoice_id/po_id/vendor_id`
-- are FOREIGN KEYs into invoices/purchase_orders/vendors, so those tables
-- must exist first for this migration to apply cleanly.
--
-- Deviation from TDD §4.1 text (flagged, not silently applied): the TDD
-- document lists `exceptions.type` and `exceptions.severity` as NOT NULL,
-- but TASK-01 (exception ingestion) is explicitly scoped to persist a row
-- with status=DETECTED and leave type/severity/root_cause null until TASK-02
-- (classification) runs. A NOT NULL constraint here would make TASK-01
-- un-implementable without inventing placeholder values, so both columns
-- are nullable — matching the DDL given in the TASK-01 spec. Recommend
-- reconciling the TDD text to say the same, since TASK-02 is intentionally
-- a separate follow-on task.

CREATE TABLE vendors (
    vendor_id    VARCHAR(20) PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    category     VARCHAR(50),
    risk_level   VARCHAR(10) NOT NULL DEFAULT 'LOW',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE purchase_orders (
    po_id            VARCHAR(20) PRIMARY KEY,
    vendor_id        VARCHAR(20) NOT NULL REFERENCES vendors(vendor_id),
    approved_amount  NUMERIC(14,2) NOT NULL CHECK (approved_amount >= 0),
    currency         CHAR(3) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
    invoice_id      VARCHAR(20) PRIMARY KEY,
    vendor_id       VARCHAR(20) NOT NULL REFERENCES vendors(vendor_id),
    po_id           VARCHAR(20) REFERENCES purchase_orders(po_id),
    invoice_amount  NUMERIC(14,2) NOT NULL CHECK (invoice_amount >= 0),
    invoice_date    DATE NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    transaction_id  VARCHAR(20) PRIMARY KEY,
    vendor_id       VARCHAR(20) NOT NULL REFERENCES vendors(vendor_id),
    amount          NUMERIC(14,2) NOT NULL,
    txn_date        DATE NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE exceptions (
    exception_id            VARCHAR(20) PRIMARY KEY,
    source_system            VARCHAR(50) NOT NULL,
    type                     VARCHAR(50),
    severity                 VARCHAR(10),
    status                   VARCHAR(30) NOT NULL DEFAULT 'DETECTED',
    invoice_id               VARCHAR(20) REFERENCES invoices(invoice_id),
    po_id                    VARCHAR(20) REFERENCES purchase_orders(po_id),
    vendor_id                VARCHAR(20) REFERENCES vendors(vendor_id),
    root_cause_label         VARCHAR(80),
    root_cause_confidence    SMALLINT CHECK (root_cause_confidence BETWEEN 0 AND 100),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_exceptions_status ON exceptions(status);
CREATE INDEX idx_exceptions_vendor ON exceptions(vendor_id);

-- Backs app-generated exception_id values of the form EXC-NNNNN (TDD §3.1).
CREATE SEQUENCE exception_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE incidents (
    incident_id  VARCHAR(20) PRIMARY KEY,
    exception_id VARCHAR(20) REFERENCES exceptions(exception_id),
    root_cause   VARCHAR(80) NOT NULL,
    resolution   TEXT NOT NULL,
    decision     TEXT,
    evidence     JSONB NOT NULL,
    owner        VARCHAR(50),
    outcome      VARCHAR(20) NOT NULL,
    resolved_at  TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_traces (
    id               BIGSERIAL PRIMARY KEY,
    investigation_id VARCHAR(30) NOT NULL,
    exception_id    VARCHAR(20) NOT NULL REFERENCES exceptions(exception_id),
    agent            VARCHAR(30) NOT NULL,
    step             VARCHAR(30) NOT NULL,
    tool_name        VARCHAR(50),
    tool_input       JSONB,
    tool_output      JSONB,
    occurred_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agent_traces_exception ON agent_traces(exception_id);

CREATE TABLE tickets (
    ticket_id    VARCHAR(20) PRIMARY KEY,
    exception_id VARCHAR(20) NOT NULL REFERENCES exceptions(exception_id),
    action_type  VARCHAR(50) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
    id           BIGSERIAL PRIMARY KEY,
    exception_id VARCHAR(20) NOT NULL REFERENCES exceptions(exception_id),
    actor        VARCHAR(50) NOT NULL,
    action       VARCHAR(50) NOT NULL,
    details      JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_exception ON audit_logs(exception_id);

CREATE TABLE users (
    user_id        VARCHAR(20) PRIMARY KEY,
    email          VARCHAR(255) UNIQUE NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(20) NOT NULL DEFAULT 'ANALYST',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
