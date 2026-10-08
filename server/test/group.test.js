'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { boot, client, key, createGroup, joinGroup, sleep } = require('./util.js');
const { cleanUpdate, cleanText } = require('../server.js');

const POS = { lat: 12.9716, lon: 77.5946, steps: 120, ts: 1700000000000 };
const BIG = { maxConnsPerIp: 1000, joinsPerIpPerMin: 1000, groupCreatesPerIpPerHour: 1000 };

test('creating a group returns a code, the host and a roster of one', async () => {
  const { srv, url } = await boot();
  try {
    const g = await createGroup(url, 'alice', { name: 'Alice', approval: false, title: 'Sunday stroll', goalSteps: 50000, ttlMin: 90 });
    assert.equal(g.joined.t, 'group-joined');
    assert.match(g.code, /^[2-9A-HJ-NP-Z]{6}$/);
    assert.equal(g.joined.you, 'alice');
    assert.equal(g.joined.host, 'alice');
    assert.deepEqual(g.joined.roster, [{ peer: 'alice', name: 'Alice', host: true }]);
    assert.deepEqual(g.joined.settings, { approval: false, goalSteps: 50000, title: 'Sunday stroll', max: 50 });
    assert.ok(g.joined.expiresInSec > 80 * 60 && g.joined.expiresInSec <= 90 * 60);
    assert.equal(srv.stats().rooms, 1);
  } finally { await srv.close(); }
});

test('anyone with the code can join, including late, and gets the current roster snapshot', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice', { name: 'Alice' });
    const bob = await joinGroup(url, host.code, 'bob', { name: 'Bob' });
    assert.equal(bob.first.t, 'group-joined');
    assert.deepEqual(bob.first.roster.map((r) => r.peer).sort(), ['alice', 'bob']);
    assert.deepEqual(await host.c.next(), { t: 'member-joined', peer: 'bob', name: 'Bob', host: false });
    // the walk is already under way when carol arrives
    bob.c.send({ t: 'upd', d: POS });
    assert.equal((await host.c.next()).t, 'upd');
    const carol = await joinGroup(url, host.code, 'carol', { name: 'Carol' });
    assert.deepEqual(carol.first.roster.map((r) => r.peer).sort(), ['alice', 'bob', 'carol']);
    assert.equal((await host.c.next()).peer, 'carol');
    assert.equal((await bob.c.next()).peer, 'carol');
  } finally { await srv.close(); }
});

test('updates are relayed to everyone else with a server-stamped sender and a fixed field set', async () => {
  const { srv, url } = await boot();
  try {
    const a = await createGroup(url, 'alice');
    const b = await joinGroup(url, a.code, 'bob');
    await a.c.next();
    b.c.send({ t: 'upd', from: 'mallory', d: { ...POS, acc: 8, spd: 1.2, cad: 100, dist: 340.5, evil: 'x', nested: { a: 1 } } });
    const m = await a.c.next();
    assert.deepEqual(m, { t: 'upd', from: 'bob', d: { lat: 12.9716, lon: 77.5946, steps: 120, ts: 1700000000000, acc: 8, spd: 1.2, cad: 100, dist: 340.5 } });
    await b.c.none(); // no echo to the sender
  } finally { await srv.close(); }
});

test('the server keeps no location: a late joiner sees none until the next live update', async () => {
  const { srv, url } = await boot();
  try {
    const a = await createGroup(url, 'alice');
    a.c.send({ t: 'upd', d: POS });
    await sleep(60);
    const b = await joinGroup(url, a.code, 'bob');
    assert.equal(JSON.stringify(b.first).includes('12.97'), false);
    for (const r of b.first.roster) assert.deepEqual(Object.keys(r).sort(), ['host', 'name', 'peer']);
    await b.c.none(200);
    await sleep(850); // alice's next update goes out after the rate window
    a.c.send({ t: 'upd', d: { ...POS, steps: 130 } });
    assert.equal((await b.c.next()).d.steps, 130);
  } finally { await srv.close(); }
});

