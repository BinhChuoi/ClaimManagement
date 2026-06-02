"""
Benchmark script — captures PostgreSQL metrics for 64MB vs 256MB comparison.
Runs while Spring Boot sync executes chunk 1 (2022 H1 only).

Usage:
  python benchmark.py --label 64MB
  python benchmark.py --label 256MB
  python benchmark.py --compare        # print side-by-side table
"""

import psycopg2
import time
import json
import os
import argparse
from datetime import datetime

DB = dict(host="localhost", port=5432, dbname="invoices", user="postgres", password="test123")
RESULTS_DIR = "benchmark_results"

SNAPSHOT_QUERY = """
SELECT
  -- Buffer pressure
  b.buffers_backend,
  b.buffers_clean,
  b.buffers_checkpoint,
  b.checkpoints_req,
  b.checkpoints_timed,

  -- Cache hit ratio
  ROUND(d.blks_hit * 100.0 / NULLIF(d.blks_hit + d.blks_read, 0), 4) AS hit_ratio_pct,
  d.blks_read,
  d.blks_hit,

  -- Throughput
  d.xact_commit,
  d.xact_rollback,
  d.tup_inserted,
  d.tup_updated,
  d.tup_fetched,

  -- Write latency (from pg_stat_statements)
  (SELECT COALESCE(AVG(mean_exec_time), 0)
   FROM pg_stat_statements
   WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'invoices')
     AND (query ILIKE 'insert%' OR query ILIKE 'update%' OR query ILIKE 'delete%')
  ) AS write_avg_ms,
  (SELECT COALESCE(MAX(max_exec_time), 0)
   FROM pg_stat_statements
   WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'invoices')
     AND (query ILIKE 'insert%' OR query ILIKE 'update%' OR query ILIKE 'delete%')
  ) AS write_max_ms,

  -- Read latency
  (SELECT COALESCE(AVG(mean_exec_time), 0)
   FROM pg_stat_statements
   WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'invoices')
     AND query ILIKE 'select%'
  ) AS read_avg_ms,
  (SELECT COALESCE(MAX(max_exec_time), 0)
   FROM pg_stat_statements
   WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'invoices')
     AND query ILIKE 'select%'
  ) AS read_max_ms,

  -- Row counts
  (SELECT COUNT(*) FROM invoices_header)      AS headers,
  (SELECT COUNT(*) FROM invoices_line_items)  AS line_items,

  -- Sync status
  (SELECT status FROM sync_history ORDER BY started_at DESC LIMIT 1) AS sync_status,
  (SELECT setting || unit FROM pg_settings WHERE name = 'shared_buffers') AS shared_buffers

FROM pg_stat_bgwriter b, pg_stat_database d
WHERE d.datname = 'invoices'
"""


