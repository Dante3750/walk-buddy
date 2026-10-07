'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const { createServer } = require('../server.js');

async function boot(opts = {}) {
  const srv = createServer({ port: 0, host: '127.0.0.1', ...opts });
  const port = await srv.listen();
  return { srv, url: `ws://127.0.0.1:${port}`, http: `http://127.0.0.1:${port}` };
}

/** A real WS client that queues messages so tests can await them in order. */
function client(url) {
  const ws = new WebSocket(url);
  const queue = [];
  const waiters = [];
  ws.on('message', (d) => {
    const m = JSON.parse(d.toString());
    const w = waiters.shift();
    if (w) w(m); else queue.push(m);
  });
  const api = {
    ws,
    open: () => new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); }),
    send: (o) => ws.send(typeof o === 'string' ? o : JSON.stringify(o)),
    next: (ms = 1500) => new Promise((res, rej) => {
      if (queue.length) return res(queue.shift());
      const t = setTimeout(() => rej(new Error('timeout waiting for message')), ms);
      waiters.push((m) => { clearTimeout(t); res(m); });
    }),
    none: (ms = 150) => new Promise((res, rej) => setTimeout(() => (queue.length ? rej(new Error('unexpected ' + JSON.stringify(queue[0]))) : res()), ms)),
    closed: () => new Promise((res) => (ws.readyState === ws.CLOSED ? res({ code: ws.closeCode }) : ws.once('close', (code, reason) => res({ code, reason: reason.toString() })))),
    close: () => ws.close(),
  };
  return api;
}
async function join(url, code, peer) {
  const c = client(url);
  await c.open();
  c.send({ t: 'join', code, peer });
  const joined = await c.next();
  return { c, joined };
}

test('first peer creates the room, second sees existing peers and first is told', async () => {
  const { srv, url } = await boot();
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    assert.deepEqual(a.joined, { t: 'joined', you: 'alice', peers: [] });
    const b = await join(url, 'K7M2QX', 'bob');
    assert.deepEqual(b.joined.peers, ['alice']);
    assert.deepEqual(await a.c.next(), { t: 'peer-joined', peer: 'bob' });
    assert.equal(srv.stats().rooms, 1);
  } finally { await srv.close(); }
});

test('offer, answer and ice are relayed with a server-stamped sender', async () => {
  const { srv, url } = await boot();
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    const b = await join(url, 'K7M2QX', 'bob');
    await a.c.next();
    for (const kind of ['offer', 'ice']) {
      a.c.send({ t: kind, to: 'bob', data: `payload-${kind}`, from: 'mallory' });
      assert.deepEqual(await b.c.next(), { t: kind, from: 'alice', to: 'bob', data: `payload-${kind}` });
    }
    b.c.send({ t: 'answer', to: 'alice', data: 'sdp-answer' });
    assert.deepEqual(await a.c.next(), { t: 'answer', from: 'bob', to: 'alice', data: 'sdp-answer' });
  } finally { await srv.close(); }
});

test('extra fields are never forwarded (fixed envelope)', async () => {
  const { srv, url } = await boot();
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    const b = await join(url, 'K7M2QX', 'bob');
    await a.c.next();
    a.c.send({ t: 'offer', to: 'bob', data: 'x', lat: 12.9, lon: 77.5, extra: { a: 1 } });
    const m = await b.c.next();
    assert.deepEqual(Object.keys(m).sort(), ['data', 'from', 't', 'to']);
  } finally { await srv.close(); }
});

test('location-like or unknown message types are refused and never forwarded', async () => {
  const { srv, url } = await boot();
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    const b = await join(url, 'K7M2QX', 'bob');
    await a.c.next();
    a.c.send({ t: 'pos', to: 'bob', lat: 12.9, lon: 77.5 });
    assert.deepEqual(await a.c.next(), { t: 'error', code: 'unsupported_type' });
    await b.c.none();
  } finally { await srv.close(); }
});