test('invalid updates are refused; too-frequent updates are dropped quietly', async () => {
  const { srv, url } = await boot();
  try {
    const a = await createGroup(url, 'alice');
    const b = await joinGroup(url, a.code, 'bob');
    await a.c.next();
    for (const bad of [{ lat: 200, lon: 0, steps: 1 }, { lat: 10, steps: 1 }, { lat: 'x', lon: 1, steps: 1 }, { lat: 1, lon: 1, steps: -4 }]) {
      b.c.send({ t: 'upd', d: bad });
      assert.deepEqual(await b.c.next(), { t: 'error', code: 'bad_update' });
    }
    await a.c.none(100);
    // a persistent offender is disconnected after a handful
    for (const bad of [{ lat: 1, lon: 1, steps: 1, spd: 900 }]) b.c.send({ t: 'upd', d: bad });
    assert.deepEqual(await b.c.next(), { t: 'error', code: 'bad_update' });
    assert.equal((await b.c.closed()).code, 1008);
  } finally { await srv.close(); }
  const s2 = await boot();
  try {
    const a = await createGroup(s2.url, 'alice');
    const b = await joinGroup(s2.url, a.code, 'bob');
    await a.c.next();
    b.c.send({ t: 'upd', d: POS });
    b.c.send({ t: 'upd', d: { ...POS, steps: 999 } });
    b.c.send({ t: 'upd', d: { ...POS, steps: 998 } });
    assert.equal((await a.c.next()).d.steps, 120);
    await a.c.none(250); // the two fast ones were dropped
    await b.c.none(50);  // and the sender was not told off
  } finally { await s2.srv.close(); }
});

test('a member without location permission can still share steps', async () => {
  const { srv, url } = await boot();
  try {
    const a = await createGroup(url, 'alice');
    const b = await joinGroup(url, a.code, 'bob');
    await a.c.next();
    b.c.send({ t: 'upd', d: { steps: 42 } });
    assert.deepEqual(await a.c.next(), { t: 'upd', from: 'bob', d: { steps: 42 } });
  } finally { await srv.close(); }
});

test('fifty members fit, the fifty-first is refused, and updates reach everyone', async () => {
  const { srv, url } = await boot({ ...BIG });
  const all = [];
  try {
    const host = await createGroup(url, 'p0');
    all.push(host);
    for (let i = 1; i < 50; i++) {
      const m = await joinGroup(url, host.code, 'p' + i);
      assert.equal(m.first.t, 'group-joined', `member ${i}`);
      all.push(m);
    }
    assert.equal(all[49].first.roster.length, 50);
    const extra = await joinGroup(url, host.code, 'p50');
    assert.deepEqual(extra.first, { t: 'error', code: 'room_full' });
    // drain join notices on the host, then broadcast from the last member
    for (let i = 1; i < 50; i++) await host.c.next();
    all[49].c.send({ t: 'upd', d: POS });
    const got = await Promise.all(all.slice(0, 49).map((m) => m.c.until('upd', 3000)));
    assert.equal(got.length, 49);
    assert.ok(got.every((g) => g.from === 'p49'));
  } finally { all.forEach((m) => m.c.close()); await srv.close(); }
});

test('optional approval: host gets a request, approves or denies; known members reconnect without it', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice', { approval: true });
    const bob = await joinGroup(url, host.code, 'bob', { name: 'Bob' });
    assert.deepEqual(bob.first, { t: 'pending' });
    assert.deepEqual(await host.c.next(), { t: 'join-request', peer: 'bob', name: 'Bob' });
    bob.c.send({ t: 'upd', d: POS });
    assert.deepEqual(await bob.c.next(), { t: 'error', code: 'not_joined' }); // a pending member cannot post
    host.c.send({ t: 'approve', peer: 'bob' });
    assert.equal((await bob.c.next()).t, 'group-joined');
    assert.equal((await host.c.next()).t, 'member-joined');

    const eve = await joinGroup(url, host.code, 'eve');
    assert.equal(eve.first.t, 'pending');
    await host.c.next();
    host.c.send({ t: 'deny', peer: 'eve' });
    assert.deepEqual(await eve.c.next(), { t: 'denied' });
    assert.equal((await eve.c.closed()).code, 1000);

    // bob's connection drops and he comes back: no second approval
    bob.c.close();
    assert.equal((await host.c.next()).t, 'member-left');
    const bob2 = await joinGroup(url, host.code, 'bob', { name: 'Bob' });
    assert.equal(bob2.first.t, 'group-joined');
  } finally { await srv.close(); }
});

