"""
Benchmark runner — starts benchmark THEN Spring Boot in the correct order.

Usage:
  python run_benchmark.py --label 64MB
  python run_benchmark.py --label 256MB
  python run_benchmark.py --compare
"""

import subprocess
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
  b.buffers_backend,
  b.buffers_clean,
  b.buffers_checkpoint,
  b.checkpoints_req,
  b.checkpoints_timed,
  ROUND(d.blks_hit * 100.0 / NULLIF(d.blks_hit + d.blks_read, 0), 4) AS hit_ratio_pct,
  d.blks_read,
  d.blks_hit,
  d.xact_commit,
  d.tup_inserted,
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
  (SELECT COUNT(*) FROM invoices_header)     AS headers,
  (SELECT COUNT(*) FROM invoices_line_items) AS line_items,
  (SELECT COALESCE(status, 'none') FROM sync_history ORDER BY started_at DESC LIMIT 1) AS sync_status,
  (SELECT setting || unit FROM pg_settings WHERE name = 'shared_buffers') AS shared_buffers
FROM pg_stat_bgwriter b, pg_stat_database d
WHERE d.datname = 'invoices'
"""


def wait_for_db(max_wait=30):
    for _ in range(max_wait):
        try:
            conn = psycopg2.connect(**DB, connect_timeout=2)
            conn.close()
            return True
        except Exception:
            time.sleep(1)
    return False


def reset_stats(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT pg_stat_reset()")
        cur.execute("SELECT pg_stat_reset_shared('bgwriter')")
        cur.execute("DELETE FROM sync_history")
    conn.commit()
    print("[OK] Stats + sync_history reset")


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
    ts = datetime.now().strftime("%H%M%S")
    logfile = os.path.join(RESULTS_DIR, f"{label.replace(' ', '_')}_{ts}_run.log")

    print(f"\n{'='*60}")
    print(f" BENCHMARK: {label}")
    print(f"{'='*60}")

    # Step 1: connect and reset BEFORE Spring Boot starts
    print("[1] Connecting to DB...")
    if not wait_for_db():
        print("ERROR: Cannot connect to DB")
        return
    conn = psycopg2.connect(**DB)
    conn.autocommit = True
    reset_stats(conn)

    # Step 2: start Spring Boot
    print("[2] Starting Spring Boot...")
    sb = subprocess.Popen(
        ["cmd", "/c", "cd", "cmp", "&&", r".\gradlew.bat", "bootRun"],
        cwd=os.path.dirname(os.path.abspath(__file__)),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )
    print(f"    PID: {sb.pid}")

    # Step 3: wait for sync to start (cron fires at 10s)
    print("[3] Waiting for sync to start...")
    for _ in range(30):
        time.sleep(2)
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM sync_history WHERE status='RUNNING'")
                if cur.fetchone()[0] > 0:
                    break
        except Exception:
            pass
    print("    Sync RUNNING detected")

    # Step 4: capture metrics
    print(f"\n{'Time':>8} {'HitRatio':>9} {'BufBknd':>8} {'WrAvgMs':>9} {'WrMaxMs':>9} {'LiRows':>10} {'Status'}")
    print("-" * 75)

    snapshots = []
    start = time.time()

    with open(logfile, "w") as log:
        log.write(f"BENCHMARK: {label}\nStarted: {datetime.now()}\n\n")
        log.write(f"{'Time':>8} {'HitRatio':>9} {'BufBknd':>8} {'WrAvgMs':>9} {'WrMaxMs':>9} {'LiRows':>10} {'Status'}\n")
        log.write("-" * 75 + "\n")

        while True:
            time.sleep(10)
            elapsed = time.time() - start
            s = snapshot(conn, round(elapsed, 1))
            snapshots.append(s)

            line = (f"{s['ts']:>8} "
                    f"{float(s['hit_ratio_pct'] or 0):>8.2f}% "
                    f"{int(s['buffers_backend'] or 0):>8,} "
                    f"{float(s['write_avg_ms'] or 0):>9.4f} "
                    f"{float(s['write_max_ms'] or 0):>9.3f} "
                    f"{int(s['line_items'] or 0):>10,} "
                    f"{s['sync_status'] or '-'}")
            print(line)
            log.write(line + "\n")
            log.flush()

            status = s.get("sync_status", "")
            if status == "COMPLETED" and int(s.get("line_items") or 0) > 100000:
                total = time.time() - start
                msg = f"\n[DONE] Sync COMPLETED at {total:.1f}s"
                print(msg); log.write(msg + "\n")
                break
            if status == "FAILED":
                msg = f"\n[FAIL] Sync FAILED at {elapsed:.1f}s"
                print(msg); log.write(msg + "\n")
                break

    # Cleanup Spring Boot
    sb.terminate()

    # Save summary
    total_elapsed = time.time() - start
    final = snapshots[-1] if snapshots else {}

    summary = {
        "label":               label,
        "shared_buffers":      str(final.get("shared_buffers", "?")),
        "total_elapsed_sec":   round(total_elapsed, 1),
        "final_line_items":    int(final.get("line_items") or 0),
        "final_hit_ratio":     float(final.get("hit_ratio_pct") or 0),
        "peak_buffers_backend": max(int(s.get("buffers_backend") or 0) for s in snapshots),
        "checkpoints_req":     int(final.get("checkpoints_req") or 0),
        "write_avg_ms":        float(final.get("write_avg_ms") or 0),
        "write_max_ms":        float(final.get("write_max_ms") or 0),
        "read_avg_ms":         float(final.get("read_avg_ms") or 0),
        "read_max_ms":         float(final.get("read_max_ms") or 0),
        "snapshots":           snapshots,
    }
    with open(outfile, "w") as f:
        json.dump(summary, f, indent=2, default=str)

    print_summary(summary)
    conn.close()


def print_summary(s):
    sep = "=" * 52
    print(f"\n{sep}")
    print(f" SUMMARY: {s['label']} ({s['shared_buffers']})")
    print(sep)
    print(f"  Total time            : {s['total_elapsed_sec']}s")
    print(f"  Rows synced           : {s['final_line_items']:,}")
    print(f"  Hit ratio             : {s['final_hit_ratio']:.2f}%")
    print(f"  Peak buffers_backend  : {s['peak_buffers_backend']:,}")
    print(f"  checkpoints_req       : {s['checkpoints_req']}")
    print(f"  Write avg latency ms  : {s['write_avg_ms']:.4f}")
    print(f"  Write max latency ms  : {s['write_max_ms']:.3f}")
    print(f"  Read avg latency ms   : {s['read_avg_ms']:.4f}")
    print(f"  Read max latency ms   : {s['read_max_ms']:.3f}")
    print(sep)


def compare():
    files = sorted([f for f in os.listdir(RESULTS_DIR) if f.endswith(".json") and not f.startswith("64MB_") == False])
    results = []
    for f in files:
        try:
            with open(os.path.join(RESULTS_DIR, f)) as fp:
                results.append(json.load(fp))
        except Exception:
            pass

    if len(results) < 2:
        if results: print_summary(results[0])
        else: print("No results found.")
        return

    a, b = results[0], results[1]
    sep = "=" * 68
    print(f"\n{sep}")
    print(f" COMPARISON:  {a['label']} ({a['shared_buffers']})  vs  {b['label']} ({b['shared_buffers']})")
    print(sep)
    print(f"  {'Metric':<30} {a['label']:>10} {b['label']:>10}  {'Winner':>10}")
    print("-" * 68)

    def row(label, key, lower_better=True, fmt="{:.2f}"):
        va, vb = float(a.get(key, 0) or 0), float(b.get(key, 0) or 0)
        if lower_better:
            winner = a['label'] if va < vb else (b['label'] if vb < va else "tie")
        else:
            winner = a['label'] if va > vb else (b['label'] if vb > va else "tie")
        print(f"  {label:<30} {fmt.format(va):>10} {fmt.format(vb):>10}  {winner:>10}")

    row("Total time (s)",          "total_elapsed_sec",    lower_better=True,  fmt="{:.1f}")
    row("Peak buffers_backend",    "peak_buffers_backend", lower_better=True,  fmt="{:,.0f}")
    row("checkpoints_req",         "checkpoints_req",      lower_better=True,  fmt="{:.0f}")
    row("Hit ratio (%)",           "final_hit_ratio",      lower_better=False, fmt="{:.2f}")
    row("Write avg latency (ms)",  "write_avg_ms",         lower_better=True,  fmt="{:.4f}")
    row("Write max latency (ms)",  "write_max_ms",         lower_better=True,  fmt="{:.3f}")
    row("Read avg latency (ms)",   "read_avg_ms",          lower_better=True,  fmt="{:.4f}")
    row("Read max latency (ms)",   "read_max_ms",          lower_better=True,  fmt="{:.3f}")
    print(sep)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--label",   help="Label e.g. 64MB or 256MB")
    parser.add_argument("--compare", action="store_true")
    args = parser.parse_args()

    if args.compare:
        compare()
    elif args.label:
        run(args.label)
    else:
        print("Usage: python run_benchmark.py --label 64MB  OR  --compare")
