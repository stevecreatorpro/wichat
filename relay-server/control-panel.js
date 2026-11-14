// WiChat Relay Control: a local web page to start/stop the dev relay and show the address
// each device should use. Run `node control-panel.js` (or double-click "Start Relay Control.bat");
// it opens http://127.0.0.1:8099 in your browser. Only reachable from this PC.
//
//   --no-open   don't open the browser (for tests)

const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');
const { spawn, execFile } = require('node:child_process');

const PANEL_PORT = Number(process.env.PANEL_PORT || 8099);
const TOKEN = crypto.randomBytes(16).toString('hex');
const ADB = path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');

let relay = null; // { child, port, startedAt }
let lastExit = null;
const log = []; // { n, time, text }
let logCounter = 0;

function addLog(text) {
  for (const line of String(text).split(/\r?\n/)) {
    if (!line.trim()) continue;
    log.push({ n: ++logCounter, time: new Date().toTimeString().slice(0, 8), text: line });
  }
  if (log.length > 500) log.splice(0, log.length - 500);
}

function lanAddresses() {
  const result = [];
  for (const [name, entries] of Object.entries(os.networkInterfaces())) {
    for (const e of entries || []) {
      if (e.family === 'IPv4' && !e.internal && !e.address.startsWith('169.254.')) result.push({ name, address: e.address });
    }
  }
  return result;
}

function checkHealth(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/health', timeout: 1500 }, (res) => {
      res.resume();
      resolve(res.statusCode === 200);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

async function startRelay(port) {
  if (relay) return { ok: false, error: 'Relay is already running' };
  if (await checkHealth(port)) return { ok: false, error: `Something is already answering on port ${port}` };
  const child = spawn(process.execPath, ['server.js'], {
    cwd: __dirname,
    env: { ...process.env, PORT: String(port) },
    windowsHide: true,
  });
  relay = { child, port, startedAt: Date.now() };
  lastExit = null;
  addLog(`Starting relay on port ${port}...`);
  child.stdout.on('data', addLog);
  child.stderr.on('data', (d) => addLog(`ERROR: ${d}`));
  child.on('exit', (code, signal) => {
    if (relay && relay.child === child) {
      addLog(signal ? 'Relay stopped.' : `Relay exited (code ${code}). Is port ${port} already in use?`);
      lastExit = { code, signal };
      relay = null;
    }
  });
  return { ok: true };
}

function stopRelay() {
  if (!relay) return { ok: false, error: 'Relay is not running' };
  relay.child.kill();
  return { ok: true };
}

function connectUsb(port) {
  return new Promise((resolve) => {
    if (!fs.existsSync(ADB)) return resolve({ ok: false, error: 'adb not found. Install Android Studio platform-tools.' });
    execFile(ADB, ['reverse', `tcp:${port}`, `tcp:${port}`], { timeout: 10_000 }, (err, stdout, stderr) => {
      if (err) {
        const why = (stderr || err.message).trim();
        addLog(`USB connect failed: ${why}`);
        return resolve({ ok: false, error: `${why}. Is the phone plugged in with USB debugging allowed?` });
      }
      addLog(`USB phone connected: on the phone use http://localhost:${port}`);
      resolve({ ok: true });
    });
  });
}

async function status(port) {
  const p = relay ? relay.port : port;
  const healthy = await checkHealth(p);
  return {
    state: relay ? (healthy ? 'running' : 'starting') : healthy ? 'external' : 'stopped',
    port: p,
    links: [
      { device: 'This PC (browser test)', url: `http://localhost:${p}`, note: '' },
      { device: 'USB phone', url: `http://localhost:${p}`, note: 'Press "Connect USB phone" first' },
      { device: 'Android emulator on this PC', url: `http://10.0.2.2:${p}`, note: '' },
      ...lanAddresses().map((a) => ({
        device: `Phone on the same network (${a.name})`,
        url: `http://${a.address}:${p}`,
        note: 'Blocked on some campus and public Wi-Fi',
      })),
    ],
  };
}

function sendJson(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

function readJson(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (c) => { raw += c; if (raw.length > 10_000) req.destroy(); });
    req.on('end', () => { try { resolve(raw ? JSON.parse(raw) : {}); } catch { resolve({}); } });
  });
}

const validPort = (p) => Number.isInteger(p) && p >= 1024 && p <= 65535;