test('only the host can approve, kick, close or change settings', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice');
    const bob = await joinGroup(url, host.code, 'bob');
    await host.c.next();
    for (const m of [{ t: 'kick', peer: 'alice' }, { t: 'close-room' }, { t: 'settings', approval: true }, { t: 'approve', peer: 'x' }, { t: 'pin', lat: 1, lon: 1 }]) {
      bob.c.send(m);
      assert.deepEqual(await bob.c.next(), { t: 'error', code: 'not_host' }, m.t);
    }
    await host.c.none(100);
  } finally { await srv.close(); }
});

test('kick removes a member, tells the others and blocks that id from returning', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice');
    const bob = await joinGroup(url, host.code, 'bob');
    const carol = await joinGroup(url, host.code, 'carol');
    await host.c.next(); await host.c.next(); await bob.c.next();
    host.c.send({ t: 'kick', peer: 'bob' });
    assert.deepEqual(await bob.c.next(), { t: 'kicked' });
    assert.equal((await bob.c.closed()).code, 1000);
    assert.deepEqual(await carol.c.next(), { t: 'member-left', peer: 'bob', reason: 'kicked' });
    assert.deepEqual(await host.c.next(), { t: 'member-left', peer: 'bob', reason: 'kicked' });
    const again = await joinGroup(url, host.code, 'bob');
    assert.deepEqual(again.first, { t: 'error', code: 'removed' });
    host.c.send({ t: 'kick', peer: 'alice' });
    assert.deepEqual(await host.c.next(), { t: 'error', code: 'unknown_peer' }); // the host cannot kick itself
  } finally { await srv.close(); }
});

test('close-room tells everyone and forgets the code', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice', { approval: true });
    const bob = await joinGroup(url, host.code, 'bob');
    await host.c.next();
    host.c.send({ t: 'approve', peer: 'bob' });
    await bob.c.next();
    const pend = await joinGroup(url, host.code, 'zed');
    host.c.send({ t: 'close-room' });
    assert.deepEqual(await bob.c.until('room-closed'), { t: 'room-closed' });
    assert.deepEqual(await pend.c.next(), { t: 'room-closed' });
    await bob.c.closed();
    assert.equal(srv.stats().rooms, 0);
    const late = await joinGroup(url, host.code, 'late');
    assert.deepEqual(late.first, { t: 'error', code: 'no_such_room' });
  } finally { await srv.close(); }
});

test('host settings are broadcast and the meeting pin is relayed, not stored', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice');
    const bob = await joinGroup(url, host.code, 'bob');
    await host.c.next();
    host.c.send({ t: 'settings', approval: true, goalSteps: 30000, title: 'Lake loop' });
    const s = await bob.c.next();
    assert.deepEqual(s, { t: 'settings', settings: { approval: true, goalSteps: 30000, title: 'Lake loop', max: 50 } });
    assert.deepEqual(await host.c.next(), s);
    host.c.send({ t: 'pin', lat: 12.95, lon: 77.6, label: 'Gate 2\u0007' });
    assert.deepEqual(await bob.c.next(), { t: 'pin', lat: 12.95, lon: 77.6, label: 'Gate 2' });
    await host.c.none(60);
    const carol = await joinGroup(url, host.code, 'carol');
    await carol.c.none(150); // no pin replay: the host app re-sends it when someone joins
    host.c.send({ t: 'pin', lat: 99, lon: 1 });
    assert.deepEqual(await host.c.until('error'), { t: 'error', code: 'bad_message' });
  } finally { await srv.close(); }
});

