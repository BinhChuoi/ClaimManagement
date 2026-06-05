-- ============================================================
-- Core schema — invoices_header + invoices_line_items
-- Table order matters: parent before child
-- ============================================================

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
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50)
);

-- ── Child: line items (many per invoice) ─────────────────────
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
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50)
);
