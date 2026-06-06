-- ============================================================
-- Database Task Queue — initial load jobs
-- Covers the last 3 years in monthly time frames.
-- job_type allows multiple entity types to share one queue.
-- Runs automatically on first container startup (02_initial_jobs.sql).
-- ============================================================

CREATE SEQUENCE IF NOT EXISTS initial_job_id_seq INCREMENT BY 5 START WITH 1;

CREATE TABLE IF NOT EXISTS initial_job (
    id              BIGINT          PRIMARY KEY DEFAULT nextval('initial_job_id_seq'),

    -- Which entity type this job processes (e.g. 'INVOICE', 'RETURN_ORDER')
    job_type        VARCHAR(50)     NOT NULL,

    -- Time window this job is responsible for (both inclusive)
    start_date      DATE            NOT NULL,
    end_date        DATE            NOT NULL,

    -- Human-readable key, e.g. "2024-01" for Jan 2024
    job_key         VARCHAR(20)     NOT NULL,

    -- Lifecycle
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    -- PENDING → RUNNING → COMPLETED | FAILED

    -- Populated when a worker picks up the job
    worker_id       VARCHAR(100),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,

    -- Result
    records_fetched INTEGER,
    error_message   TEXT,
    retry_count     INTEGER         NOT NULL DEFAULT 0,

    -- Audit (created_by/updated_by managed by Spring Data @CreatedBy / @LastModifiedBy)
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),

    -- One job per (type + month) — prevents duplicate seeding
    CONSTRAINT initial_job_type_key_unique UNIQUE (job_type, job_key),

    CONSTRAINT initial_job_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT initial_job_dates_check
        CHECK (end_date > start_date)
);

-- Worker query: grab next PENDING job for a specific type, ordered chronologically
CREATE INDEX IF NOT EXISTS idx_initial_job_type_status_start
    ON initial_job (job_type, status, start_date);

-- ── Seed: one INVOICE job per calendar month for the last 3 years ──
INSERT INTO initial_job (job_type, start_date, end_date, job_key, status)
SELECT
    'INVOICE'                                   AS job_type,
    gs::DATE                                    AS start_date,
    (gs + INTERVAL '1 month')::DATE             AS end_date,
    TO_CHAR(gs, 'YYYY-MM')                      AS job_key,
    'PENDING'                                   AS status
FROM generate_series(
    DATE_TRUNC('month', CURRENT_DATE - INTERVAL '3 years'),
    DATE_TRUNC('month', CURRENT_DATE),
    INTERVAL '1 month'
) AS gs
ON CONFLICT (job_type, job_key) DO NOTHING;