test('TTL: a group closes when its time is up and everyone is told', async () => {
  const { srv, url } = await boot({ groupTtlMinMin: 0.0005, groupTtlDefaultMin: 0.001, sweepMs: 40 });
  try {
    const host = await createGroup(url, 'alice');
    const bob = await joinGroup(url, host.code, 'bob');
    assert.ok(host.joined.expiresInSec <= 1);
    assert.deepEqual(await bob.c.until('room-expired', 2000), { t: 'room-expired' });
    await bob.c.closed();
    assert.equal(srv.stats().rooms, 0);
  } finally { await srv.close(); }
});

test('TTL requests are clamped to the allowed range', async () => {
  const { srv, url } = await boot();
  try {
    const lo = await createGroup(url, 'a1', { ttlMin: 1 });
    assert.ok(lo.joined.expiresInSec >= 14 * 60 && lo.joined.expiresInSec <= 15 * 60);
    const hi = await createGroup(url, 'a2', { ttlMin: 99999 });
    assert.ok(hi.joined.expiresInSec <= 720 * 60 && hi.joined.expiresInSec > 719 * 60);
  } finally { await srv.close(); }
});

test('idle groups expire', async () => {
  const { srv, url } = await boot({ groupIdleMs: 120, sweepMs: 40 });
  try {
    const host = await createGroup(url, 'alice');
    assert.deepEqual(await host.c.until('room-expired', 2000), { t: 'room-expired' });
  } finally { await srv.close(); }
});

test('host drops: others are told, the group carries on, and the host can come back with its key', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice');
    const bob = await joinGroup(url, host.code, 'bob');
    await host.c.next();
    host.c.close();
    assert.deepEqual(await bob.c.next(), { t: 'member-left', peer: 'alice', reason: 'left' });
    assert.deepEqual(await bob.c.next(), { t: 'host-away' });
    const imposter = client(url); await imposter.open();
    imposter.send({ t: 'group-join', code: host.code, peer: 'alice', key: 'some-other-key-123', name: 'Mallory' });
    assert.deepEqual(await imposter.next(), { t: 'error', code: 'id_taken' });
    const back = client(url); await back.open();
    back.send({ t: 'group-join', code: host.code, peer: 'alice', key: key('alice'), name: 'Alice' });
    const j = await back.next();
    assert.equal(j.host, 'alice');
    assert.deepEqual(await bob.c.next(), { t: 'host-back' });
    assert.deepEqual(await bob.c.next(), { t: 'member-joined', peer: 'alice', name: 'Alice', host: true });
    back.send({ t: 'kick', peer: 'bob' }); // host powers are back
    assert.equal((await bob.c.next()).t, 'kicked');
  } finally { await srv.close(); }
});

test('a group whose host stays away too long is closed', async () => {
  const { srv, url } = await boot({ hostGraceMs: 100, sweepMs: 40 });
  try {
    const host = await createGroup(url, 'alice');
    const bob = await joinGroup(url, host.code, 'bob');
    await host.c.next();
    host.c.close();
    assert.deepEqual(await bob.c.until('room-expired', 2000), { t: 'room-expired' });
  } finally { await srv.close(); }
});

test('the same phone (same key) can take over its own slot after a network change', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice');
    const bob = await joinGroup(url, host.code, 'bob');
    await host.c.next();
    const stranger = await joinGroup(url, host.code, 'bob', { key: 'another-key-12345' });
    assert.deepEqual(stranger.first, { t: 'error', code: 'id_taken' });
    const bob2 = client(url); await bob2.open();
    bob2.send({ t: 'group-join', code: host.code, peer: 'bob', key: key('bob'), name: 'Bob' });
    assert.equal((await bob2.next()).t, 'group-joined');
    assert.equal((await bob.c.closed()).code, 4000);
    const m = await host.c.next();
    assert.equal(m.t, 'member-joined'); // no member-left for a silent takeover
    assert.equal(srv.stats().connections >= 2, true);
  } finally { await srv.close(); }
});

