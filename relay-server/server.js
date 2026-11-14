// WiChat development relay (Tier 3 in SYSTEM_DESIGN.md).
//
// It only ever sees public keys, encrypted envelopes and encrypted file chunks:
// it can route messages but cannot read them. Single process, state kept in
// memory and in data.json, file chunks under blobs/.
// NOT production-ready: no rate limiting, no phone verification, no TLS (put it
// behind a TLS reverse proxy), no FCM wake-ups yet.

const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8080);
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data.json');
const BLOB_DIR = process.env.BLOB_DIR || path.join(__dirname, 'blobs');
const QUEUE_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_BLOB_BYTES = Number(process.env.MAX_BLOB_BYTES || 2 * 1024 * 1024 * 1024);
// 256 KiB plaintext chunk + 16-byte AES-GCM tag (must match FileCipher.CHUNK_SIZE)
const MAX_CHUNK_BYTES = 256 * 1024 + 16;
const USERNAME = /^[a-z0-9._]{3,32}$/;
const BLOB_ID = /^[A-Za-z0-9_-]{22}$/;

// users: username -> { tokenHash, registrationId, identityKey, signedPreKey, kyberPreKey, preKeys[] }
// queues: username -> [envelope]   (kept until the recipient acks)
// blobs: blobId -> { owner, size, chunkCount, createdAt }   (bytes live in BLOB_DIR)
const state = loadState();
const online = new Map(); // username -> WebSocket

function loadState() {
  try {
    const loaded = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
    return { blobs: {}, ...loaded };
  } catch {
    return { users: {}, queues: {}, blobs: {} };
  }
}

const chunkPath = (blobId, index) => path.join(BLOB_DIR, blobId, String(index));

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.writeFileSync(DATA_FILE + '.tmp', JSON.stringify(state));
    fs.renameSync(DATA_FILE + '.tmp', DATA_FILE);
  }, 200);
}

const hashToken = (token) => crypto.createHash('sha256').update(token).digest('hex');

function authenticate(req) {
  const match = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  if (!match) return null;
  const hash = hashToken(match[1]);
  for (const [username, user] of Object.entries(state.users)) {
    if (crypto.timingSafeEqual(Buffer.from(user.tokenHash), Buffer.from(hash))) return username;
  }
  return null;
}

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(body === undefined ? '' : JSON.stringify(body));
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', (chunk) => {
      raw += chunk;
      if (raw.length > 1_000_000) reject(new Error('Body too large'));
    });
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch (e) {
        reject(e);
      }
    });
  });
}

// Resolves to null when the body exceeds `limit`; the rest is drained so we can still reply.
function readRaw(req, limit) {
  return new Promise((resolve, reject) => {
    const parts = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size <= limit) parts.push(chunk);
    });
    req.on('end', () => resolve(size > limit ? null : Buffer.concat(parts)));
    req.on('error', reject);
  });
}

const isKey = (k) => k && Number.isInteger(k.keyId) && typeof k.publicKey === 'string';
const isSignedKey = (k) => isKey(k) && typeof k.signature === 'string';