const server = http.createServer(async (req, res) => {
  // Only this PC's browser may drive the panel: reject other Host headers (DNS rebinding)
  // and API calls without the per-run token (other websites can't read it).
  const host = (req.headers.host || '').split(':')[0];
  if (host !== '127.0.0.1' && host !== 'localhost') return sendJson(res, 403, { error: 'Forbidden' });
  const url = new URL(req.url, 'http://panel');

  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(PAGE.replace('__TOKEN__', TOKEN));
  }
  if (!url.pathname.startsWith('/api/')) return sendJson(res, 404, { error: 'Not found' });
  if (req.headers['x-panel-token'] !== TOKEN) return sendJson(res, 403, { error: 'Bad token' });

  const port = Number(url.searchParams.get('port') || 8080);
  if (req.method === 'GET' && url.pathname === '/api/status') {
    const since = Number(url.searchParams.get('since') || 0);
    return sendJson(res, 200, { ...(await status(port)), log: log.filter((l) => l.n > since) });
  }
  if (req.method === 'POST') {
    const body = await readJson(req);
    const p = Number(body.port);
    if (!validPort(p)) return sendJson(res, 400, { ok: false, error: 'Port must be 1024 to 65535' });
    if (url.pathname === '/api/start') return sendJson(res, 200, await startRelay(p));
    if (url.pathname === '/api/stop') return sendJson(res, 200, stopRelay());
    if (url.pathname === '/api/usb') return sendJson(res, 200, await connectUsb(p));
  }
  return sendJson(res, 404, { error: 'Not found' });
});

function openBrowser(target) {
  if (process.platform === 'win32') execFile('cmd', ['/c', 'start', '', target]);
  else execFile(process.platform === 'darwin' ? 'open' : 'xdg-open', [target]);
}

function shutdown() {
  if (relay) relay.child.kill();
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.log(`Relay Control is already open at http://127.0.0.1:${PANEL_PORT}`);
    if (!process.argv.includes('--no-open')) openBrowser(`http://127.0.0.1:${PANEL_PORT}`);
    process.exit(0);
  }
  throw e;
});

server.listen(PANEL_PORT, '127.0.0.1', () => {
  const panel = `http://127.0.0.1:${PANEL_PORT}`;
  console.log(`WiChat Relay Control is open at ${panel}`);
  console.log('Keep this window open. Closing it stops the relay.');
  if (process.env.PANEL_PRINT_TOKEN) console.log(`token=${TOKEN}`);
  if (!process.argv.includes('--no-open')) openBrowser(panel);
});