test('nicknames and titles are sanitised', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice', { name: '  Al\u0000ice‮\nSmith-with-a-very-long-name-indeed  ', title: '<b>\u0001Hi</b>' });
    assert.equal(host.joined.roster[0].name, 'AliceSmith-with-a-very-l');
    assert.equal(host.joined.settings.title, '<b>Hi</b>'); // text only; the client never renders it as markup
    const bob = await joinGroup(url, host.code, 'bob', { name: '   ' });
    assert.equal(bob.first.roster.find((r) => r.peer === 'bob').name, 'Walker');
  } finally { await srv.close(); }
});

test('bad ids, keys and codes are rejected', async () => {
  const { srv, url } = await boot();
  try {
    let c = client(url); await c.open();
    c.send({ t: 'group-create', peer: 'bad id!', key: key('x') });
    assert.deepEqual(await c.next(), { t: 'error', code: 'bad_peer' });
    c.send({ t: 'group-create', peer: 'ok', key: 'short' });
    assert.deepEqual(await c.next(), { t: 'error', code: 'bad_key' });
    c.send({ t: 'group-join', code: 'abc', peer: 'ok', key: key('x') });
    assert.deepEqual(await c.next(), { t: 'error', code: 'bad_code' });
    c.send({ t: 'group-join', code: 'K7M2QX', peer: 'ok', key: key('x') });
    assert.deepEqual(await c.next(), { t: 'error', code: 'no_such_room' });
  } finally { await srv.close(); }
});

test('group creation per IP is limited and join guessing is limited', async () => {
  const { srv, url } = await boot({ groupCreatesPerIpPerHour: 2, maxConnsPerIp: 100 });
  try {
    await createGroup(url, 'a1'); await createGroup(url, 'a2');
    const c = client(url); await c.open();
    c.send({ t: 'group-create', peer: 'a3', key: key('a3') });
    assert.deepEqual(await c.next(), { t: 'error', code: 'too_many_rooms' });
    assert.equal((await c.closed()).code, 1008);
  } finally { await srv.close(); }
  const s2 = await boot({ joinsPerIpPerMin: 3, maxConnsPerIp: 100 });
  try {
    const out = [];
    for (let i = 0; i < 4; i++) { const r = await joinGroup(s2.url, 'K7M2QX', 'g' + i); out.push(r.first.code); }
    assert.deepEqual(out, ['no_such_room', 'no_such_room', 'no_such_room', 'too_many_joins']);
  } finally { await s2.srv.close(); }
});

test('pending requests are capped', async () => {
  const { srv, url } = await boot({ maxPending: 2, ...BIG });
  try {
    const host = await createGroup(url, 'alice', { approval: true });
    assert.equal((await joinGroup(url, host.code, 'a1')).first.t, 'pending');
    assert.equal((await joinGroup(url, host.code, 'a2')).first.t, 'pending');
    assert.deepEqual((await joinGroup(url, host.code, 'a3')).first, { t: 'error', code: 'too_many_pending' });
  } finally { await srv.close(); }
});

test('an unanswered request is dropped after the timeout', async () => {
  const { srv, url } = await boot({ pendingTimeoutMs: 80, sweepMs: 30 });
  try {
    const host = await createGroup(url, 'alice', { approval: true });
    const p = await joinGroup(url, host.code, 'bob');
    await host.c.next();
    assert.deepEqual(await p.c.until('denied', 2000), { t: 'denied' });
    assert.deepEqual(await host.c.next(), { t: 'join-cancelled', peer: 'bob' });
  } finally { await srv.close(); }
});

test('partner (pair) rooms and group rooms never mix', async () => {
  const { srv, url } = await boot();
  try {
    const host = await createGroup(url, 'alice');
    const c = client(url); await c.open();
    c.send({ t: 'join', code: host.code, peer: 'bob' });
    assert.deepEqual(await c.next(), { t: 'error', code: 'wrong_mode' });
    const p = client(url); await p.open();
    p.send({ t: 'join', code: 'K7M2QX', peer: 'pa' });
    await p.next();
    const g = await joinGroup(url, 'K7M2QX', 'gb');
    assert.deepEqual(g.first, { t: 'error', code: 'wrong_mode' });
    // a group member cannot use the pair relay, and a pair peer cannot post group updates
    host.c.send({ t: 'offer', to: 'pa', data: 'x' });
    assert.deepEqual(await host.c.next(), { t: 'error', code: 'not_joined' });
    p.send({ t: 'upd', d: POS });
    assert.deepEqual(await p.next(), { t: 'error', code: 'not_joined' });
  } finally { await srv.close(); }
});

