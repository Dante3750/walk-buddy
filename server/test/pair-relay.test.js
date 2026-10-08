'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { boot, client, createGroup, sleep } = require('./util.js');
const { SERVER_VERSION, PAIR_DATA_TYPES } = require('../server.js');

const POS = JSON.stringify({ v: 1, t: 'pos', q: 1000, ts: 1700000000000, lat: 12.97, lon: 77.59, steps: 120 });
const KEY_A = 'pair-key-alice-0123456789';
const KEY_B = 'pair-key-bob-0123456789';

async function pair(url, code = 'K7M2QX', opts = {}) {
  const a = client(url); await a.open();
  a.send({ t: 'join', code, peer: 'alice', ...(opts.keys ? { key: KEY_A } : {}) });
  assert.equal((await a.next()).t, 'joined');
  const b = client(url); await b.open();
  b.send({ t: 'join', code, peer: 'bob', ...(opts.keys ? { key: KEY_B } : {}) });
  assert.equal((await b.next()).t, 'joined');
  assert.equal((await a.next()).t, 'peer-joined');
  return { a, b };
}

test('health carries the aggregate count and a version so a redeploy can be checked', async () => {
  const { srv, http } = await boot();
  try {
    const h = await (await fetch(`${http}/health`)).json();
    assert.deepEqual(h, { ok: true, rooms: 0, v: SERVER_VERSION });
    assert.ok(SERVER_VERSION >= 2);
  } finally { await srv.close(); }
});

test('pdata is forwarded to the partner with a server-stamped sender and a fixed envelope', async () => {
  const { srv, url } = await boot();
  try {
    const { a, b } = await pair(url);
    a.send({ t: 'pdata', data: POS, from: 'mallory', extra: { lat: 1 } });
    assert.deepEqual(await b.next(), { t: 'pdata', from: 'alice', data: POS });
    await a.none(); // never echoed to the sender
    await sleep(150);
    b.send({ t: 'pdata', data: JSON.stringify({ v: 1, t: 'hello', id: 'bob', name: 'Bob' }) });
    const m = await a.next();
    assert.equal(m.t, 'pdata');
    assert.equal(m.from, 'bob');
  } finally { await srv.close(); }
});

test('every allowed partner message type travels; anything else is refused and not forwarded', async () => {
  const { srv, url } = await boot();
  try {
    const { a, b } = await pair(url);
    for (const t of PAIR_DATA_TYPES) {
      a.send({ t: 'pdata', data: JSON.stringify({ v: 1, t }) });
      assert.equal(JSON.parse((await b.next()).data).t, t);
      await sleep(130);
    }
    for (const bad of [
      JSON.stringify({ v: 1, t: 'chat', text: 'hi' }), // not a partner message type
      JSON.stringify({ v: 1 }), // no type
      JSON.stringify([1, 2]), // not an object
      'not json at all',
      '',
      'x'.repeat(1300), // too large
    ]) {
      a.send({ t: 'pdata', data: bad });
      assert.deepEqual(await a.next(), { t: 'error', code: 'bad_message' });
    }
    a.send({ t: 'pdata', data: 42 });
    assert.deepEqual(await a.next(), { t: 'error', code: 'bad_message' });
    await b.none();
  } finally { await srv.close(); }
});

test('pdata needs a joined pair room; group members and strangers cannot use it', async () => {
  const { srv, url } = await boot();
  try {
    const stranger = client(url); await stranger.open();
    stranger.send({ t: 'pdata', data: POS });
    assert.deepEqual(await stranger.next(), { t: 'error', code: 'not_joined' });

    const host = await createGroup(url, 'host');
    host.c.send({ t: 'pdata', data: POS });
    assert.deepEqual(await host.c.next(), { t: 'error', code: 'not_joined' });
  } finally { await srv.close(); }
});

test('pdata from one connection closer than the minimum interval is dropped quietly', async () => {
  const { srv, url } = await boot({ pairDataMinIntervalMs: 400 });
  try {
    const { a, b } = await pair(url);
    a.send({ t: 'pdata', data: POS });
    a.send({ t: 'pdata', data: POS });
    assert.equal((await b.next()).t, 'pdata');
    await b.none(250);
    await a.none(50); // no error for the dropped one
    await sleep(400);
    a.send({ t: 'pdata', data: POS });
    assert.equal((await b.next()).t, 'pdata');
  } finally { await srv.close(); }
});

test('pdata with nobody else in the room is dropped without an error; a target id reaches only that peer', async () => {
  const { srv, url } = await boot();
  try {
    const solo = client(url); await solo.open();
    solo.send({ t: 'join', code: 'K7M2QX', peer: 'alice' });
    await solo.next();
    solo.send({ t: 'pdata', data: POS });
    await solo.none(200);

    const b = client(url); await b.open();
    b.send({ t: 'join', code: 'K7M2QX', peer: 'bob' });
    await b.next(); await solo.next();
    const c = client(url); await c.open();
    c.send({ t: 'join', code: 'K7M2QX', peer: 'carol' });
    await c.next(); await solo.next(); await b.next();
    await sleep(150);
    solo.send({ t: 'pdata', data: POS, to: 'carol' });
    assert.equal((await c.next()).from, 'alice');
    await b.none(200);
  } finally { await srv.close(); }
});