def reset_stats(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT pg_stat_reset()")
        cur.execute("SELECT pg_stat_reset_shared('bgwriter')")
    conn.commit()
    print("[OK] Stats reset")


def snapshot(conn, elapsed_sec):
    with conn.cursor() as cur:
        cur.execute(SNAPSHOT_QUERY)
        cols = [d[0] for d in cur.description]
        row  = cur.fetchone()
    data = dict(zip(cols, row))
    data["elapsed_sec"] = elapsed_sec
    data["ts"] = datetime.now().strftime("%H:%M:%S")
    return data


def run(label):
    os.makedirs(RESULTS_DIR, exist_ok=True)
    outfile = os.path.join(RESULTS_DIR, f"{label.replace(' ', '_')}.json")

    conn = psycopg2.connect(**DB)
    conn.autocommit = True

    print(f"\n{'='*55}")
    print(f" BENCHMARK: {label}")
    print(f"{'='*55}")
    reset_stats(conn)

    print(f"\n{'Time':>8} {'HitRatio':>9} {'BufBknd':>8} {'WrAvgMs':>8} {'WrMaxMs':>8} {'LiRows':>10} {'Status'}")
    print("-" * 70)

    snapshots = []
    start     = time.time()
    tick      = 0

    try:
        while True:
            time.sleep(10)
            elapsed = time.time() - start
            s = snapshot(conn, round(elapsed, 1))
            snapshots.append(s)

            print(f"{s['ts']:>8} "
                  f"{float(s['hit_ratio_pct'] or 0):>8.2f}% "
                  f"{int(s['buffers_backend'] or 0):>8,} "
                  f"{float(s['write_avg_ms'] or 0):>8.4f} "
                  f"{float(s['write_max_ms'] or 0):>8.3f} "
                  f"{int(s['line_items'] or 0):>10,} "
                  f"{s['sync_status'] or '-'}")

            status = s.get("sync_status", "")
            if status == "COMPLETED" and int(s.get("line_items") or 0) > 100000:
                print(f"\n[DONE] Sync COMPLETED at {elapsed:.1f}s")
                break
            if status == "FAILED":
                print(f"\n[FAIL] Sync FAILED at {elapsed:.1f}s")
                break

            tick += 1

    except KeyboardInterrupt:
        print("\n[STOPPED]")

    # ── Summary ───────────────────────────────────────────────
    total_elapsed = time.time() - start
    final = snapshots[-1] if snapshots else {}
    start_snap = snapshots[0] if snapshots else {}

    summary = {
        "label":             label,
        "shared_buffers":    str(final.get("shared_buffers", "?")),
        "total_elapsed_sec": round(total_elapsed, 1),
        "final_headers":     int(final.get("headers") or 0),
        "final_line_items":  int(final.get("line_items") or 0),
        "final_hit_ratio":   float(final.get("hit_ratio_pct") or 0),
        "peak_buffers_backend": max(int(s.get("buffers_backend") or 0) for s in snapshots),
        "final_buffers_backend": int(final.get("buffers_backend") or 0),
        "checkpoints_req":   int(final.get("checkpoints_req") or 0),
        "write_avg_ms":      float(final.get("write_avg_ms") or 0),
        "write_max_ms":      float(final.get("write_max_ms") or 0),
        "read_avg_ms":       float(final.get("read_avg_ms") or 0),
        "read_max_ms":       float(final.get("read_max_ms") or 0),
        "snapshots":         snapshots,
    }

    with open(outfile, "w") as f:
        json.dump(summary, f, indent=2, default=str)

    print(f"\nResults saved: {outfile}")
    print_summary(summary)
    conn.close()


def print_summary(s):
    sep = "=" * 50
    print(f"\n{sep}")
    print(f" SUMMARY: {s['label']} ({s['shared_buffers']})")
    print(sep)
    print(f"  Total time         : {s['total_elapsed_sec']}s")
    print(f"  Rows synced        : {s['final_line_items']:,} line items")
    print(f"  Hit ratio          : {s['final_hit_ratio']:.2f}%")
    print(f"  Peak buffers_backend: {s['peak_buffers_backend']:,}")
    print(f"  checkpoints_req    : {s['checkpoints_req']}")
    print(f"  Write avg latency  : {s['write_avg_ms']:.4f} ms")
    print(f"  Write max latency  : {s['write_max_ms']:.3f} ms")
    print(f"  Read avg latency   : {s['read_avg_ms']:.4f} ms")
    print(f"  Read max latency   : {s['read_max_ms']:.3f} ms")
    print(sep)


def compare():
    files = [f for f in os.listdir(RESULTS_DIR) if f.endswith(".json")]
    if not files:
        print("No benchmark results found. Run with --label first.")
        return

    results = []
    for f in sorted(files):
        with open(os.path.join(RESULTS_DIR, f)) as fp:
            results.append(json.load(fp))

    if len(results) < 2:
        print_summary(results[0])
        return

    a, b = results[0], results[1]
    sep = "=" * 65
    print(f"\n{sep}")
    print(f" COMPARISON: {a['label']} vs {b['label']}")
    print(sep)
    print(f"  {'Metric':<28} {a['label']:>12} {b['label']:>12}  {'Winner':>8}")
    print("-" * 65)

    def row(label, ka, kb, lower_better=True, fmt="{:.2f}"):
        va, vb = a.get(ka, 0), b.get(kb or ka, 0)
        if lower_better:
            winner = a['label'] if va < vb else (b['label'] if vb < va else "tie")
        else:
            winner = a['label'] if va > vb else (b['label'] if vb > va else "tie")
        print(f"  {label:<28} {fmt.format(va):>12} {fmt.format(vb):>12}  {winner:>8}")

    row("Total time (s)",        "total_elapsed_sec",  None, lower_better=True,  fmt="{:.1f}")
    row("Peak buffers_backend",  "peak_buffers_backend", None, lower_better=True, fmt="{:,.0f}")
    row("checkpoints_req",       "checkpoints_req",    None, lower_better=True,  fmt="{:.0f}")
    row("Hit ratio (%)",         "final_hit_ratio",    None, lower_better=False, fmt="{:.2f}")
    row("Write avg latency (ms)","write_avg_ms",       None, lower_better=True,  fmt="{:.4f}")
    row("Write max latency (ms)","write_max_ms",       None, lower_better=True,  fmt="{:.3f}")
    row("Read avg latency (ms)", "read_avg_ms",        None, lower_better=True,  fmt="{:.4f}")
    row("Read max latency (ms)", "read_max_ms",        None, lower_better=True,  fmt="{:.3f}")
    print(sep)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--label",   help="Label for this run e.g. '64MB'")
    parser.add_argument("--compare", action="store_true", help="Compare saved results")
    args = parser.parse_args()

    if args.compare:
        compare()
    elif args.label:
        run(args.label)
    else:
        print("Usage: python benchmark.py --label 64MB  OR  --compare")
