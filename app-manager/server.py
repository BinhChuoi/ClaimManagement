"""
CMP App Manager — lightweight HTTP management server (no external deps).
Runs on port 8088. Starts/stops the Spring Boot JAR, toggles initial-load flag.

Usage:
  python server.py
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import subprocess, json, os, threading, time, sys
from collections import deque

# ── Config ────────────────────────────────────────────────────────────────────
PORT        = 8088
JAR_PATH    = os.path.abspath(os.path.join(os.path.dirname(__file__),
                  "..", "cmp", "build", "libs", "cmp-0.0.1-SNAPSHOT.jar"))
JAVA_CMD    = "java"
COMPOSE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MAX_REPLICAS = 10

# ── State ─────────────────────────────────────────────────────────────────────
state = {
    "process":      None,
    "initial_load": True,
    "log":          deque(maxlen=200),
    "lock":         threading.Lock(),
}

# ── Process management ────────────────────────────────────────────────────────

def start_app():
    with state["lock"]:
        if state["process"] and state["process"].poll() is None:
            return {"ok": False, "msg": "Already running"}
        args = [
            JAVA_CMD, "-jar", JAR_PATH,
            f"--app.enable-initial-load={'true' if state['initial_load'] else 'false'}",
        ]
        state["log"].clear()
        state["log"].append(f"[manager] Starting: {' '.join(args)}")
        try:
            p = subprocess.Popen(
                args,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
            )
            state["process"] = p
            threading.Thread(target=_tail_logs, args=(p,), daemon=True).start()
            return {"ok": True, "pid": p.pid}
        except Exception as e:
            state["log"].append(f"[manager] Error: {e}")
            return {"ok": False, "msg": str(e)}

def stop_app():
    with state["lock"]:
        p = state["process"]
        if not p or p.poll() is not None:
            return {"ok": False, "msg": "Not running"}
        p.terminate()
        try:
            p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
        state["log"].append("[manager] Process stopped")
        return {"ok": True}

def get_status():
    p = state["process"]
    running = p is not None and p.poll() is None
    return {
        "running":     running,
        "pid":         p.pid if running else None,
        "initialLoad": state["initial_load"],
        "jarPath":     JAR_PATH,
        "jarExists":   os.path.exists(JAR_PATH),
    }

def _tail_logs(proc):
    for line in proc.stdout:
        state["log"].append(line.rstrip())
    state["log"].append("[manager] Process exited")

# ── Docker scale ──────────────────────────────────────────────────────────────

def get_instances():
    """List running cmp-app Docker containers with health status."""
    try:
        r = subprocess.run(
            ["docker", "ps", "--filter", "name=cmp-cmp-app",
             "--format", "{{json .}}"],
            capture_output=True, text=True, timeout=10
        )
        instances = []
        for line in r.stdout.strip().split("\n"):
            if not line.strip():
                continue
            d = json.loads(line)
            raw_status = d.get("Status", "")
            if "(healthy)" in raw_status:
                health = "healthy"
            elif "(unhealthy)" in raw_status:
                health = "unhealthy"
            elif "health: starting" in raw_status:
                health = "starting"
            else:
                health = "no healthcheck"
            instances.append({
                "name":   d.get("Names", ""),
                "status": raw_status,
                "health": health,
            })
        return instances
    except Exception as e:
        return [{"error": str(e)}]

def scale_instances(n):
    """Scale cmp-app service to n replicas via docker compose."""
    n = max(0, min(MAX_REPLICAS, n))
    try:
        r = subprocess.run(
            ["docker", "compose", "up", "-d",
             "--scale", f"cmp-app={n}", "--no-recreate"],
            capture_output=True, text=True, timeout=120,
            cwd=COMPOSE_DIR
        )
        msg = (r.stdout + r.stderr).strip()
        return {"ok": r.returncode == 0, "replicas": n, "msg": msg}
    except Exception as e:
        return {"ok": False, "replicas": n, "msg": str(e)}

# ── HTTP handler ──────────────────────────────────────────────────────────────

DASHBOARD_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>CMP Manager</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: Inter, sans-serif; background: #111217; color: #d0d0d0; font-size: 13px; }
header { background: #181b22; padding: 10px 16px; display: flex; align-items: center; gap: 14px; border-bottom: 1px solid #2e2e3e; }
header h1 { font-size: 15px; color: #fff; font-weight: 600; flex: 1; }
.status-pill { display: flex; align-items: center; gap: 6px; padding: 4px 12px; border-radius: 20px; background: #1c1e24; font-size: 12px; font-weight: 600; }
.dot { width: 9px; height: 9px; border-radius: 50%; }
.dot.running { background: #27ae60; box-shadow: 0 0 6px #27ae60; }
.dot.stopped { background: #e74c3c; }
nav { display: flex; gap: 2px; padding: 0 16px; background: #181b22; border-bottom: 1px solid #2e2e3e; }
.tab { padding: 9px 16px; cursor: pointer; font-size: 13px; color: #888; border-bottom: 2px solid transparent; transition: .15s; }
.tab:hover { color: #ccc; }
.tab.active { color: #fff; border-color: #1f60c4; }
.pane { display: none; padding: 14px 16px; }
.pane.active { display: block; }
.card { background: #1c1e24; border-radius: 6px; padding: 14px; margin-bottom: 12px; }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.btn { padding: 6px 16px; border: none; border-radius: 5px; cursor: pointer; font-size: 12px; font-weight: 600; transition: opacity .15s; }
.btn:hover { opacity: .85; } .btn:disabled { opacity: .4; cursor: not-allowed; }
.btn-start  { background: #27ae60; color: #fff; }
.btn-stop   { background: #e74c3c; color: #fff; }
.btn-sm     { background: #2d2d3e; color: #ccc; padding: 4px 10px; }
.btn-restart { background: #e67e22; color: #fff; padding: 4px 10px; }
.btn-kill    { background: #c0392b; color: #fff; padding: 4px 10px; }
.switch { position: relative; width: 40px; height: 22px; }
.switch input { display: none; }
.slider { position: absolute; inset: 0; background: #444; border-radius: 22px; cursor: pointer; transition: .25s; }
.slider:before { content:""; position:absolute; width:16px; height:16px; left:3px; bottom:3px; background:#fff; border-radius:50%; transition:.25s; }
input:checked + .slider { background: #27ae60; }
input:checked + .slider:before { transform: translateX(18px); }
.toggle-lbl { font-size: 12px; cursor: pointer; }
.log-box { background: #0a0c10; border-radius: 5px; padding: 10px; height: 300px; overflow-y: auto; font-family: monospace; font-size: 11px; line-height: 1.55; color: #b0b0b0; }
.log-box .err { color: #e74c3c; } .log-box .mgr { color: #3498db; font-style: italic; } .log-box .warn { color: #f39c12; }
table { width: 100%; border-collapse: collapse; }
th { text-align: left; padding: 6px 8px; background: #15171e; color: #888; font-size: 11px; text-transform: uppercase; letter-spacing: .4px; border-bottom: 1px solid #2e2e3e; }
td { padding: 7px 8px; border-bottom: 1px solid #1a1a2a; vertical-align: middle; }
tr:hover td { background: #1a1c26; }
.badge { display: inline-block; padding: 2px 7px; border-radius: 3px; font-size: 11px; font-weight: 600; }
.COMPLETED,.COMPLETED { background: #1a5c1a; color: #7adc7a; }
.FAILED    { background: #5c1a1a; color: #dc7a7a; }
.STARTED,.STARTING { background: #1a3a5c; color: #7ab8dc; }
.RUNNING   { background: #1a3a5c; color: #7ab8dc; }
.PENDING   { background: #2a2a1a; color: #dcdc7a; }
.STOPPED   { background: #3a3a1a; color: #dcdc7a; }
.msg { display:inline-block; padding: 3px 10px; border-radius: 4px; background: #1a3a5c; color: #7eb8f7; font-size: 12px; min-width: 120px; }
.msg.err { background: #3a1a1a; color: #dc7a7a; }
.section-hdr { font-size: 12px; font-weight: 600; color: #aaa; margin-bottom: 8px; }
.info { font-size: 11px; color: #666; margin-top: 6px; }
.stat-row { display: flex; gap: 10px; margin-bottom: 12px; }
.stat { background: #15171e; border-radius: 5px; padding: 10px 16px; flex: 1; text-align: center; }
.stat .val { font-size: 22px; font-weight: 700; color: #fff; }
.stat .lbl { font-size: 11px; color: #666; margin-top: 2px; }
.COMPLETED .val { color: #27ae60; } .FAILED .val { color: #e74c3c; } .PENDING .val { color: #f39c12; } .RUNNING .val, .STARTED .val { color: #3498db; }
</style>
</head>
<body>
<header>
  <h1>CMP Manager</h1>
  <span class="msg" id="msg"></span>
</header>

<nav>
  <div class="tab active" onclick="switchTab('app')">App Control</div>
  <div class="tab" onclick="switchTab('log')">Live Log</div>
</nav>

<!-- ── App Control ─────────────────────────────────────────────────────────── -->
<div class="pane active" id="pane-app">
  <div class="stat-row" id="stats-row">
    <div class="stat COMPLETED"><div class="val" id="st-done">-</div><div class="lbl">Jobs Completed</div></div>
    <div class="stat PENDING">  <div class="val" id="st-pend">-</div><div class="lbl">Jobs Pending</div></div>
    <div class="stat STARTED">  <div class="val" id="st-run">-</div> <div class="lbl">Jobs Running</div></div>
    <div class="stat FAILED">   <div class="val" id="st-fail">-</div><div class="lbl">Jobs Failed</div></div>
  </div>

  <div class="card">
    <div class="section-hdr" style="margin-bottom:12px">Docker Instances</div>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" style="background:#444;color:#fff;font-size:20px;width:34px;height:34px;padding:0;line-height:1" onclick="scaleBy(-1)">&#8722;</button>
      <span id="replica-count" style="font-size:22px;font-weight:700;color:#fff;min-width:28px;text-align:center">-</span>
      <button class="btn" style="background:#1f60c4;color:#fff;font-size:20px;width:34px;height:34px;padding:0;line-height:1" onclick="scaleBy(1)">+</button>
      <span style="font-size:12px;color:#666">replicas (max 10)</span>
      <span class="msg" id="scale-msg" style="display:none"></span>
    </div>
    <div id="instance-list" style="display:flex;flex-direction:column;gap:6px"></div>
  </div>

  <div class="card">
    <div class="section-hdr">Configuration</div>
    <div class="row">
      <label class="switch">
        <input type="checkbox" id="toggle-il2" onchange="toggleIL()" checked>
        <span class="slider"></span>
      </label>
      <label class="toggle-lbl" for="toggle-il2">Enable Initial Load — when OFF the app runs delta sync only</label>
    </div>
    <div class="info" id="jar-info"></div>
  </div>
</div>

<!-- ── Live Log ────────────────────────────────────────────────────────────── -->
<div class="pane" id="pane-log">
  <div class="row" style="margin-bottom:8px">
    <button class="btn btn-sm" onclick="clearLog()">Clear</button>
    <label style="font-size:12px;color:#888"><input type="checkbox" id="autoscroll" checked> Auto-scroll</label>
    <span id="log-count" style="font-size:11px;color:#555"></span>
  </div>
  <div class="log-box" id="log"></div>
</div>

<script>
const APP = 'http://localhost:8080/admin/batch';
let lastLogLen = 0, currentTab = 'app';

function switchTab(t) {
  currentTab = t;
  document.querySelectorAll('.tab').forEach((el,i) => el.classList.toggle('active', ['app','log'][i]===t));
  document.querySelectorAll('.pane').forEach((el,i) => el.classList.toggle('active', ['pane-app','pane-log'][i]==='pane-'+t));
}

// ── App status ──────────────────────────────────────────────────────────────
function refreshStatus() {
  fetch('/api/status').then(r=>r.json()).then(s=>{
    const r = s.running;
    document.getElementById('app-dot').className = 'dot '+(r?'running':'stopped');
    document.getElementById('app-status').textContent = r ? 'Running' : 'Stopped';
    document.getElementById('app-pid').textContent = r ? 'PID '+s.pid : '';
    document.getElementById('btn-start').disabled = r;
    document.getElementById('btn-stop').disabled  = !r;
    ['toggle-il','toggle-il2'].forEach(id=>{
      const el=document.getElementById(id); if(el) el.checked=s.initialLoad;
    });
    document.getElementById('jar-info').textContent =
      'JAR: '+s.jarPath+' | '+(s.jarExists?'Ready':'NOT FOUND — run bootJar first');
  });
}

// ── Initial job stats ────────────────────────────────────────────────────────
function refreshStats() {
  if(!document.getElementById('pane-app').classList.contains('active')) return;
  fetch(APP+'/initial-jobs').then(r=>r.json()).then(jobs=>{
    const cnt = s => jobs.filter(j=>j.status===s).length;
    document.getElementById('st-done').textContent = cnt('COMPLETED');
    document.getElementById('st-pend').textContent = cnt('PENDING');
    document.getElementById('st-run').textContent  = cnt('RUNNING');
    document.getElementById('st-fail').textContent = cnt('FAILED');
  }).catch(()=>{});
}

// ── Logs ─────────────────────────────────────────────────────────────────────
function refreshLog() {
  if(currentTab!=='log') return;
  fetch('/api/log').then(r=>r.json()).then(lines=>{
    if(lines.length===lastLogLen) return;
    lastLogLen = lines.length;
    document.getElementById('log-count').textContent = lines.length+' lines';
    const box = document.getElementById('log');
    box.innerHTML = lines.map(l=>{
      const cls = l.startsWith('[manager]')?'mgr':l.includes(' ERROR ')||l.includes('FAILED')?'err':l.includes(' WARN ')?'warn':'';
      return '<div class="'+cls+'">'+esc(l)+'</div>';
    }).join('');
    if(document.getElementById('autoscroll').checked) box.scrollTop=box.scrollHeight;
  });
}

// ── App control ───────────────────────────────────────────────────────────────
function startApp(){
  msg('Starting...');
  fetch('/api/start',{method:'POST'}).then(r=>r.json()).then(d=>{
    msg(d.ok?'Started (PID '+d.pid+')':'Error: '+d.msg, !d.ok);
    refreshStatus();
  });
}
function stopApp(){
  msg('Stopping...');
  fetch('/api/stop',{method:'POST'}).then(r=>r.json()).then(d=>{
    msg(d.ok?'Stopped':'Error: '+d.msg, !d.ok);
    refreshStatus();
  });
}
function toggleIL(){
  fetch('/api/toggle',{method:'POST'}).then(r=>r.json()).then(d=>{
    msg('Initial load: '+(d.initialLoad?'ON':'OFF'));
    ['toggle-il','toggle-il2'].forEach(id=>{
      const el=document.getElementById(id); if(el) el.checked=d.initialLoad;
    });
  });
}

// ── Job manager ───────────────────────────────────────────────────────────────
function loadJobs(){
  // Batch executions
  fetch(APP+'/jobs').then(r=>r.json()).then(jobs=>{
    document.getElementById('batch-tbody').innerHTML = !jobs.length
      ? '<tr><td colspan="7" style="text-align:center;padding:16px;color:#555">No executions</td></tr>'
      : jobs.map(j=>`<tr>
          <td>#${j.executionId}</td>
          <td style="font-family:monospace;font-size:11px;max-width:180px;overflow:hidden;text-overflow:ellipsis" title="${j.jobIds||''}">${(j.jobIds||'-').substring(0,30)}...</td>
          <td><span class="badge ${j.status}">${j.status}</span></td>
          <td style="font-size:11px">${j.startTime?new Date(j.startTime).toLocaleString():'-'}</td>
          <td style="font-size:11px">${j.endTime?new Date(j.endTime).toLocaleString():'-'}</td>
          <td style="text-align:center">${j.stepCount}</td>
          <td style="display:flex;gap:4px">
            ${j.status==='FAILED'?`<button class="btn btn-restart" onclick="restartJob(${j.executionId})">↺ Restart</button>`:''}
            ${(j.status==='STARTED'||j.status==='STARTING')?`<button class="btn btn-kill" onclick="stopJob(${j.executionId})">■ Stop</button>`:''}
          </td>
        </tr>`).join('');
  }).catch(()=>{
    document.getElementById('batch-tbody').innerHTML =
      '<tr><td colspan="7" style="text-align:center;padding:16px;color:#555">App not running</td></tr>';
  });
  // Initial jobs
  fetch(APP+'/initial-jobs').then(r=>r.json()).then(jobs=>{
    document.getElementById('init-tbody').innerHTML = !jobs.length
      ? '<tr><td colspan="7" style="text-align:center;padding:16px;color:#555">No jobs</td></tr>'
      : jobs.map(j=>{
          const dur = j.startedAt && j.completedAt
            ? Math.round((new Date(j.completedAt)-new Date(j.startedAt))/1000)+'s' : '-';
          return `<tr>
            <td>${j.jobKey}</td>
            <td><span class="badge ${j.status}">${j.status}</span></td>
            <td style="text-align:center">${j.recordsFetched!=null?j.recordsFetched:'-'}</td>
            <td style="font-size:11px">${j.startedAt?new Date(j.startedAt).toLocaleTimeString():'-'}</td>
            <td style="font-size:11px">${j.completedAt?new Date(j.completedAt).toLocaleTimeString():'-'}</td>
            <td>${dur}</td>
            <td>${j.retryCount}</td>
          </tr>`;
        }).join('');
  }).catch(()=>{
    document.getElementById('init-tbody').innerHTML =
      '<tr><td colspan="7" style="text-align:center;padding:16px;color:#555">App not running</td></tr>';
  });
}

function restartJob(id){
  setJobMsg('Restarting #'+id+'...');
  fetch(APP+'/restart/'+id,{method:'POST'}).then(r=>r.json())
    .then(d=>{ setJobMsg('Restarted! New ID: '+d.newExecutionId); loadJobs(); })
    .catch(e=>setJobMsg('Error: '+e, true));
}
function stopJob(id){
  setJobMsg('Stopping #'+id+'...');
  fetch(APP+'/stop/'+id,{method:'POST'}).then(r=>r.json())
    .then(d=>{ setJobMsg('Stopped #'+id); loadJobs(); })
    .catch(e=>setJobMsg('Error: '+e, true));
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function msg(m, err){
  const el=document.getElementById('msg');
  el.textContent=m; el.className='msg'+(err?' err':'');
}
function setJobMsg(m, err){
  const el=document.getElementById('job-msg');
  el.textContent=m; el.className='msg'+(err?' err':''); el.style.display='inline-block';
}
function clearLog(){ lastLogLen=0; document.getElementById('log').innerHTML=''; }
function esc(s){ return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

// ── Docker instances ──────────────────────────────────────────────────────────
let currentReplicas = 0;

function refreshInstances() {
  if(!document.getElementById('pane-app').classList.contains('active')) return;
  fetch('/api/instances').then(r=>r.json()).then(list=>{
    currentReplicas = list.length;
    document.getElementById('replica-count').textContent = currentReplicas;
    const healthColor = { healthy:'#27ae60', unhealthy:'#e74c3c', starting:'#f39c12', 'no healthcheck':'#666' };
    document.getElementById('instance-list').innerHTML = !list.length
      ? '<span style="color:#555;font-size:12px">No Docker containers running — use docker compose up --scale cmp-app=N</span>'
      : list.map(i=>`
        <div onclick="toggleLogs('${i.name}')"
             style="display:flex;align-items:center;gap:10px;padding:7px 10px;background:#15171e;border-radius:4px;cursor:pointer;user-select:none"
             title="Click to open logs in new tab">
          <div style="width:9px;height:9px;border-radius:50%;background:${healthColor[i.health]||'#666'};flex-shrink:0"></div>
          <span style="font-family:monospace;font-size:12px;flex:1">${i.name}</span>
          <span style="font-size:11px;color:#888">${i.status}</span>
          <span style="font-size:11px;padding:1px 7px;border-radius:3px;background:#1c1e24;color:${healthColor[i.health]||'#666'}">${i.health}</span>
          <span style="font-size:11px;color:#555">&#x2197; logs</span>
        </div>`).join('');
  }).catch(()=>{});
}

function toggleLogs(name) {
  window.open('/logs/' + name, '_blank');
}

function scaleBy(delta) {
  const target = Math.max(0, Math.min(10, currentReplicas + delta));
  setScaleMsg('Scaling to ' + target + ' replica(s)...');
  fetch('/api/scale', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({replicas: target})
  }).then(r=>r.json()).then(d=>{
    setScaleMsg(d.ok ? 'Scaled to ' + d.replicas + ' replica(s)' : 'Error: ' + d.msg, !d.ok);
    setTimeout(refreshInstances, 2000);
  }).catch(e=>setScaleMsg('Error: '+e, true));
}

function setScaleMsg(m, err) {
  const el=document.getElementById('scale-msg');
  el.textContent=m; el.className='msg'+(err?' err':''); el.style.display='inline-block';
}

// ── Polling ───────────────────────────────────────────────────────────────────
refreshStatus();
refreshStats();
refreshInstances();
setInterval(()=>{ refreshStatus(); refreshStats(); refreshLog(); refreshInstances(); }, 3000);
setInterval(()=>{ if(currentTab==='jobs') loadJobs(); }, 10000);
</script>
</body>
</html>
"""