async function handleHttp(req, res) {
  const url = new URL(req.url, 'http://relay');
  try {
    if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true });

    if (req.method === 'POST' && url.pathname === '/v1/register') {
      const body = await readJson(req);
      const username = String(body.username || '').toLowerCase();
      if (!USERNAME.test(username)) return send(res, 400, { error: 'Invalid username' });
      if (state.users[username]) return send(res, 409, { error: 'Username already taken' });
      if (!Number.isInteger(body.registrationId) || typeof body.identityKey !== 'string' ||
          !isSignedKey(body.signedPreKey) || !isSignedKey(body.kyberPreKey) ||
          !Array.isArray(body.preKeys) || !body.preKeys.every(isKey)) {
        return send(res, 400, { error: 'Invalid key bundle' });
      }
      const token = crypto.randomBytes(32).toString('base64url');
      state.users[username] = {
        tokenHash: hashToken(token),
        registrationId: body.registrationId,
        identityKey: body.identityKey,
        signedPreKey: body.signedPreKey,
        kyberPreKey: body.kyberPreKey,
        preKeys: body.preKeys,
      };
      state.queues[username] = [];
      save();
      console.log(`registered @${username}`);
      return send(res, 200, { token });
    }

    const me = authenticate(req);
    if (!me) return send(res, 401, { error: 'Unauthorized' });

    if (req.method === 'GET' && url.pathname === '/v1/keys/count') {
      return send(res, 200, { count: state.users[me].preKeys.length });
    }

    if (req.method === 'POST' && url.pathname === '/v1/keys/prekeys') {
      const body = await readJson(req);
      if (!Array.isArray(body.preKeys) || !body.preKeys.every(isKey)) return send(res, 400, { error: 'Invalid prekeys' });
      state.users[me].preKeys.push(...body.preKeys);
      save();
      return send(res, 204);
    }

    if (req.method === 'PUT' && url.pathname === '/v1/keys/signed') {
      const body = await readJson(req);
      if (!isSignedKey(body)) return send(res, 400, { error: 'Invalid signed prekey' });
      state.users[me].signedPreKey = body;
      save();
      return send(res, 204);
    }

    // Encrypted attachments: the client encrypts every chunk before upload and only the
    // recipient gets the key (inside an E2EE message). Blob ids are 128-bit random, so
    // knowing the id is the capability to download.
    if (req.method === 'POST' && url.pathname === '/v1/blobs') {
      const body = await readJson(req);
      const size = Number(body.size);
      const chunkCount = Number(body.chunkCount);
      if (!Number.isSafeInteger(size) || size < 0 || size > MAX_BLOB_BYTES) {
        return send(res, 413, { error: 'File too large' });
      }
      if (!Number.isInteger(chunkCount) || chunkCount < 1 || chunkCount > Math.ceil(MAX_BLOB_BYTES / (256 * 1024)) + 1) {
        return send(res, 400, { error: 'Invalid chunk count' });
      }
      const blobId = crypto.randomBytes(16).toString('base64url');
      fs.mkdirSync(path.join(BLOB_DIR, blobId), { recursive: true });
      state.blobs[blobId] = { owner: me, size, chunkCount, createdAt: Date.now() };
      save();
      return send(res, 200, { blobId });
    }

    const chunkMatch = /^\/v1\/blobs\/([A-Za-z0-9_-]+)\/chunks\/(\d+)$/.exec(url.pathname);
    if (chunkMatch) {
      const [, blobId, rawIndex] = chunkMatch;
      const index = Number(rawIndex);
      const blob = BLOB_ID.test(blobId) ? state.blobs[blobId] : undefined;
      if (!blob || index >= blob.chunkCount) return send(res, 404, { error: 'No such chunk' });

      if (req.method === 'PUT') {
        if (blob.owner !== me) return send(res, 403, { error: 'Not your upload' });
        const data = await readRaw(req, MAX_CHUNK_BYTES);
        if (!data) return send(res, 413, { error: 'Chunk too large' });
        fs.writeFileSync(chunkPath(blobId, index) + '.tmp', data);
        fs.renameSync(chunkPath(blobId, index) + '.tmp', chunkPath(blobId, index));
        return send(res, 204);
      }
      if (req.method === 'GET') {
        let data;
        try {
          data = fs.readFileSync(chunkPath(blobId, index));
        } catch {
          return send(res, 404, { error: 'Chunk not uploaded yet' });
        }
        res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': data.length });
        return res.end(data);
      }
    }

    const keysMatch = /^\/v1\/keys\/([a-z0-9._]{3,32})$/.exec(url.pathname);
    if (req.method === 'GET' && keysMatch) {
      const target = state.users[keysMatch[1]];
      if (!target) return send(res, 404, { error: 'No such user' });
      // Each one-time prekey is handed out exactly once.
      const preKey = target.preKeys.shift() || null;
      save();
      return send(res, 200, {
        username: keysMatch[1],
        deviceId: 1,
        registrationId: target.registrationId,
        identityKey: target.identityKey,
        signedPreKey: target.signedPreKey,
        kyberPreKey: target.kyberPreKey,
        preKey,
      });
    }

    return send(res, 404, { error: 'Not found' });
  } catch (e) {
    return send(res, 400, { error: e.message });
  }
}