test('cannot relay across rooms, to self, or to unknown peers', async () => {
  const { srv, url } = await boot();
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    const other = await join(url, 'ZZZZ22', 'carol');
    a.c.send({ t: 'offer', to: 'carol', data: 'x' });
    assert.equal((await a.c.next()).code, 'unknown_peer');
    a.c.send({ t: 'offer', to: 'alice', data: 'x' });
    assert.equal((await a.c.next()).code, 'unknown_peer');
    await other.c.none();
  } finally { await srv.close(); }
});

test('relay before joining is refused', async () => {
  const { srv, url } = await boot();
  try {
    const c = client(url); await c.open();
    c.send({ t: 'offer', to: 'bob', data: 'x' });
    assert.equal((await c.next()).code, 'not_joined');
  } finally { await srv.close(); }
});

test('max four peers per room', async () => {
  const { srv, url } = await boot();
  try {
    for (const id of ['a', 'b', 'c', 'd']) await join(url, 'K7M2QX', id);
    const e = client(url); await e.open();
    e.send({ t: 'join', code: 'K7M2QX', peer: 'e' });
    assert.equal((await e.next()).code, 'room_full');
    assert.equal(srv.stats().rooms, 1);
  } finally { await srv.close(); }
});

test('duplicate peer id, bad code and bad peer id are rejected', async () => {
  const { srv, url } = await boot();
  try {
    await join(url, 'K7M2QX', 'alice');
    const c = client(url); await c.open();
    c.send({ t: 'join', code: 'K7M2QX', peer: 'alice' });
    assert.equal((await c.next()).code, 'id_taken');
    c.send({ t: 'join', code: 'k7m2qx', peer: 'x' });
    assert.equal((await c.next()).code, 'bad_code');
    c.send({ t: 'join', code: 'K7M0QX', peer: 'x' }); // 0 is not in the alphabet
    assert.equal((await c.next()).code, 'bad_code');
    c.send({ t: 'join', code: 'K7M2QX', peer: 'bad id!' });
    assert.equal((await c.next()).code, 'bad_peer');
  } finally { await srv.close(); }
});

test('invalid JSON gets an error and repeated garbage closes the connection', async () => {
  const { srv, url } = await boot({ maxBadMessages: 3 });
  try {
    const c = client(url); await c.open();
    c.send('{not json');
    assert.equal((await c.next()).code, 'bad_message');
    c.send('[1,2]');
    c.send('"str"');
    const r = await c.closed();
    assert.equal(r.code, 1008);
  } finally { await srv.close(); }
});

test('oversized frames are rejected by the transport', async () => {
  const { srv, url } = await boot({ maxMessageBytes: 1024 });
  try {
    const c = client(url); await c.open();
    c.send('x'.repeat(5000));
    const r = await c.closed();
    assert.equal(r.code, 1009);
  } finally { await srv.close(); }
});

test('payloads above the SDP cap are refused', async () => {
  const { srv, url } = await boot({ maxPayloadChars: 100 });
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    const b = await join(url, 'K7M2QX', 'bob');
    await a.c.next();
    a.c.send({ t: 'offer', to: 'bob', data: 'x'.repeat(101) });
    assert.equal((await a.c.next()).code, 'payload_too_large');
    await b.c.none();
  } finally { await srv.close(); }
});

test('peer-left is sent on disconnect and an empty room is deleted', async () => {
  const { srv, url } = await boot();
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    const b = await join(url, 'K7M2QX', 'bob');
    await a.c.next();
    b.c.close();
    assert.deepEqual(await a.c.next(), { t: 'peer-left', peer: 'bob' });
    a.c.close();
    await a.c.closed();
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(srv.stats().rooms, 0);
  } finally { await srv.close(); }
});

test('explicit leave frees the slot', async () => {
  const { srv, url } = await boot();
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    const b = await join(url, 'K7M2QX', 'bob');
    await a.c.next();
    b.c.send({ t: 'leave' });
    assert.deepEqual(await a.c.next(), { t: 'peer-left', peer: 'bob' });
  } finally { await srv.close(); }
});

