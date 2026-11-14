const { test, before, after } = require('node:test');
const assert = require('node:assert');
const os = require('node:os');
const path = require('node:path');

process.env.DATA_FILE = path.join(os.tmpdir(), `wichat-relay-test-${process.pid}.json`);
process.env.BLOB_DIR = path.join(os.tmpdir(), `wichat-relay-test-blobs-${process.pid}`);
const { createServer } = require('../server');
const WebSocket = require('ws');

let server;
let base;

before(async () => {
  server = createServer();
  await new Promise((r) => server.listen(0, r));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

const bundle = (username) => ({
  username,
  registrationId: 42,
  identityKey: 'aWQ=',
  signedPreKey: { keyId: 1, publicKey: 'c3Br', signature: 'c2ln' },
  kyberPreKey: { keyId: 2, publicKey: 'a3li', signature: 'c2ln' },
  preKeys: [{ keyId: 10, publicKey: 'cDE=' }, { keyId: 11, publicKey: 'cDI=' }],
});

async function register(username) {
  const res = await fetch(`${base}/v1/register`, { method: 'POST', body: JSON.stringify(bundle(username)) });
  assert.strictEqual(res.status, 200);
  return (await res.json()).token;
}

function connect(token) {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/v1/ws`, { headers: { Authorization: `Bearer ${token}` } });
  const frames = [];
  const waiters = [];
  ws.on('message', (d) => {
    frames.push(JSON.parse(d.toString()));
    waiters.splice(0).forEach((w) => w());
  });
  const next = async (pred) => {
    for (;;) {
      const i = frames.findIndex(pred);
      if (i >= 0) return frames.splice(i, 1)[0];
      await new Promise((r) => waiters.push(r));
    }
  };
  return new Promise((resolve) => ws.on('open', () => resolve({ ws, next })));
}

test('register, fetch bundle, relay, queue and ack', async () => {
  const alice = await register('alice');
  const bob = await register('bob');

  const dup = await fetch(`${base}/v1/register`, { method: 'POST', body: JSON.stringify(bundle('alice')) });
  assert.strictEqual(dup.status, 409);

  const auth = { headers: { Authorization: `Bearer ${alice}` } };
  const b1 = await (await fetch(`${base}/v1/keys/bob`, auth)).json();
  const b2 = await (await fetch(`${base}/v1/keys/bob`, auth)).json();
  assert.strictEqual(b1.preKey.keyId, 10);
  assert.strictEqual(b2.preKey.keyId, 11, 'one-time prekeys are never handed out twice');
  assert.strictEqual((await fetch(`${base}/v1/keys/nobody`, auth)).status, 404);

  // Bob is offline: message is queued.
  const a = await connect(alice);
  const envelope = { envelopeId: 'e1', type: 'PREKEY_MESSAGE', recipientTag: 'bob', timestamp: 1, ciphertext: 'Y3Q=', senderId: 'mallory' };
  a.ws.send(JSON.stringify({ type: 'send', envelope }));
  assert.deepStrictEqual(await a.next((f) => f.type === 'server_ack'), { type: 'server_ack', envelopeId: 'e1' });

  // Bob connects: gets it, with the sender set by the relay, not the client.
  let b = await connect(bob);
  const delivered = await b.next((f) => f.type === 'envelope');
  assert.strictEqual(delivered.envelope.senderId, 'alice');
  b.ws.close();

  // Not acked yet, so it is redelivered on reconnect; after ack it is gone.
  b = await connect(bob);
  await b.next((f) => f.type === 'envelope');
  b.ws.send(JSON.stringify({ type: 'ack', envelopeIds: ['e1'] }));
  await new Promise((r) => setTimeout(r, 50));
  b.ws.close();

  a.ws.send(JSON.stringify({ type: 'send', envelope: { ...envelope, envelopeId: 'e2', recipientTag: 'ghost' } }));
  const err = await a.next((f) => f.type === 'error');
  assert.strictEqual(err.envelopeId, 'e2');
  a.ws.close();
});

test('encrypted blob upload and download', async () => {
  const owner = await register('carol');
  const other = await register('dave');
  const as = (token, init = {}) => ({ ...init, headers: { ...(init.headers || {}), Authorization: `Bearer ${token}` } });

  const created = await fetch(`${base}/v1/blobs`, as(owner, { method: 'POST', body: JSON.stringify({ size: 5, chunkCount: 2 }) }));
  assert.strictEqual(created.status, 200);
  const { blobId } = await created.json();

  const chunk = Buffer.from([1, 2, 3, 4, 5, 250]);
  assert.strictEqual((await fetch(`${base}/v1/blobs/${blobId}/chunks/0`, as(owner, { method: 'PUT', body: chunk }))).status, 204);
  assert.strictEqual((await fetch(`${base}/v1/blobs/${blobId}/chunks/0`, as(other, { method: 'PUT', body: chunk }))).status, 403,
    'only the uploader can write chunks');
  assert.strictEqual((await fetch(`${base}/v1/blobs/${blobId}/chunks/2`, as(owner, { method: 'PUT', body: chunk }))).status, 404,
    'index beyond chunkCount is rejected');
  const tooBig = Buffer.alloc(256 * 1024 + 17);
  assert.strictEqual((await fetch(`${base}/v1/blobs/${blobId}/chunks/1`, as(owner, { method: 'PUT', body: tooBig }))).status, 413);

  const got = await fetch(`${base}/v1/blobs/${blobId}/chunks/0`, as(other));
  assert.strictEqual(got.status, 200);
  assert.deepStrictEqual(Buffer.from(await got.arrayBuffer()), chunk);
  assert.strictEqual((await fetch(`${base}/v1/blobs/${blobId}/chunks/1`, as(other))).status, 404, 'not uploaded yet');
  assert.strictEqual((await fetch(`${base}/v1/blobs/${blobId}/chunks/0`)).status, 401);
  assert.strictEqual((await fetch(`${base}/v1/blobs/..%2F..%2Fdata.json/chunks/0`, as(other))).status, 404);
});