function deliverQueued(username) {
  const ws = online.get(username);
  if (!ws) return;
  for (const envelope of state.queues[username] || []) {
    ws.send(JSON.stringify({ type: 'envelope', envelope }));
  }
}

function onFrame(username, ws, raw) {
  let frame;
  try {
    frame = JSON.parse(raw);
  } catch {
    return ws.send(JSON.stringify({ type: 'error', error: 'Bad JSON' }));
  }

  if (frame.type === 'send') {
    const envelope = frame.envelope || {};
    const to = envelope.recipientTag;
    if (typeof envelope.envelopeId !== 'string' || typeof envelope.ciphertext !== 'string') {
      return ws.send(JSON.stringify({ type: 'error', error: 'Malformed envelope', envelopeId: envelope.envelopeId }));
    }
    if (!state.users[to]) {
      return ws.send(JSON.stringify({ type: 'error', error: 'Unknown recipient', envelopeId: envelope.envelopeId }));
    }
    // The relay, not the client, says who sent it, so senders can't be spoofed.
    const stored = { ...envelope, senderId: username, deviceId: 1, receivedAt: Date.now() };
    const queue = state.queues[to];
    if (!queue.some((e) => e.envelopeId === stored.envelopeId)) queue.push(stored);
    save();
    ws.send(JSON.stringify({ type: 'server_ack', envelopeId: envelope.envelopeId }));
    const recipientSocket = online.get(to);
    if (recipientSocket) recipientSocket.send(JSON.stringify({ type: 'envelope', envelope: stored }));
    // TODO(phase 1b): FCM data-only wake-up when the recipient is offline.
    return;
  }

  if (frame.type === 'ack' && Array.isArray(frame.envelopeIds)) {
    const ids = new Set(frame.envelopeIds);
    state.queues[username] = (state.queues[username] || []).filter((e) => !ids.has(e.envelopeId));
    save();
  }
}

function pruneQueues() {
  const cutoff = Date.now() - QUEUE_TTL_MS;
  for (const name of Object.keys(state.queues)) {
    state.queues[name] = state.queues[name].filter((e) => (e.receivedAt || 0) > cutoff);
  }
  for (const [blobId, blob] of Object.entries(state.blobs)) {
    if (blob.createdAt < cutoff) {
      fs.rmSync(path.join(BLOB_DIR, blobId), { recursive: true, force: true });
      delete state.blobs[blobId];
    }
  }
  save();
}

function createServer() {
  const server = http.createServer(handleHttp);
  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://relay');
    const username = url.pathname === '/v1/ws' ? authenticate(req) : null;
    if (!username) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      return socket.destroy();
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      online.get(username)?.close(4000, 'Replaced by new connection');
      online.set(username, ws);
      console.log(`@${username} online`);
      ws.on('message', (data) => onFrame(username, ws, data.toString()));
      ws.on('close', () => {
        if (online.get(username) === ws) online.delete(username);
        console.log(`@${username} offline`);
      });
      deliverQueued(username);
    });
  });

  return server;
}

if (require.main === module) {
  pruneQueues();
  setInterval(pruneQueues, 60 * 60 * 1000).unref();
  createServer().listen(PORT, () => console.log(`WiChat relay listening on :${PORT}`));
}

module.exports = { createServer, state };