test('rate limiting closes a flooding connection', async () => {
  const { srv, url } = await boot({ burst: 5, refillPerSec: 0.001 });
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    await join(url, 'K7M2QX', 'bob');
    await a.c.next();
    for (let i = 0; i < 20; i++) a.c.send({ t: 'ice', to: 'bob', data: String(i) });
    const r = await a.c.closed();
    assert.equal(r.code, 1008);
  } finally { await srv.close(); }
});

test('join attempts per IP are limited (code guessing)', async () => {
  const { srv, url } = await boot({ joinsPerIpPerMin: 3 });
  try {
    const c = client(url); await c.open();
    for (const code of ['AAAAAA', 'BBBBBB', 'CCCCCC']) {
      c.send({ t: 'leave' });
      c.send({ t: 'join', code, peer: 'x' });
      assert.equal((await c.next()).t, 'joined');
      c.send({ t: 'leave' });
    }
    c.send({ t: 'join', code: 'DDDDDD', peer: 'x' });
    assert.equal((await c.next()).code, 'too_many_joins');
    assert.equal((await c.closed()).code, 1008);
  } finally { await srv.close(); }
});

test('connections that never join are dropped', async () => {
  const { srv, url } = await boot({ joinTimeoutMs: 100 });
  try {
    const c = client(url); await c.open();
    assert.equal((await c.closed()).code, 1008);
  } finally { await srv.close(); }
});

test('rooms expire after the TTL and peers are told', async () => {
  const { srv, url } = await boot({ roomTtlMs: 150, sweepMs: 50 });
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    assert.equal((await a.c.next()).code, 'room_expired');
    await a.c.closed();
    assert.equal(srv.stats().rooms, 0);
  } finally { await srv.close(); }
});

test('idle rooms expire even before the hard TTL', async () => {
  const { srv, url } = await boot({ idleTtlMs: 100, sweepMs: 40 });
  try {
    const a = await join(url, 'K7M2QX', 'alice');
    assert.equal((await a.c.next()).code, 'room_expired');
  } finally { await srv.close(); }
});

test('per-IP connection cap', async () => {
  const { srv, url } = await boot({ maxConnsPerIp: 2 });
  try {
    const a = client(url); await a.open();
    const b = client(url); await b.open();
    const c = client(url);
    const res = await new Promise((resolve) => { c.ws.on('close', (code) => resolve(code)); c.ws.on('error', () => {}); });
    assert.equal(res, 1008);
  } finally { await srv.close(); }
});

test('health endpoint reports only an aggregate count; other paths 404', async () => {
  const { srv, url, http } = await boot();
  try {
    await join(url, 'K7M2QX', 'alice');
    const h = await (await fetch(`${http}/health`)).json();
    assert.deepEqual(h, { ok: true, rooms: 1 });
    assert.equal((await fetch(`${http}/rooms`)).status, 404);
  } finally { await srv.close(); }
});

test('the server never logs codes, peer ids or payloads', async () => {
  const lines = [];
  const { srv, url } = await boot({ logger: (ev, info) => lines.push(JSON.stringify({ ev, info })), roomTtlMs: 120, sweepMs: 40 });
  const origLog = console.log; const origErr = console.error;
  const consoleLines = [];
  console.log = (...a) => consoleLines.push(a.join(' ')); console.error = (...a) => consoleLines.push(a.join(' '));
  try {
    const a = await join(url, 'K7M2QX', 'secretpeer');
    const b = await join(url, 'K7M2QX', 'otherpeer');
    await a.c.next();
    a.c.send({ t: 'offer', to: 'otherpeer', data: 'SECRET-SDP-PAYLOAD lat=12.97' });
    await b.c.next();
    await new Promise((r) => setTimeout(r, 250));
  } finally {
    console.log = origLog; console.error = origErr;
    await srv.close();
  }
  const all = lines.join('\n') + consoleLines.join('\n');
  for (const needle of ['K7M2QX', 'secretpeer', 'otherpeer', 'SECRET-SDP', 'lat=']) assert.ok(!all.includes(needle), `leaked ${needle}`);
});