test('there is no directory: no listing endpoints, health is a bare count', async () => {
  const { srv, url, http: base } = await boot();
  try {
    await createGroup(url, 'alice');
    for (const p of ['/groups', '/rooms', '/g', '/g/', '/api/groups', '/list', '/g/abc']) assert.equal((await fetch(base + p)).status, 404, p);
    assert.deepEqual(await (await fetch(`${base}/health`)).json(), { ok: true, rooms: 1, v: 2 });
  } finally { await srv.close(); }
});

test('invite landing page: static, escapes the server hint, and does not say whether a room exists', async () => {
  const { srv, port } = await boot();
  const get = (path, host) => new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port, path, headers: { host } }, (res) => {
      let b = ''; res.on('data', (d) => { b += d; }); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: b }));
    }).on('error', reject);
  });
  try {
    const ok = await get('/g/k7m2qx', 'walk.example.org');
    assert.equal(ok.status, 200);
    assert.match(ok.body, /walkbuddy:\/\/group\/K7M2QX\?s=ws:\/\/walk\.example\.org/);
    assert.ok(!/<script/i.test(ok.body));
    assert.match(ok.headers['content-security-policy'], /default-src 'none'/);
    const evil = await get('/g/K7M2QX', '"><script>alert(1)</script>');
    assert.equal(evil.status, 200);
    assert.ok(!evil.body.includes('alert(1)'));
    assert.match(evil.body, /href="walkbuddy:\/\/group\/K7M2QX"/);
  } finally { await srv.close(); }
  const pub = await boot({ publicUrl: 'wss://walk.example.org' });
  try {
    const r = await fetch(`${pub.http}/g/K7M2QX`);
    assert.match(await r.text(), /\?s=walk\.example\.org/);
  } finally { await pub.srv.close(); }
});

test('the server never logs codes, peer ids, names or locations for group rooms', async () => {
  const lines = [];
  const { srv, url } = await boot({ logger: (ev, info) => lines.push(JSON.stringify({ ev, info })), groupTtlMinMin: 0.0005, groupTtlDefaultMin: 0.002, sweepMs: 40 });
  const origLog = console.log; const origErr = console.error;
  const consoleLines = [];
  console.log = (...a) => consoleLines.push(a.join(' ')); console.error = (...a) => consoleLines.push(a.join(' '));
  let code;
  try {
    const host = await createGroup(url, 'secrethost', { name: 'SecretName' });
    code = host.code;
    const b = await joinGroup(url, host.code, 'otherpeer', { name: 'OtherName' });
    b.c.send({ t: 'upd', d: { lat: 12.34567, lon: 77.12345, steps: 9 } });
    await host.c.until('upd');
    await sleep(300);
  } finally {
    console.log = origLog; console.error = origErr;
    await srv.close();
  }
  const all = lines.join('\n') + consoleLines.join('\n');
  for (const needle of [code, 'secrethost', 'otherpeer', 'SecretName', 'OtherName', '12.34567', '77.12345']) assert.ok(!all.includes(needle), `leaked ${needle}`);
});

test('cleanUpdate and cleanText helpers', () => {
  assert.deepEqual(cleanUpdate({ steps: 5 }), { steps: 5 });
  assert.deepEqual(cleanUpdate({}), { steps: 0 });
  assert.equal(cleanUpdate({ lat: 1, lon: 1, steps: 'x' }), null);
  assert.equal(cleanUpdate({ lat: NaN, lon: 1 }), null);
  assert.equal(cleanUpdate([]), null);
  assert.equal(cleanText(5, 10, 'x'), 'x');
  assert.equal(cleanText('  hi\u0000 ', 10, 'x'), 'hi');
});
