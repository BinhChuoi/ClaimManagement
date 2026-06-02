-- ============================================================
-- Minimal schema for testing — core fields + FK relationships
-- Table order matters: parent table must exist before child
-- ============================================================

-- Enable query tracking (required for response time metrics)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ── Parent ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS invoices_header (
    invoice_number  BIGINT          PRIMARY KEY,
    customer_code   VARCHAR(20),
    currency        VARCHAR(3),
    invoice_date    DATE,
    invoice_amount  NUMERIC(15, 2),
    sales_org       VARCHAR(10),
    country_code    VARCHAR(5),
    synced_at       TIMESTAMP       DEFAULT NOW(),
    -- JPA audit fields (managed by BaseEntity) — TIMESTAMPTZ always stores UTC
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50)
);

-- ── Child: line items (many per invoice) ─────────────────────
-- ON DELETE CASCADE: delete header → all its line items deleted too
CREATE TABLE IF NOT EXISTS invoices_line_items (
    id              BIGSERIAL       PRIMARY KEY,
    invoice_number  BIGINT          NOT NULL
                        REFERENCES invoices_header (invoice_number)
                        ON DELETE CASCADE,
    line_item_no    VARCHAR(10),
    description     VARCHAR(100),
    item_category   VARCHAR(10),
    invoice_quantity    NUMERIC(10, 3),
    submitted_quantity  NUMERIC(10, 3),
    net_value       NUMERIC(15, 2),
    synced_at       TIMESTAMP       DEFAULT NOW(),
    -- JPA audit fields (managed by BaseEntity) — TIMESTAMPTZ always stores UTC
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50)
);

-- ── Sync history (one record per cron run) ──────────────────
CREATE TABLE IF NOT EXISTS sync_history (
    id                  BIGSERIAL       PRIMARY KEY,
    from_invoice_number BIGINT          NOT NULL,       -- where this run started
    to_invoice_number   BIGINT,                         -- highest invoice number synced
    status              VARCHAR(20)     NOT NULL,       -- RUNNING / COMPLETED / FAILED
    started_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,                    -- null until finished
    records_synced      INTEGER,                        -- row count pulled from Athena
    error_message       TEXT                            -- populated on FAILED
);

-- ── Child: return orders (optional, one per invoice) ─────────
-- ON DELETE SET NULL: delete header → return order stays but loses invoice link
CREATE TABLE IF NOT EXISTS return_orders (
    return_order_request_id VARCHAR(50)  PRIMARY KEY,
    invoice_number          BIGINT
                                REFERENCES invoices_header (invoice_number)
                                ON DELETE SET NULL,
    return_order_status     VARCHAR(20),
    return_order_no         VARCHAR(20),
    synced_at               TIMESTAMPTZ DEFAULT NOW(),
    -- JPA audit fields (managed by BaseEntity)
    created_at              TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(50),
    updated_by              VARCHAR(50)
);