test('a phone with a session key takes its slot back at once; the partner is not told it left', async () => {
  const { srv, url } = await boot();
  try {
    const { a, b } = await pair(url, 'K7M2QX', { keys: true });
    // alice's network switched: the old socket is still open on the server. A new one arrives with the same id and key.
    const a2 = client(url); await a2.open();
    a2.send({ t: 'join', code: 'K7M2QX', peer: 'alice', key: KEY_A });
    const joined = await a2.next();
    assert.deepEqual(joined, { t: 'joined', you: 'alice', peers: ['bob'] });
    assert.equal((await a.closed()).code, 4000);
    // bob only hears that alice is (back) in the room, never a peer-left
    assert.deepEqual(await b.next(), { t: 'peer-joined', peer: 'alice' });
    await b.none(100);
    // the new socket carries relay traffic, the old one is gone
    await sleep(150);
    a2.send({ t: 'pdata', data: POS });
    assert.equal((await b.next()).from, 'alice');
    assert.equal(srv.stats().rooms, 1);
  } finally { await srv.close(); }
});

test('taking over a slot needs the same key; without keys the old behaviour (id_taken) stays', async () => {
  const { srv, url } = await boot();
  try {
    const { a } = await pair(url, 'K7M2QX', { keys: true });
    const thief = client(url); await thief.open();
    thief.send({ t: 'join', code: 'K7M2QX', peer: 'alice', key: 'some-other-key-0123456789' });
    assert.deepEqual(await thief.next(), { t: 'error', code: 'id_taken' });
    const nokey = client(url); await nokey.open();
    nokey.send({ t: 'join', code: 'K7M2QX', peer: 'alice' });
    assert.deepEqual(await nokey.next(), { t: 'error', code: 'id_taken' });
    a.send({ t: 'pdata', data: POS }); // the real alice is untouched
    await a.none(100);

    const badKey = client(url); await badKey.open();
    badKey.send({ t: 'join', code: 'K7M2QX', peer: 'zed', key: 'short' });
    assert.deepEqual(await badKey.next(), { t: 'error', code: 'bad_key' });

    // legacy clients (no key) behave exactly as before
    const x = client(url); await x.open();
    x.send({ t: 'join', code: 'ZZZZ22', peer: 'alice' });
    assert.equal((await x.next()).t, 'joined');
    const y = client(url); await y.open();
    y.send({ t: 'join', code: 'ZZZZ22', peer: 'alice' });
    assert.deepEqual(await y.next(), { t: 'error', code: 'id_taken' });
  } finally { await srv.close(); }
});

test('after a real disconnect the partner gets peer-left, and the room keeps working for the returning phone', async () => {
  const { srv, url } = await boot();
  try {
    const { a, b } = await pair(url, 'K7M2QX', { keys: true });
    a.close();
    assert.deepEqual(await b.next(), { t: 'peer-left', peer: 'alice' });
    const a2 = client(url); await a2.open();
    a2.send({ t: 'join', code: 'K7M2QX', peer: 'alice', key: KEY_A });
    assert.deepEqual(await a2.next(), { t: 'joined', you: 'alice', peers: ['bob'] });
    assert.deepEqual(await b.next(), { t: 'peer-joined', peer: 'alice' });
  } finally { await srv.close(); }
});

test('the fallback relay keeps the room alive and the server never logs or stores what passes through', async () => {
  const lines = [];
  const { srv, url } = await boot({ logger: (ev, info) => lines.push(JSON.stringify({ ev, info })), idleTtlMs: 600, sweepMs: 60 });
  const origLog = console.log; const origErr = console.error;
  const consoleLines = [];
  console.log = (...a) => consoleLines.push(a.join(' ')); console.error = (...a) => consoleLines.push(a.join(' '));
  try {
    const { a, b } = await pair(url, 'K7M2QX');
    const secret = JSON.stringify({ v: 1, t: 'pos', ts: 1700000000000, lat: 51.5007, lon: -0.1246, steps: 4242, name: 'SECRETNAME' });
    // traffic every 200 ms for longer than the idle TTL: the room must stay open because the relay counts as activity
    for (let i = 0; i < 6; i++) { a.send({ t: 'pdata', data: secret }); await b.next(); await sleep(200); }
    assert.equal(srv.stats().rooms, 1);
    await sleep(100);
  } finally {
    console.log = origLog; console.error = origErr;
    await srv.close();
  }
  const all = lines.join('\n') + consoleLines.join('\n');
  for (const needle of ['51.5007', '0.1246', '4242', 'SECRETNAME', 'K7M2QX', 'alice']) assert.ok(!all.includes(needle), `leaked ${needle}`);
});

test('group avatar codes are optional, validated and shown in the roster and to late joiners', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'host', { av: 21 });
    assert.deepEqual(host.joined.roster, [{ peer: 'host', name: 'host', host: true, av: 21 }]);
    const g2 = client(url); await g2.open();
    g2.send({ t: 'group-join', code: host.code, peer: 'bea', key: 'key-bea-0123456789', name: 'Bea', av: 300 }); // out of range: dropped
    const joined = await g2.next();
    assert.equal(joined.t, 'group-joined');
    assert.deepEqual(joined.roster.find((r) => r.peer === 'bea'), { peer: 'bea', name: 'Bea', host: false });
    assert.deepEqual(await host.c.next(), { t: 'member-joined', peer: 'bea', name: 'Bea', host: false });
    const g3 = client(url); await g3.open();
    g3.send({ t: 'group-join', code: host.code, peer: 'cy', key: 'key-cy-0123456789', name: 'Cy', av: 6 });
    const j3 = await g3.next();
    assert.deepEqual(j3.roster.find((r) => r.peer === 'cy'), { peer: 'cy', name: 'Cy', host: false, av: 6 });
    assert.deepEqual(await host.c.next(), { t: 'member-joined', peer: 'cy', name: 'Cy', host: false, av: 6 });
  } finally { await srv.close(); }
});
