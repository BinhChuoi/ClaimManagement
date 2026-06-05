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

-- ── Return orders ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cc_return_order (
    id                      BIGINT          PRIMARY KEY,
    return_order_request_id VARCHAR(100)    NOT NULL UNIQUE,
    return_order_no         VARCHAR(20),
    return_order_status     VARCHAR(20)     NOT NULL,
    return_order_sap_type   VARCHAR(10),
    return_order_sap_reason VARCHAR(20),
    return_order_type       VARCHAR(20),
    return_order_error_msg  TEXT,
    claim_id                VARCHAR(100)    NOT NULL,
    shipping_method         VARCHAR(10),
    payload                 TEXT,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(50),
    updated_by              VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_ro_request_id ON cc_return_order (return_order_request_id);
CREATE INDEX IF NOT EXISTS idx_ro_claim_id   ON cc_return_order (claim_id);

-- ── Return order details (claim items / part IDs covered) ─────
CREATE TABLE IF NOT EXISTS cc_return_order_detail (
    id              BIGINT          PRIMARY KEY,
    return_order_id BIGINT          NOT NULL
                        REFERENCES cc_return_order (id)
                        ON DELETE CASCADE,
    link_id         VARCHAR(100)    NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_rod_return_order_id ON cc_return_order_detail (return_order_id);
CREATE INDEX IF NOT EXISTS idx_rod_link_id         ON cc_return_order_detail (link_id);

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
