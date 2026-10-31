'use strict';

/*
 * Castle of Magic room server.
 *
 * - REST: create a room (host), join a room with its room key (guest), read status, close.
 * - WebSocket: each player connects with their player token; every message is relayed
 *   to the other player in the room. The server never looks inside game messages.
 * - Two keys: ACCESS_KEY (server-wide, only your game copies know it) and a room key per room.
 * - Everything lives in memory. A restart (or Render putting the service to sleep) clears all rooms.
 */

const http = require('node:http');
const crypto = require('node:crypto');
const { promisify } = require('node:util');
const { WebSocketServer, WebSocket } = require('ws');

const scrypt = promisify(crypto.scrypt);

const ROLES = ['host', 'guest'];
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O, 1/I
const CODE_LENGTH = 6;

function int(v, def) {
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) && n > 0 ? n : def;
}

function loadConfig(env = process.env) {
  return {
    port: int(env.PORT, 3000),
    accessKey: env.ACCESS_KEY || '',
    trustProxy: env.TRUST_PROXY === '1' || env.TRUST_PROXY === 'true',
    maxRooms: int(env.MAX_ROOMS, 50),
    roomTtlMs: int(env.ROOM_TTL_MINUTES, 360) * 60_000,
    idleMs: int(env.ROOM_IDLE_MINUTES, 30) * 60_000,
    reconnectGraceMs: int(env.RECONNECT_GRACE_SECONDS, 60) * 1000,
    maxMessageBytes: int(env.MAX_MESSAGE_BYTES, 512 * 1024),
    maxBytesPerSecond: int(env.MAX_BYTES_PER_SECOND, 4 * 1024 * 1024),
    maxBufferedBytes: int(env.MAX_BUFFERED_BYTES, 8 * 1024 * 1024),
    requestsPerMinute: int(env.REQUESTS_PER_MINUTE, 30),
    heartbeatMs: int(env.HEARTBEAT_SECONDS, 25) * 1000,
    sweepMs: int(env.SWEEP_SECONDS, 30) * 1000,
  };
}

// ---------------------------------------------------------------- helpers

const sha256 = (s) => crypto.createHash('sha256').update(s).digest();

/** Constant-time comparison of two strings of any length. */
function safeEqual(a, b) {
  return crypto.timingSafeEqual(sha256(String(a)), sha256(String(b)));
}

function newToken() {
  return crypto.randomBytes(32).toString('base64url');
}

function newRoomCode(taken) {
  for (;;) {
    let code = '';
    for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
    if (!taken.has(code)) return code;
  }
}

async function hashRoomKey(key, salt) {
  return scrypt(key, salt, 32);
}

function cleanName(v) {
  if (typeof v !== 'string') return null;
  const name = v.replace(/[\u0000-\u001f\u007f]/g, '').trim();
  return name.length >= 1 && name.length <= 20 ? name : null;
}

function validRoomKey(v) {
  return typeof v === 'string' && v.length >= 4 && v.length <= 64;
}

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function sendJson(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    'Cache-Control': 'no-store',
  });
  res.end(data);
}

function readJson(req, limit = 4096) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new HttpError(413, 'Request body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (size === 0) return resolve({});
      try {
        const v = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error('not an object');
        resolve(v);
      } catch { reject(new HttpError(400, 'Body must be a JSON object')); }
    });
    req.on('error', reject);
  });
}

function bearer(req) {
  const h = req.headers.authorization || '';
  return h.startsWith('Bearer ') ? h.slice(7).trim() : '';
}

// ---------------------------------------------------------------- server

