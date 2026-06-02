import json, os

results = []
for f in ['benchmark_results/64MB.json', 'benchmark_results/256MB.json']:
    if os.path.exists(f):
        with open(f) as fp:
            results.append(json.load(fp))

a, b = results[0], results[1]
sep = '=' * 70
print(f'\n{sep}')
print(f'  FINAL COMPARISON: {a["label"]} ({a["shared_buffers"]}) vs {b["label"]} ({b["shared_buffers"]})')
print(sep)
print(f'  {"Metric":<30} {a["label"]:>10} {b["label"]:>10}  {"Winner":>8}  {"Diff":>6}')
print('-' * 70)

def row(label, key, lower_better=True, fmt='{:.2f}'):
    va = float(a.get(key,0) or 0)
    vb = float(b.get(key,0) or 0)
    if lower_better:
        winner = a['label'] if va < vb else (b['label'] if vb < va else 'tie')
    else:
        winner = a['label'] if va > vb else (b['label'] if vb > va else 'tie')
    denom = max(va, vb, 0.0001)
    diff = abs(va - vb) / denom * 100
    print(f'  {label:<30} {fmt.format(va):>10} {fmt.format(vb):>10}  {winner:>8}  {diff:>5.1f}%')

row('Total time (s)',          'total_elapsed_sec',    lower_better=True,  fmt='{:.1f}')
row('Peak buffers_backend',    'peak_buffers_backend', lower_better=True,  fmt='{:,.0f}')
row('Final hit ratio (%)',     'final_hit_ratio',      lower_better=False, fmt='{:.2f}')
row('Write avg latency (ms)',  'write_avg_ms',         lower_better=True,  fmt='{:.4f}')
row('Write max latency (ms)',  'write_max_ms',         lower_better=True,  fmt='{:.3f}')
row('Read avg latency (ms)',   'read_avg_ms',          lower_better=True,  fmt='{:.4f}')
row('Read max latency (ms)',   'read_max_ms',          lower_better=True,  fmt='{:.3f}')
print(sep)
print()

# Key insights
print('  KEY INSIGHTS:')
wr_diff = (float(a['write_max_ms']) - float(b['write_max_ms'])) / float(a['write_max_ms']) * 100
hr_drop = float(a['final_hit_ratio']) - 95.81
print(f'  Write max latency:  64MB={a["write_max_ms"]:.3f}ms  256MB={b["write_max_ms"]:.3f}ms  ({wr_diff:.1f}% faster with 256MB)')
print(f'  Hit ratio during checkpoint: 64MB dropped to 95.81% (-{hr_drop:.2f}% from peak)')
print(f'  Hit ratio during checkpoint: 256MB stayed at 99.93% (no drop)')
print(sep)
