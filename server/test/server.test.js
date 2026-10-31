'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { WebSocket } = require('ws');
const { createRoomServer, loadConfig } = require('../src/server');

const ACCESS_KEY = 'test-access-key-1234567890';

async function start(overrides = {}) {
  const config = { ...loadConfig({}), accessKey: ACCESS_KEY, port: 0, reconnectGraceMs: 200, ...overrides };
  const app = createRoomServer(config);
  await new Promise((r) => app.server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${app.server.address().port}`;
  return { app, base, ws: base.replace('http', 'ws') + '/v1/ws' };
}

async function api(base, method, path, { body, token, key = ACCESS_KEY } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (key) headers['X-Access-Key'] = key;
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

/** Opens a socket and collects incoming messages. */
function connect(url, token, key = ACCESS_KEY) {
  return new Promise((resolve, reject) => {
    const headers = { 'X-Access-Key': key };
    if (token) headers.Authorization = `Bearer ${token}`;
    const ws = new WebSocket(url, { headers });
    const inbox = [];
    const waiters = [];
    ws.on('message', (data, isBinary) => {
      const msg = isBinary ? { binary: Buffer.from(data) } : JSON.parse(data.toString());
      const w = waiters.findIndex((x) => x.match(msg));
      if (w >= 0) waiters.splice(w, 1)[0].resolve(msg); else inbox.push(msg);
    });
    ws.next = (match = () => true, ms = 2000) => {
      const i = inbox.findIndex(match);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((res, rej) => {
        const t = setTimeout(() => rej(new Error('timeout waiting for message')), ms);
        waiters.push({ match, resolve: (m) => { clearTimeout(t); res(m); } });
      });
    };
    ws.on('open', () => resolve(ws));
    ws.on('unexpected-response', (_req, res) => reject(new Error(`upgrade rejected: ${res.statusCode}`)));
    ws.on('error', reject);
  });
}

const byType = (type) => (m) => m.type === type;

test('health is public, the API needs the access key', async (t) => {
  const { app, base } = await start();
  t.after(() => app.close());
  assert.equal((await api(base, 'GET', '/health', { key: null })).status, 200);
  assert.equal((await api(base, 'POST', '/v1/rooms', { key: null, body: { name: 'A', roomKey: '1234' } })).status, 401);
  assert.equal((await api(base, 'POST', '/v1/rooms', { key: 'wrong-key-wrong-key', body: { name: 'A', roomKey: '1234' } })).status, 401);
});

test('create, join with the right key, relay both ways', async (t) => {
  const { app, base, ws: wsUrl } = await start();
  t.after(() => app.close());

  const host = await api(base, 'POST', '/v1/rooms', { body: { name: 'Anirban', roomKey: '4321' } });
  assert.equal(host.status, 201);
  assert.match(host.body.roomCode, /^[A-Z2-9]{6}$/);
  assert.equal(host.body.role, 'host');

  const bad = await api(base, 'POST', `/v1/rooms/${host.body.roomCode}/join`, { body: { name: 'Friend', roomKey: '0000' } });
  assert.equal(bad.status, 403);
  const missing = await api(base, 'POST', '/v1/rooms/ZZZZZZ/join', { body: { name: 'Friend', roomKey: '4321' } });
  assert.equal(missing.status, 403);
  assert.equal(missing.body.error, bad.body.error, 'same error for unknown room and wrong key');

  const guest = await api(base, 'POST', `/v1/rooms/${host.body.roomCode.toLowerCase()}/join`, { body: { name: 'Friend', roomKey: '4321' } });
  assert.equal(guest.status, 200);
  assert.equal(guest.body.role, 'guest');

  const third = await api(base, 'POST', `/v1/rooms/${host.body.roomCode}/join`, { body: { name: 'Other', roomKey: '4321' } });
  assert.equal(third.status, 409, 'only two players per room');

  const hs = await connect(wsUrl, host.body.playerToken);
  const welcome = await hs.next(byType('room.welcome'));
  assert.equal(welcome.role, 'host');
  assert.equal(welcome.peer.name, 'Friend');
  assert.equal(welcome.peer.online, false);

  const gs = await connect(wsUrl, guest.body.playerToken);
  assert.equal((await gs.next(byType('room.welcome'))).peer.name, 'Anirban');
  assert.equal((await hs.next(byType('room.peer-joined'))).online, true);

  // binary game data both ways, unchanged
  const frame = Buffer.from([1, 2, 3, 250, 0, 7]);
  hs.send(frame);
  assert.deepEqual((await gs.next((m) => m.binary)).binary, frame);
  gs.send(Buffer.from('keys'));
  assert.equal((await hs.next((m) => m.binary)).binary.toString(), 'keys');

  // text JSON is relayed too; ping is answered by the server
  gs.send(JSON.stringify({ type: 'chat', text: 'hi' }));
  assert.equal((await hs.next(byType('chat'))).text, 'hi');
  hs.send(JSON.stringify({ type: 'ping', t: 5 }));
  assert.equal((await hs.next(byType('pong'))).t, 5);

  const status = await api(base, 'GET', `/v1/rooms/${host.body.roomCode}`, { token: guest.body.playerToken });
  assert.equal(status.status, 200);
  assert.deepEqual(status.body.players.map((p) => [p.role, p.online]), [['host', true], ['guest', true]]);

  // guest disconnects -> host is told
  gs.close();
  assert.equal((await hs.next(byType('room.peer-left'))).role, 'guest');

  // host closes the room -> tokens stop working
  const del = await api(base, 'DELETE', `/v1/rooms/${host.body.roomCode}`, { token: host.body.playerToken });
  assert.equal(del.status, 204);
  assert.equal((await hs.next(byType('room.closed'))).reason, 'host-closed');
  await assert.rejects(connect(wsUrl, guest.body.playerToken), /403/);
});

test('websocket rejects missing access key or bad token', async (t) => {
  const { app, base, ws: wsUrl } = await start();
  t.after(() => app.close());
  const host = await api(base, 'POST', '/v1/rooms', { body: { name: 'A', roomKey: '1234' } });
  await assert.rejects(connect(wsUrl, host.body.playerToken, 'nope-nope-nope-nope'), /401/);
  await assert.rejects(connect(wsUrl, 'made-up-token'), /403/);
  await assert.rejects(connect(wsUrl, null), /403/);
});

test('reconnecting with the same token replaces the old socket', async (t) => {
  const { app, base, ws: wsUrl } = await start();
  t.after(() => app.close());
  const host = await api(base, 'POST', '/v1/rooms', { body: { name: 'A', roomKey: '1234' } });
  const first = await connect(wsUrl, host.body.playerToken);
  const closed = new Promise((r) => first.on('close', (code) => r(code)));
  const second = await connect(wsUrl, host.body.playerToken);
  assert.equal(await closed, 4000);
  assert.equal((await second.next(byType('room.welcome'))).role, 'host');
  second.close();
});

test('guest slot frees up after the guest is gone longer than the grace period', async (t) => {
  const { app, base, ws: wsUrl } = await start({ reconnectGraceMs: 150 });
  t.after(() => app.close());
  const host = await api(base, 'POST', '/v1/rooms', { body: { name: 'A', roomKey: '1234' } });
  const code = host.body.roomCode;
  const g1 = await api(base, 'POST', `/v1/rooms/${code}/join`, { body: { name: 'B', roomKey: '1234' } });
  const s = await connect(wsUrl, g1.body.playerToken);
  s.close();
  await new Promise((r) => setTimeout(r, 50));
  assert.equal((await api(base, 'POST', `/v1/rooms/${code}/join`, { body: { name: 'C', roomKey: '1234' } })).status, 409);
  await new Promise((r) => setTimeout(r, 200));
  assert.equal((await api(base, 'POST', `/v1/rooms/${code}/join`, { body: { name: 'C', roomKey: '1234' } })).status, 200);
  await assert.rejects(connect(wsUrl, g1.body.playerToken), /403/, 'old guest token no longer works');
});

test('wrong keys lock the room for a minute', async (t) => {
  const { app, base } = await start();
  t.after(() => app.close());
  const host = await api(base, 'POST', '/v1/rooms', { body: { name: 'A', roomKey: 'secret' } });
  for (let i = 0; i < 5; i++) {
    assert.equal((await api(base, 'POST', `/v1/rooms/${host.body.roomCode}/join`, { body: { name: 'X', roomKey: 'guess' + i } })).status, 403);
  }
  assert.equal((await api(base, 'POST', `/v1/rooms/${host.body.roomCode}/join`, { body: { name: 'X', roomKey: 'secret' } })).status, 429);
});

test('input validation and limits', async (t) => {
  const { app, base } = await start({ maxRooms: 1, requestsPerMinute: 6 });
  t.after(() => app.close());
  assert.equal((await api(base, 'POST', '/v1/rooms', { body: { name: '', roomKey: '1234' } })).status, 400);
  assert.equal((await api(base, 'POST', '/v1/rooms', { body: { name: 'A', roomKey: '12' } })).status, 400);
  assert.equal((await api(base, 'POST', '/v1/rooms', { body: { name: 'A', roomKey: '1234' } })).status, 201);
  assert.equal((await api(base, 'POST', '/v1/rooms', { body: { name: 'B', roomKey: '1234' } })).status, 503, 'room limit');
  const res = await fetch(base + '/v1/rooms', { method: 'POST', headers: { 'X-Access-Key': ACCESS_KEY }, body: '[1,2' });
  assert.equal(res.status, 400);
  for (let i = 0; i < 3; i++) await api(base, 'POST', '/v1/rooms', { body: { name: 'B', roomKey: '1234' } });
  assert.equal((await api(base, 'POST', '/v1/rooms', { body: { name: 'B', roomKey: '1234' } })).status, 429, 'rate limit');
});

test('refuses to start without a strong access key', () => {
  assert.throws(() => createRoomServer({ ...loadConfig({}), accessKey: 'short' }), /ACCESS_KEY/);
});