class Handler(BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        pass  # suppress access logs

    def do_GET(self):
        if self.path == '/api/status':
            self._json(get_status())
        elif self.path == '/api/log':
            self._json(list(state["log"]))
        elif self.path == '/api/instances':
            self._json(get_instances())
        elif self.path.startswith('/api/container-logs/'):
            name = self.path.split('/api/container-logs/', 1)[1]
            try:
                r = subprocess.run(
                    ['docker', 'logs', '--tail', '500', name],
                    capture_output=True, text=True, timeout=10
                )
                lines = (r.stdout + r.stderr).split('\n')
                self._json(lines)
            except Exception as e:
                self._json([str(e)])
        elif self.path.startswith('/logs/'):
            name = self.path.split('/logs/', 1)[1]
            self._html(self._log_viewer(name))
        else:
            self._html(DASHBOARD_HTML)

    def do_POST(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        if self.path == '/api/start':
            self._json(start_app())
        elif self.path == '/api/stop':
            self._json(stop_app())
        elif self.path == '/api/toggle':
            with state["lock"]:
                state["initial_load"] = not state["initial_load"]
            self._json({"initialLoad": state["initial_load"]})
        elif self.path == '/api/scale':
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            data = json.loads(body) if body else {}
            self._json(scale_instances(data.get("replicas", 1)))
        else:
            self._json({"error": "unknown endpoint"})

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()

    def _json(self, data):
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, html):
        body = html.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _log_viewer(self, container_name):
        return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Logs — {container_name}</title>
<style>
* {{ box-sizing: border-box; margin: 0; padding: 0; }}
body {{ font-family: monospace; background: #0a0c10; color: #b0b0b0; font-size: 12px; }}
header {{ background: #181b22; padding: 10px 16px; display: flex; align-items: center;
          gap: 12px; border-bottom: 1px solid #2e2e3e; position: sticky; top: 0; z-index: 10; }}
header h2 {{ color: #fff; font-size: 13px; font-family: sans-serif; flex: 1; }}
.btn {{ padding: 4px 12px; border: none; border-radius: 4px; cursor: pointer;
        font-size: 12px; font-weight: 600; }}
.btn-refresh {{ background: #1f60c4; color: #fff; font-family: sans-serif; }}
.btn-clear   {{ background: #2d2d3e; color: #ccc; font-family: sans-serif; }}
.auto-lbl    {{ font-family: sans-serif; font-size: 12px; color: #888; }}
#log {{ padding: 10px 16px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; }}
.err  {{ color: #e74c3c; }} .warn {{ color: #f39c12; }} .info {{ color: #7eb8f7; }}
.mgr  {{ color: #3498db; font-style: italic; }}
#status {{ font-family: sans-serif; font-size: 11px; color: #555; }}
</style>
</head>
<body>
<header>
  <h2>&#x1F4CB; {container_name}</h2>
  <span id="status">Loading...</span>
  <button class="btn btn-refresh" onclick="load()">&#8635; Refresh</button>
  <button class="btn btn-clear"   onclick="clearLog()">Clear</button>
  <label class="auto-lbl"><input type="checkbox" id="auto" checked> Auto-refresh (5s)</label>
  <label class="auto-lbl"><input type="checkbox" id="scroll" checked> Auto-scroll</label>
</header>
<div id="log"></div>
<script>
const NAME = '{container_name}';
function esc(s) {{ return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }}
function cls(l) {{
  if (l.includes(' ERROR ') || l.includes('ERROR:') || l.includes('FAILED')) return 'err';
  if (l.includes(' WARN ')  || l.includes('WARN:'))  return 'warn';
  if (l.includes(' INFO ')  || l.includes('INFO:'))  return 'info';
  if (l.startsWith('[manager]')) return 'mgr';
  return '';
}}
function load() {{
  fetch('/api/container-logs/' + NAME)
    .then(r => r.json())
    .then(lines => {{
      document.getElementById('status').textContent = lines.length + ' lines';
      document.getElementById('log').innerHTML = lines
        .map(l => `<div class="${{cls(l)}}">${{esc(l)}}</div>`).join('');
      if (document.getElementById('scroll').checked)
        window.scrollTo(0, document.body.scrollHeight);
    }})
    .catch(e => {{ document.getElementById('status').textContent = 'Error: ' + e; }});
}}
function clearLog() {{ document.getElementById('log').innerHTML = ''; }}
load();
setInterval(() => {{ if (document.getElementById('auto').checked) load(); }}, 5000);
</script>
</body>
</html>"""


if __name__ == "__main__":
    if not os.path.exists(JAR_PATH):
        print(f"[manager] WARNING: JAR not found at {JAR_PATH}")
        print(f"[manager] Run: cd cmp && gradlew bootJar")
    print(f"[manager] Dashboard: http://localhost:{PORT}")
    print(f"[manager] JAR path : {JAR_PATH}")
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        stop_app()
        print("\n[manager] Shutdown")