const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>WiChat Relay Control</title>
<style>
  :root { --bg:#f6f7f9; --card:#fff; --text:#1c1e21; --muted:#5f6670; --border:#dfe2e6; --accent:#1b6ef3; --ok:#2ea043; --warn:#d29922; --off:#8b949e; --danger:#cf222e; }
  @media (prefers-color-scheme: dark) { :root { --bg:#111418; --card:#1a1e23; --text:#e6e8eb; --muted:#9aa3ad; --border:#2d333b; --accent:#4c8dff; } }
  * { box-sizing: border-box; }
  body { margin:0; font:15px/1.5 "Segoe UI", system-ui, sans-serif; background:var(--bg); color:var(--text); }
  main { max-width:820px; margin:0 auto; padding:24px 16px 40px; }
  h1 { font-size:20px; font-weight:600; margin:0 0 16px; }
  .card { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:16px 18px; margin-bottom:16px; }
  .row { display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
  .dot { width:14px; height:14px; border-radius:50%; background:var(--off); flex:none; }
  .status { font-size:18px; font-weight:600; flex:1; min-width:200px; }
  label { color:var(--muted); }
  input { width:90px; padding:6px 8px; border:1px solid var(--border); border-radius:8px; background:var(--card); color:var(--text); font:inherit; }
  button { padding:7px 16px; border-radius:8px; border:1px solid var(--border); background:var(--card); color:var(--text); font:inherit; cursor:pointer; }
  button.primary { background:var(--accent); border-color:var(--accent); color:#fff; }
  button:disabled { opacity:.45; cursor:default; }
  table { width:100%; border-collapse:collapse; }
  td { padding:9px 6px; border-top:1px solid var(--border); vertical-align:middle; }
  tr:first-child td { border-top:none; }
  .url { font-family:Consolas, monospace; font-size:15px; }
  .note { color:var(--muted); font-size:13px; }
  .hint { color:var(--muted); font-size:13px; margin:10px 0 0; }
  pre { margin:0; height:220px; overflow:auto; font:12.5px/1.45 Consolas, monospace; white-space:pre-wrap; }
  .msg { min-height:20px; font-size:14px; margin-top:8px; }
  .msg.err { color:var(--danger); }
  @media (max-width:560px) { td.note-cell { display:none; } }
</style>
</head>
<body>
<main>
  <h1>WiChat Relay Control</h1>

  <section class="card">
    <div class="row">
      <span class="dot" id="dot"></span>
      <span class="status" id="status">Checking...</span>
      <label for="port">Port</label>
      <input id="port" type="number" min="1024" max="65535" value="8080">
      <button class="primary" id="start">Start</button>
      <button id="stop">Stop</button>
    </div>
    <div class="msg" id="msg"></div>
  </section>

  <section class="card">
    <div class="row" style="justify-content:space-between">
      <strong>Relay server address to type in the app</strong>
      <button id="usb">Connect USB phone</button>
    </div>
    <table><tbody id="links"></tbody></table>
    <p class="hint">These addresses work only while this PC is on and the relay is running. To reach people miles away, deploy the relay online (see DEPLOY.md).</p>
  </section>

  <section class="card">
    <strong>Relay log</strong>
    <pre id="log"></pre>
  </section>
</main>
<script>
const TOKEN = '__TOKEN__';
const $ = (id) => document.getElementById(id);
let since = 0;
let state = 'stopped';

function api(method, path, body) {
  return fetch(path, {
    method,
    headers: { 'X-Panel-Token': TOKEN, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  }).then((r) => r.json());
}

function showMessage(text, isError) {
  $('msg').textContent = text || '';
  $('msg').className = 'msg' + (isError ? ' err' : '');
}

const LABELS = {
  running: ['var(--ok)', (p) => 'Relay is running on port ' + p],
  starting: ['var(--warn)', () => 'Starting relay...'],
  external: ['var(--ok)', (p) => 'A relay is already running on port ' + p + ' (started elsewhere)'],
  stopped: ['var(--off)', () => 'Relay is stopped'],
};

function render(s) {
  state = s.state;
  const [color, text] = LABELS[s.state];
  $('dot').style.background = color;
  $('status').textContent = text(s.port);
  $('start').disabled = s.state !== 'stopped';
  $('stop').disabled = !(s.state === 'running' || s.state === 'starting');
  $('port').disabled = s.state === 'running' || s.state === 'starting';

  const rows = s.links.map((l) =>
    '<tr><td>' + l.device + '</td><td class="url">' + l.url + '</td>' +
    '<td class="note note-cell">' + l.note + '</td>' +
    '<td style="text-align:right"><button data-url="' + l.url + '">Copy</button></td></tr>');
  const html = rows.join('');
  if ($('links').dataset.html !== html) { $('links').innerHTML = html; $('links').dataset.html = html; }

  if (s.log.length) {
    const pre = $('log');
    const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 4;
    pre.textContent += s.log.map((l) => '[' + l.time + '] ' + l.text).join('\\n') + '\\n';
    since = s.log[s.log.length - 1].n;
    if (atBottom) pre.scrollTop = pre.scrollHeight;
  }
}

function refresh() {
  return api('GET', '/api/status?since=' + since + '&port=' + $('port').value)
    .then(render)
    .catch(() => {
      $('dot').style.background = 'var(--danger)';
      $('status').textContent = 'Control panel closed. Run "Start Relay Control.bat" again.';
      $('start').disabled = $('stop').disabled = true;
    });
}

async function act(path, okText) {
  showMessage('');
  const res = await api('POST', path, { port: Number($('port').value) });
  if (!res.ok) showMessage(res.error, true); else if (okText) showMessage(okText);
  refresh();
}

$('start').onclick = () => act('/api/start');
$('stop').onclick = () => act('/api/stop');
$('usb').onclick = () => act('/api/usb', 'USB phone connected. On the phone, use http://localhost:' + $('port').value);
$('links').onclick = (e) => {
  const url = e.target.dataset && e.target.dataset.url;
  if (!url) return;
  navigator.clipboard.writeText(url).then(
    () => { e.target.textContent = 'Copied'; setTimeout(() => (e.target.textContent = 'Copy'), 1200); },
    () => showMessage('Copy failed. Select the address and copy it by hand.', true));
};

refresh();
setInterval(refresh, 1000);
</script>
</body>
</html>`;