function createRoomServer(config = loadConfig()) {
  if (!config.accessKey || config.accessKey.length < 16) {
    throw new Error('ACCESS_KEY must be set and at least 16 characters long');
  }

  /** @type {Map<string, any>} room code -> room */
  const rooms = new Map();
  /** @type {Map<string, {room: any, role: string}>} sha256(token) hex -> player ref */
  const tokens = new Map();
  /** @type {Map<string, {windowStart: number, count: number}>} */
  const rate = new Map();

  const now = () => Date.now();

  function clientIp(req) {
    if (config.trustProxy) {
      const xff = req.headers['x-forwarded-for'];
      if (typeof xff === 'string' && xff.length) return xff.split(',')[0].trim();
    }
    return req.socket.remoteAddress || 'unknown';
  }

  function checkRate(ip) {
    const t = now();
    let r = rate.get(ip);
    if (!r || t - r.windowStart >= 60_000) { r = { windowStart: t, count: 0 }; rate.set(ip, r); }
    r.count++;
    if (r.count > config.requestsPerMinute) throw new HttpError(429, 'Too many requests, try again in a minute');
  }

  function checkAccess(headers) {
    const key = headers['x-access-key'];
    if (typeof key !== 'string' || !safeEqual(key, config.accessKey)) throw new HttpError(401, 'Missing or wrong access key');
  }

  function tokenRef(token) {
    if (!token) return null;
    return tokens.get(sha256(token).toString('hex')) || null;
  }

  function addPlayer(room, role, name) {
    const token = newToken();
    room.players[role] = { role, name, socket: null, joinedAt: now(), lastSeen: now(), tokenId: sha256(token).toString('hex') };
    tokens.set(room.players[role].tokenId, { room, role });
    return token;
  }

  function removePlayer(room, role) {
    const p = room.players[role];
    if (!p) return;
    tokens.delete(p.tokenId);
    if (p.socket) p.socket.close(4001, 'Removed from room');
    room.players[role] = null;
  }

  /** A guest slot is free again if its player left and did not come back within the grace period. */
  function slotFree(p) {
    return !p || (!p.socket && now() - p.lastSeen > config.reconnectGraceMs);
  }

  function publicRoom(room) {
    return {
      roomCode: room.code,
      createdAt: new Date(room.createdAt).toISOString(),
      expiresAt: new Date(room.createdAt + config.roomTtlMs).toISOString(),
      players: ROLES.filter((r) => room.players[r]).map((r) => ({
        role: r, name: room.players[r].name, online: !!room.players[r].socket,
      })),
    };
  }

  function closeRoom(room, reason) {
    if (!rooms.has(room.code)) return;
    rooms.delete(room.code);
    for (const role of ROLES) {
      const p = room.players[role];
      if (!p) continue;
      tokens.delete(p.tokenId);
      if (p.socket && p.socket.readyState === WebSocket.OPEN) {
        p.socket.send(JSON.stringify({ type: 'room.closed', reason }));
        p.socket.close(4002, 'Room closed');
      }
    }
  }

  function sendEvent(ws, event) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(event));
  }

  // ------------------------------------------------------------ REST

  async function handle(req, res) {
    const url = new URL(req.url, 'http://local');
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (req.method === 'GET' && (path === '/' || path === '/health')) {
      return sendJson(res, 200, { ok: true, service: 'castle-of-magic-rooms' });
    }

    if (!path.startsWith('/v1/')) throw new HttpError(404, 'Not found');
    checkAccess(req.headers);

    // POST /v1/rooms  -> create a room, caller becomes host
    if (req.method === 'POST' && path === '/v1/rooms') {
      checkRate(clientIp(req));
      const body = await readJson(req);
      const name = cleanName(body.name);
      if (!name) throw new HttpError(400, 'name must be 1-20 characters');
      if (!validRoomKey(body.roomKey)) throw new HttpError(400, 'roomKey must be 4-64 characters');
      if (rooms.size >= config.maxRooms) throw new HttpError(503, 'Server is full, try again later');

      const code = newRoomCode(rooms);
      const salt = crypto.randomBytes(16);
      const room = {
        code, createdAt: now(), lastActivity: now(),
        keySalt: salt, keyHash: await hashRoomKey(body.roomKey, salt),
        failedJoins: 0, lockedUntil: 0,
        players: { host: null, guest: null },
      };
      rooms.set(code, room);
      const playerToken = addPlayer(room, 'host', name);
      return sendJson(res, 201, { ...publicRoom(room), role: 'host', playerToken });
    }

    const m = path.match(/^\/v1\/rooms\/([A-Za-z0-9]{4,12})(\/join)?$/);
    if (!m) throw new HttpError(404, 'Not found');
    const code = m[1].toUpperCase();

    // POST /v1/rooms/:code/join  -> join as guest with the room key
    if (req.method === 'POST' && m[2]) {
      checkRate(clientIp(req));
      const body = await readJson(req);
      const name = cleanName(body.name);
      if (!name) throw new HttpError(400, 'name must be 1-20 characters');
      if (!validRoomKey(body.roomKey)) throw new HttpError(400, 'roomKey must be 4-64 characters');

      const room = rooms.get(code);
      if (!room) {
        await hashRoomKey(body.roomKey, crypto.randomBytes(16)); // same timing as a real check
        throw new HttpError(403, 'Wrong room code or room key');
      }
      if (room.lockedUntil > now()) throw new HttpError(429, 'Too many wrong keys, wait a minute');
      const hash = await hashRoomKey(body.roomKey, room.keySalt);
      if (!crypto.timingSafeEqual(hash, room.keyHash)) {
        room.failedJoins++;
        if (room.failedJoins % 5 === 0) room.lockedUntil = now() + 60_000;
        throw new HttpError(403, 'Wrong room code or room key');
      }
      if (!slotFree(room.players.guest)) throw new HttpError(409, 'Room is full');
      removePlayer(room, 'guest');
      room.lastActivity = now();
      const playerToken = addPlayer(room, 'guest', name);
      sendEvent(room.players.host && room.players.host.socket, { type: 'room.peer-joined', role: 'guest', name, online: false });
      return sendJson(res, 200, { ...publicRoom(room), role: 'guest', playerToken });
    }

    // GET /v1/rooms/:code  -> status, for players of that room
    // DELETE /v1/rooms/:code  -> host closes the room, guest leaves it
    if (req.method === 'GET' || req.method === 'DELETE') {
      const ref = tokenRef(bearer(req));
      const room = rooms.get(code);
      if (!ref || !room || ref.room !== room) throw new HttpError(403, 'Not a player in this room');
      if (req.method === 'GET') return sendJson(res, 200, { ...publicRoom(room), role: ref.role });
      if (ref.role === 'host') closeRoom(room, 'host-closed');
      else {
        removePlayer(room, 'guest');
        sendEvent(room.players.host && room.players.host.socket, { type: 'room.peer-left', role: 'guest', reason: 'left' });
      }
      res.writeHead(204, { 'Cache-Control': 'no-store' });
      return res.end();
    }

    throw new HttpError(405, 'Method not allowed');
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch((err) => {
      if (res.headersSent) return res.destroy();
      if (err instanceof HttpError) return sendJson(res, err.status, { error: err.message });
      console.error('request failed:', err);
      sendJson(res, 500, { error: 'Internal error' });
    });
  });
  server.headersTimeout = 15_000;
  server.requestTimeout = 15_000;

  // ------------------------------------------------------------ WebSocket relay

  const wss = new WebSocketServer({ noServer: true, maxPayload: config.maxMessageBytes, perMessageDeflate: false });

  function rejectUpgrade(socket, status, message) {
    const body = JSON.stringify({ error: message });
    socket.write(`HTTP/1.1 ${status} ${http.STATUS_CODES[status]}\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
    socket.destroy();
  }

  server.on('upgrade', (req, socket, head) => {
    const path = new URL(req.url, 'http://local').pathname.replace(/\/+$/, '');
    if (path !== '/v1/ws') return rejectUpgrade(socket, 404, 'Not found');
    try { checkAccess(req.headers); } catch (e) { return rejectUpgrade(socket, 401, e.message); }
    const ref = tokenRef(bearer(req));
    if (!ref || !rooms.has(ref.room.code)) return rejectUpgrade(socket, 403, 'Invalid or expired player token');
    wss.handleUpgrade(req, socket, head, (ws) => attach(ws, ref.room, ref.role));
  });

  function attach(ws, room, role) {
    const me = room.players[role];
    if (!me) { ws.close(4001, 'Removed from room'); return; }
    const peerRole = role === 'host' ? 'guest' : 'host';

    if (me.socket) { me.socket.close(4000, 'Connected from somewhere else'); }
    me.socket = ws;
    me.lastSeen = now();
    room.lastActivity = now();
    ws.isAlive = true;
    ws.bytesWindowStart = now();
    ws.bytesInWindow = 0;

    const peer = room.players[peerRole];
    sendEvent(ws, {
      type: 'room.welcome', roomCode: room.code, role, name: me.name,
      peer: peer ? { role: peerRole, name: peer.name, online: !!peer.socket } : null,
    });
    if (peer) sendEvent(peer.socket, { type: 'room.peer-joined', role, name: me.name, online: true });

    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (data, isBinary) => {
      const t = now();
      if (t - ws.bytesWindowStart >= 1000) { ws.bytesWindowStart = t; ws.bytesInWindow = 0; }
      ws.bytesInWindow += data.length;
      if (ws.bytesInWindow > config.maxBytesPerSecond) { ws.close(1008, 'Sending too fast'); return; }
      me.lastSeen = t;
      room.lastActivity = t;

      if (!isBinary && data.length < 64) {
        // small text messages may be a keep-alive for the server itself
        try {
          const msg = JSON.parse(data.toString('utf8'));
          if (msg && msg.type === 'ping') { sendEvent(ws, { type: 'pong', t: msg.t ?? null }); return; }
        } catch { /* not JSON, relay as-is */ }
      }

      const other = room.players[peerRole];
      const out = other && other.socket;
      if (!out || out.readyState !== WebSocket.OPEN) return;      // nobody to receive it
      if (out.bufferedAmount > config.maxBufferedBytes) return;  // peer is too slow: drop instead of piling up
      out.send(data, { binary: isBinary });
    });

    ws.on('close', () => {
      if (me.socket !== ws) return; // replaced by a newer connection
      me.socket = null;
      me.lastSeen = now();
      const other = room.players[peerRole];
      if (other) sendEvent(other.socket, { type: 'room.peer-left', role, reason: 'disconnected' });
    });

    ws.on('error', () => { /* close follows */ });
  }

  // ------------------------------------------------------------ housekeeping

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) { ws.terminate(); continue; }
      ws.isAlive = false;
      ws.ping();
    }
  }, config.heartbeatMs);

  const sweeper = setInterval(() => {
    const t = now();
    for (const room of [...rooms.values()]) {
      const online = ROLES.some((r) => room.players[r] && room.players[r].socket);
      if (t - room.createdAt > config.roomTtlMs) closeRoom(room, 'expired');
      else if (!online && t - room.lastActivity > config.idleMs) closeRoom(room, 'idle');
    }
    for (const [ip, r] of rate) if (t - r.windowStart > 120_000) rate.delete(ip);
  }, config.sweepMs);
  heartbeat.unref();
  sweeper.unref();

  server.on('close', () => { clearInterval(heartbeat); clearInterval(sweeper); });

  return {
    server,
    rooms,
    close: () => new Promise((resolve) => {
      for (const room of [...rooms.values()]) closeRoom(room, 'server-shutdown');
      for (const ws of wss.clients) ws.terminate();
      server.close(() => resolve());
    }),
  };
}

module.exports = { createRoomServer, loadConfig };

if (require.main === module) {
  const config = loadConfig();
  let app;
  try { app = createRoomServer(config); }
  catch (e) { console.error(e.message); process.exit(1); }
  app.server.listen(config.port, () => console.log(`Room server listening on port ${config.port}`));
  const stop = () => { console.log('Shutting down'); app.close().then(() => process.exit(0)); };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
