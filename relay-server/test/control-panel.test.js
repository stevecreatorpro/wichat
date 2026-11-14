const { test, before, after } = require('node:test');
const assert = require('node:assert');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const PANEL_PORT = 18099;
const RELAY_PORT = 18080;
const BASE = `http://127.0.0.1:${PANEL_PORT}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'wichat-panel-'));
let panel;
let token;

before(async () => {
  panel = spawn(process.execPath, [path.join(__dirname, '..', 'control-panel.js'), '--no-open'], {
    env: {
      ...process.env,
      PANEL_PORT: String(PANEL_PORT),
      PANEL_PRINT_TOKEN: '1',
      DATA_FILE: path.join(tmp, 'data.json'),
      BLOB_DIR: path.join(tmp, 'blobs'),
    },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  token = await new Promise((resolve, reject) => {
    let out = '';
    panel.stdout.on('data', (d) => {
      out += d;
      const m = /token=([0-9a-f]+)/.exec(out);
      if (m) resolve(m[1]);
    });
    panel.on('exit', () => reject(new Error(`panel exited: ${out}`)));
  });
});

after(() => {
  panel.kill();
  fs.rmSync(tmp, { recursive: true, force: true });
});

const api = (method, p, body, headers = {}) =>
  fetch(BASE + p, {
    method,
    headers: { 'X-Panel-Token': token, ...headers },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(5000),
  });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForState(want) {
  for (let i = 0; i < 30; i++) {
    const s = await (await api('GET', `/api/status?port=${RELAY_PORT}`)).json();
    if (s.state === want) return s;
    await sleep(200);
  }
  throw new Error(`never reached ${want}`);
}

test('serves the page and guards the API', async () => {
  const page = await fetch(BASE + '/', { signal: AbortSignal.timeout(5000) });
  assert.strictEqual(page.status, 200);
  assert.match(await page.text(), /<title>WiChat Relay Control<\/title>/);

  const noToken = await fetch(BASE + '/api/status', { signal: AbortSignal.timeout(5000) });
  assert.strictEqual(noToken.status, 403, 'API needs the per-run token');

  const badPort = await (await api('POST', '/api/start', { port: 80 })).json();
  assert.strictEqual(badPort.ok, false);
});

test('starts the relay, lists addresses, stops it', async () => {
  const stopped = await waitForState('stopped');
  const urls = stopped.links.map((l) => l.url);
  assert.ok(urls.includes(`http://localhost:${RELAY_PORT}`));
  assert.ok(urls.includes(`http://10.0.2.2:${RELAY_PORT}`), 'emulator address listed');

  assert.deepStrictEqual(await (await api('POST', '/api/start', { port: RELAY_PORT })).json(), { ok: true });
  const running = await waitForState('running');
  assert.ok(running.log.some((l) => l.text.includes(`listening on :${RELAY_PORT}`)), 'relay log is shown');

  const health = await fetch(`http://127.0.0.1:${RELAY_PORT}/health`, { signal: AbortSignal.timeout(5000) });
  assert.strictEqual(health.status, 200, 'relay really answers');

  const again = await (await api('POST', '/api/start', { port: RELAY_PORT })).json();
  assert.strictEqual(again.ok, false, 'no second copy');

  assert.deepStrictEqual(await (await api('POST', '/api/stop', { port: RELAY_PORT })).json(), { ok: true });
  await waitForState('stopped');
});
