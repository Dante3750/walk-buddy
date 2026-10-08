'use strict';
/**
 * Walk Buddy server. Two kinds of room, both in memory only:
 *
 * 1. PAIR rooms (partner mode): 2-4 phones that share a 6-character code exchange WebRTC offer/answer/ICE messages
 *    and then talk directly. Because a direct link can fail on strict mobile networks (CGNAT, symmetric NAT) and there is
 *    no TURN server, a pair room can also carry a FALLBACK relay (`pdata`): the same tiny partner messages the phones
 *    would send over the data channel, forwarded to the other phone(s) in the room and forgotten. Nothing is stored or logged.
 * 2. GROUP rooms (open group walks): a P2P mesh does not scale to dozens of people, so the server relays small
 *    validated location/step updates to the other members (up to ~50). It keeps no location: an update is
 *    forwarded and forgotten. The only per-room data are member ids, nicknames, host settings and timers.
 *    There is no directory, no listing and no persistence; a room is found only by its code.
 */
const http = require('node:http');
const crypto = require('node:crypto');
const { WebSocketServer } = require('ws');

const CODE_RE = /^[2-9A-HJ-NP-Z]{6}$/;
const PEER_RE = /^[A-Za-z0-9_-]{1,32}$/;
const RELAY_KINDS = new Set(['offer', 'answer', 'ice']);
/** Partner message types that may ride the fallback relay (the same ones the data channel carries). */
const PAIR_DATA_TYPES = new Set(['hello', 'pos', 'ping', 'spot', 'react', 'day', 'pin', 'unpin', 'bye']);
const SERVER_VERSION = 2;
const GROUP_HOST_KINDS = new Set(['approve', 'deny', 'kick', 'close-room', 'settings', 'pin', 'unpin']);
const KEY_RE = /^[A-Za-z0-9_-]{8,64}$/;
const CODE_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const HOST_RE = /^[A-Za-z0-9.-]{1,100}(:\d{1,5})?$/;

/** Strips control and line-separator characters, trims and caps the length. Returns fallback when empty. */
function cleanText(raw, max, fallback) {
  if (typeof raw !== 'string') return fallback;
  const s = raw.replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029\u200b-\u200f\u202a-\u202e]/g, '').trim().slice(0, max);
  return s || fallback;
}
/** An optional small avatar code (style and colour picked by the member); anything else is dropped. */
const cleanAv = (v) => (Number.isInteger(v) && v >= 0 && v <= 255 ? v : undefined);
const num = (v, lo, hi) => (typeof v === 'number' && Number.isFinite(v) && v >= lo && v <= hi ? v : null);

/**
 * Validates one location/step update and rebuilds it with a fixed set of fields, so nothing else can ride along.
 * lat and lon come together or not at all (a member without location permission still shares steps).
 */
function cleanUpdate(d) {
  if (!d || typeof d !== 'object' || Array.isArray(d)) return null;
  const out = {};
  const hasLat = d.lat !== undefined && d.lat !== null;
  const hasLon = d.lon !== undefined && d.lon !== null;
  if (hasLat !== hasLon) return null;
  if (hasLat) {
    const lat = num(d.lat, -90, 90); const lon = num(d.lon, -180, 180);
    if (lat === null || lon === null) return null;
    out.lat = lat; out.lon = lon;
  }
  const steps = d.steps === undefined ? 0 : num(d.steps, 0, 1000000);
  if (steps === null || !Number.isInteger(steps)) return null;
  out.steps = steps;
  for (const [k, lo, hi] of [['ts', 1, 4e12], ['acc', 0, 10000], ['spd', 0, 100], ['cad', 0, 400], ['dist', 0, 1000000]]) {
    if (d[k] === undefined || d[k] === null) continue;
    const v = num(d[k], lo, hi);
    if (v === null) return null;
    out[k] = v;
  }
  return out;
}

const DEFAULTS = {
  port: 8080,
  host: '0.0.0.0',
  maxPeers: 4,
  roomTtlMs: 6 * 60 * 60 * 1000, // hard cap on a room's life
  idleTtlMs: 60 * 60 * 1000, // closed after this long without any relayed message
  sweepMs: 30 * 1000,
  maxMessageBytes: 20 * 1024, // transport-level cap
  maxPayloadChars: 16 * 1024, // SDP / candidate payload cap
  maxPairDataChars: 1200, // one fallback-relay partner message (a position is about 200)
  pairDataMinIntervalMs: 120, // faster than this from one connection is dropped quietly
  joinTimeoutMs: 10 * 1000,
  burst: 60, // per-connection token bucket
  refillPerSec: 20,
  maxBadMessages: 5,
  maxConnsPerIp: 20,
  joinsPerIpPerMin: 20,
  trustProxy: false,
  heartbeatMs: 30 * 1000,
  // ---- group rooms ----
  maxGroupMembers: 50,
  pendingTimeoutMs: 5 * 60 * 1000, // an unanswered join request is dropped
  maxPending: 20, // join requests waiting for host approval
  groupTtlDefaultMin: 240,
  groupTtlMinMin: 15,
  groupTtlMaxMin: 720,
  groupIdleMs: 90 * 60 * 1000, // closed after this long without an update or join
  hostGraceMs: 10 * 60 * 1000, // room closes if the host stays away this long
  updMinIntervalMs: 800, // updates from one member closer together than this are dropped (not an error)
  maxRooms: 5000,
  groupCreatesPerIpPerHour: 12,
  publicUrl: '', // e.g. wss://walk.example.org, used by the /g/CODE invite landing page
  logger: null, // optional (event, aggregateInfo) => void; never receives codes, ids or payloads
};

function createServer(options = {}) {
  const cfg = { ...DEFAULTS, ...options };
  const rooms = new Map(); // code -> pair { kind:'pair', peers: Map(id -> ws), ... } or group { kind:'group', members: Map, ... }
  const createsByIp = new Map(); // ip -> [timestamps] for group creation
  const connsByIp = new Map();
  const joinsByIp = new Map(); // ip -> [timestamps]
  const log = (ev, info) => { if (cfg.logger) cfg.logger(ev, info || {}); };

  const httpServer = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      // Aggregate only. `v` lets you check that a redeploy finished (2 = partner fallback relay + reconnect takeover).
      res.end(JSON.stringify({ ok: true, rooms: rooms.size, v: SERVER_VERSION }));
      return;
    }
    const landing = req.method === 'GET' && /^\/g\/([2-9A-HJ-NP-Za-hj-np-z]{6})\/?$/.exec((req.url || '').split('?')[0]);
    if (landing) {
      res.writeHead(200, {
        'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store',
        'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'", 'referrer-policy': 'no-referrer',
      });
      res.end(landingPage(landing[1].toUpperCase(), serverHint(req)));
      return;
    }
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
  });

  /** The server address to put in the invite link (bare host means wss://). Never reflects an unvalidated Host header. */
  function serverHint(req) {
    if (cfg.publicUrl) return cfg.publicUrl.replace(/^wss:\/\//, '');
    const host = String(req.headers.host || '');
    if (!HOST_RE.test(host)) return '';
    const secure = cfg.trustProxy && String(req.headers['x-forwarded-proto'] || '') === 'https';
    return secure ? host : 'ws://' + host;
  }
  function landingPage(code, hint) {
    const link = 'walkbuddy://group/' + code + (hint ? '?s=' + hint : '');
    const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
    return '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
      '<title>Join a group walk</title><style>body{font-family:system-ui,sans-serif;max-width:28rem;margin:3rem auto;padding:0 1rem;line-height:1.5}' +
      'a.b{display:block;text-align:center;padding:1rem;border-radius:2rem;background:#b3412b;color:#fff;text-decoration:none;font-weight:600}' +
      'code{font-size:2rem;letter-spacing:.3rem}</style></head><body><h1>Join a group walk</h1><p>Group code</p><p><code>' + code + '</code></p>' +
      '<p><a class="b" href="' + esc(link) + '">Open in Walk Buddy</a></p>' +
      '<p>Walk Buddy is an Android app. If it is installed, the button opens it. Otherwise install it, choose "Open group walk", then "Join", and type the code. ' +
      'Your location is shared with the group only while you walk, and you can leave at any time.</p></body></html>';
  }

  const wss = new WebSocketServer({ server: httpServer, maxPayload: cfg.maxMessageBytes });

  const send = (ws, obj) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
  };
  const fail = (ws, code, close = false) => {
    send(ws, { t: 'error', code });
    if (close) ws.close(1008, code);
  };

  function clientIp(req) {
    if (cfg.trustProxy) {
      const xf = req.headers['x-forwarded-for'];
      if (xf) return String(xf).split(',')[0].trim();
    }
    return req.socket.remoteAddress || 'unknown';
  }

  function leave(ws) {
    const room = ws.room && rooms.get(ws.room);
    if (!room) return;
    if (room.kind === 'group') return leaveGroup(ws, room, 'left');
    if (room.peers.get(ws.peerId) === ws) {
      room.peers.delete(ws.peerId);
      room.keys.delete(ws.peerId);
      for (const other of room.peers.values()) send(other, { t: 'peer-left', peer: ws.peerId });
    }
    if (room.peers.size === 0) rooms.delete(ws.room);
    ws.room = null;
  }

  function allow(ws) {
    const now = Date.now();
    ws.tokens = Math.min(cfg.burst, ws.tokens + ((now - ws.lastRefill) / 1000) * cfg.refillPerSec);
    ws.lastRefill = now;
    if (ws.tokens < 1) return false;
    ws.tokens -= 1;
    return true;
  }

  function joinAllowed(ip) {
    const now = Date.now();
    const list = (joinsByIp.get(ip) || []).filter((t) => now - t < 60 * 1000);
    if (list.length >= cfg.joinsPerIpPerMin) { joinsByIp.set(ip, list); return false; }
    list.push(now);
    joinsByIp.set(ip, list);
    return true;
  }

  function handleJoin(ws, msg) {
    if (ws.room) return fail(ws, 'already_joined');
    if (typeof msg.code !== 'string' || !CODE_RE.test(msg.code)) return fail(ws, 'bad_code');
    if (typeof msg.peer !== 'string' || !PEER_RE.test(msg.peer)) return fail(ws, 'bad_peer');
    // Optional per-session secret: it proves "same phone" so a phone whose connection silently died can take its slot back
    // at once instead of waiting for the server to notice the dead socket (which is what used to make reconnects fail).
    let pkey = null;
    if (msg.key !== undefined && msg.key !== null) {
      if (typeof msg.key !== 'string' || !KEY_RE.test(msg.key)) return fail(ws, 'bad_key');
      pkey = msg.key;
    }
    if (!joinAllowed(ws.ip)) return fail(ws, 'too_many_joins', true);
    let room = rooms.get(msg.code);
    if (!room) {
      if (rooms.size >= cfg.maxRooms) return fail(ws, 'server_busy');
      room = { kind: 'pair', peers: new Map(), keys: new Map(), createdAt: Date.now(), lastActive: Date.now() };
      rooms.set(msg.code, room);
    }
    if (room.kind !== 'pair') return fail(ws, 'wrong_mode');
    const old = room.peers.get(msg.peer);
    if (old) {
      if (!pkey || room.keys.get(msg.peer) !== pkey) return fail(ws, 'id_taken');
      // Same phone coming back: take over the slot quietly (the partner is not told the phone left and returned).
      room.peers.delete(msg.peer);
      old.room = null;
      try { old.close(4000, 'replaced'); } catch { /* already gone */ }
    } else if (room.peers.size >= cfg.maxPeers) {
      return fail(ws, 'room_full');
    }
    const existing = [...room.peers.keys()];
    room.peers.set(msg.peer, ws);
    if (pkey) room.keys.set(msg.peer, pkey); else room.keys.delete(msg.peer);
    room.lastActive = Date.now();
    ws.room = msg.code;
    ws.peerId = msg.peer;
    clearTimeout(ws.joinTimer);
    send(ws, { t: 'joined', you: msg.peer, peers: existing });
    for (const other of room.peers.values()) if (other !== ws) send(other, { t: 'peer-joined', peer: msg.peer });
  }

  function handleRelay(ws, msg) {
    const room = ws.room && rooms.get(ws.room);
    if (!room || room.kind !== 'pair') return fail(ws, 'not_joined');
    if (typeof msg.to !== 'string' || typeof msg.data !== 'string') return fail(ws, 'bad_message');
    if (msg.data.length > cfg.maxPayloadChars) return fail(ws, 'payload_too_large');
    const target = room.peers.get(msg.to);
    if (!target || target === ws) return fail(ws, 'unknown_peer');
    room.lastActive = Date.now();
    // Fixed envelope: the server never forwards arbitrary fields.
    send(target, { t: msg.t, from: ws.peerId, to: msg.to, data: msg.data });
  }


  /**
   * Fallback relay for partner messages. The phones normally talk over the WebRTC data channel; when that cannot connect (strict
   * mobile networks without a TURN server) or drops, the same small message goes here and is forwarded to the other phone(s) in the
   * room. It is validated (a known partner message type, a small JSON object), forwarded as is and forgotten: no storage, no logging.
   */
  function handlePairData(ws, msg) {
    const room = ws.room && rooms.get(ws.room);
    if (!room || room.kind !== 'pair') return fail(ws, 'not_joined');
    const data = msg.data;
    if (typeof data !== 'string' || data.length === 0 || data.length > cfg.maxPairDataChars) return fail(ws, 'bad_message');
    let obj;
    try { obj = JSON.parse(data); } catch { obj = null; }
    if (!obj || typeof obj !== 'object' || Array.isArray(obj) || typeof obj.t !== 'string' || !PAIR_DATA_TYPES.has(obj.t)) {
      fail(ws, 'bad_message');
      if (++ws.bad >= cfg.maxBadMessages) ws.close(1008, 'too_many_bad_messages');
      return;
    }
    const now = Date.now();
    if (now - ws.lastPairData < cfg.pairDataMinIntervalMs) return; // too fast: dropped quietly, not an error
    ws.lastPairData = now;
    room.lastActive = now;
    // Fixed envelope; the sender id is stamped by the server.
    const out = JSON.stringify({ t: 'pdata', from: ws.peerId, data });
    if (typeof msg.to === 'string') {
      const target = room.peers.get(msg.to);
      if (target && target !== ws && target.readyState === target.OPEN) target.send(out);
      return;
    }
    for (const other of room.peers.values()) if (other !== ws && other.readyState === other.OPEN) other.send(out);
  }

  // ====================== group rooms ======================

  const gsend = (m, obj) => send(m.ws, obj);
  const connectedMembers = (room) => [...room.members.values()];
  function broadcast(room, obj, exceptId) {
    const text = JSON.stringify(obj);
    for (const [id, m] of room.members) {
      if (id !== exceptId && m.ws.readyState === m.ws.OPEN) m.ws.send(text);
    }
  }
  const publicSettings = (room) => ({ approval: room.approval, goalSteps: room.goalSteps, title: room.title, max: cfg.maxGroupMembers });
  const roster = (room) => [...room.members].map(([peer, m]) => (m.av === undefined
    ? { peer, name: m.name, host: peer === room.hostPeer }
    : { peer, name: m.name, host: peer === room.hostPeer, av: m.av }));
  const expiresInSec = (room) => Math.max(0, Math.round((room.expiresAt - Date.now()) / 1000));

  function newGroupCode() {
    for (let tries = 0; tries < 50; tries++) {
      let c = '';
      for (let i = 0; i < 6; i++) c += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
      if (!rooms.has(c)) return c;
    }
    return null;
  }

  function createAllowed(ip) {
    const now = Date.now();
    const list = (createsByIp.get(ip) || []).filter((t) => now - t < 60 * 60 * 1000);
    if (list.length >= cfg.groupCreatesPerIpPerHour) { createsByIp.set(ip, list); return false; }
    list.push(now);
    createsByIp.set(ip, list);
    return true;
  }

  function closeGroup(code, room, reason, errorCode) {
    for (const m of room.members.values()) {
      send(m.ws, { t: reason }); m.ws.room = null; m.ws.close(1000, reason);
    }
    for (const p of room.pending.values()) { send(p.ws, { t: reason }); p.ws.room = null; p.ws.close(1000, reason); }
    room.members.clear(); room.pending.clear();
    rooms.delete(code);
    if (errorCode) log(errorCode, {});
  }

  function admit(ws, room, code, peer, name, key, av) {
    const wasHost = peer === room.hostPeer;
    room.members.set(peer, { ws, name, key, av });
    room.known.set(peer, key);
    room.lastActive = Date.now();
    ws.room = code; ws.peerId = peer; ws.kind = 'group'; ws.pendingJoin = false; ws.lastUpd = 0;
    clearTimeout(ws.joinTimer);
    send(ws, { t: 'group-joined', code, you: peer, host: room.hostPeer, roster: roster(room), settings: publicSettings(room), expiresInSec: expiresInSec(room) });
    if (wasHost) { room.hostAwaySince = null; broadcast(room, { t: 'host-back' }, peer); }
    broadcast(room, av === undefined ? { t: 'member-joined', peer, name, host: wasHost } : { t: 'member-joined', peer, name, host: wasHost, av }, peer);
  }

  function handleGroupCreate(ws, msg) {
    if (ws.room) return fail(ws, 'already_joined');
    if (typeof msg.peer !== 'string' || !PEER_RE.test(msg.peer)) return fail(ws, 'bad_peer');
    if (typeof msg.key !== 'string' || !KEY_RE.test(msg.key)) return fail(ws, 'bad_key');
    if (!createAllowed(ws.ip)) return fail(ws, 'too_many_rooms', true);
    if (rooms.size >= cfg.maxRooms) return fail(ws, 'server_busy');
    const code = newGroupCode();
    if (!code) return fail(ws, 'server_busy');
    const ttlMin = Math.min(cfg.groupTtlMaxMin, Math.max(cfg.groupTtlMinMin, Number.isFinite(msg.ttlMin) ? Math.round(msg.ttlMin) : cfg.groupTtlDefaultMin));
    const goal = Number.isInteger(msg.goalSteps) && msg.goalSteps >= 0 && msg.goalSteps <= 10000000 ? msg.goalSteps : 0;
    const now = Date.now();
    const room = {
      kind: 'group', members: new Map(), pending: new Map(), known: new Map(), banned: new Set(),
      hostPeer: msg.peer, hostKey: msg.key, approval: msg.approval === true, goalSteps: goal,
      title: cleanText(msg.title, 40, ''), createdAt: now, lastActive: now, expiresAt: now + ttlMin * 60 * 1000, hostAwaySince: null,
    };
    rooms.set(code, room);
    admit(ws, room, code, msg.peer, cleanText(msg.name, 24, 'Host'), msg.key, cleanAv(msg.av));
  }

  function handleGroupJoin(ws, msg) {
    if (ws.room) return fail(ws, 'already_joined');
    if (typeof msg.code !== 'string' || !CODE_RE.test(msg.code)) return fail(ws, 'bad_code');
    if (typeof msg.peer !== 'string' || !PEER_RE.test(msg.peer)) return fail(ws, 'bad_peer');
    if (typeof msg.key !== 'string' || !KEY_RE.test(msg.key)) return fail(ws, 'bad_key');
    if (!joinAllowed(ws.ip)) return fail(ws, 'too_many_joins', true);
    const room = rooms.get(msg.code);
    if (!room) return fail(ws, 'no_such_room');
    if (room.kind !== 'group') return fail(ws, 'wrong_mode');
    const peer = msg.peer;
    const name = cleanText(msg.name, 24, 'Walker');
    if (room.banned.has(peer)) return fail(ws, 'removed');
    const existing = room.members.get(peer);
    const isHostReturn = peer === room.hostPeer && msg.key === room.hostKey;
    if (peer === room.hostPeer && !isHostReturn) return fail(ws, 'id_taken');
    if (existing) {
      // Reconnect: the same phone (same key) takes over its own slot; anyone else cannot.
      if (existing.key !== msg.key) return fail(ws, 'id_taken');
      room.members.delete(peer);
      existing.ws.room = null; existing.ws.close(4000, 'replaced');
    } else if (room.members.size >= cfg.maxGroupMembers) {
      return fail(ws, 'room_full');
    }
    const trusted = isHostReturn || existing || room.known.get(peer) === msg.key;
    if (room.approval && !trusted) {
      if (room.pending.size >= cfg.maxPending) return fail(ws, 'too_many_pending');
      if (room.pending.has(peer)) return fail(ws, 'id_taken');
      room.pending.set(peer, { ws, name, key: msg.key, av: cleanAv(msg.av), at: Date.now() });
      ws.room = msg.code; ws.peerId = peer; ws.kind = 'group'; ws.pendingJoin = true;
      clearTimeout(ws.joinTimer);
      send(ws, { t: 'pending' });
      const host = room.members.get(room.hostPeer);
      if (host) send(host.ws, { t: 'join-request', peer, name });
      return;
    }
    admit(ws, room, msg.code, peer, name, msg.key, cleanAv(msg.av));
  }

  function leaveGroup(ws, room, reason) {
    const peer = ws.peerId;
    if (ws.pendingJoin) {
      if (room.pending.get(peer)?.ws === ws) {
        room.pending.delete(peer);
        const host = room.members.get(room.hostPeer);
        if (host) send(host.ws, { t: 'join-cancelled', peer });
      }
      ws.room = null; ws.pendingJoin = false;
      return;
    }
    if (room.members.get(peer)?.ws === ws) {
      room.members.delete(peer);
      broadcast(room, { t: 'member-left', peer, reason });
      if (peer === room.hostPeer) {
        room.hostAwaySince = Date.now();
        broadcast(room, { t: 'host-away' });
      }
    }
    ws.room = null;
  }

  function handleUpd(ws, msg) {
    const room = ws.room && rooms.get(ws.room);
    if (!room || room.kind !== 'group' || ws.pendingJoin) return fail(ws, 'not_joined');
    const now = Date.now();
    if (now - ws.lastUpd < cfg.updMinIntervalMs) return; // too fast: dropped quietly, not an error
    const d = cleanUpdate(msg.d);
    if (!d) { fail(ws, 'bad_update'); if (++ws.bad >= cfg.maxBadMessages) ws.close(1008, 'too_many_bad_messages'); return; }
    ws.lastUpd = now;
    room.lastActive = now;
    // Forwarded and forgotten: the server keeps no copy.
    broadcast(room, { t: 'upd', from: ws.peerId, d }, ws.peerId);
  }

  function hostOnly(ws) {
    const room = ws.room && rooms.get(ws.room);
    if (!room || room.kind !== 'group' || ws.pendingJoin) { fail(ws, 'not_joined'); return null; }
    if (ws.peerId !== room.hostPeer) { fail(ws, 'not_host'); return null; }
    return room;
  }

  function handleHost(ws, msg) {
    const room = hostOnly(ws);
    if (!room) return;
    const code = ws.room;
    const target = typeof msg.peer === 'string' ? msg.peer : null;
    switch (msg.t) {
      case 'approve': {
        const p = target && room.pending.get(target);
        if (!p) return fail(ws, 'unknown_peer');
        room.pending.delete(target);
        if (room.members.size >= cfg.maxGroupMembers) { send(p.ws, { t: 'error', code: 'room_full' }); p.ws.room = null; p.ws.pendingJoin = false; return fail(ws, 'room_full'); }
        admit(p.ws, room, code, target, p.name, p.key, p.av);
        return;
      }
      case 'deny': {
        const p = target && room.pending.get(target);
        if (!p) return fail(ws, 'unknown_peer');
        room.pending.delete(target);
        send(p.ws, { t: 'denied' }); p.ws.room = null; p.ws.pendingJoin = false; p.ws.close(1000, 'denied');
        return;
      }
      case 'kick': {
        if (!target || target === room.hostPeer) return fail(ws, 'unknown_peer');
        const m = room.members.get(target);
        const p = room.pending.get(target);
        if (!m && !p) return fail(ws, 'unknown_peer');
        room.banned.add(target); room.known.delete(target);
        if (m) {
          room.members.delete(target);
          send(m.ws, { t: 'kicked' }); m.ws.room = null; m.ws.close(1000, 'kicked');
          broadcast(room, { t: 'member-left', peer: target, reason: 'kicked' });
        } else {
          room.pending.delete(target);
          send(p.ws, { t: 'denied' }); p.ws.room = null; p.ws.pendingJoin = false; p.ws.close(1000, 'denied');
        }
        return;
      }
      case 'close-room': return closeGroup(code, room, 'room-closed');
      case 'settings': {
        if (typeof msg.approval === 'boolean') room.approval = msg.approval;
        if (Number.isInteger(msg.goalSteps) && msg.goalSteps >= 0 && msg.goalSteps <= 10000000) room.goalSteps = msg.goalSteps;
        if (typeof msg.title === 'string') room.title = cleanText(msg.title, 40, '');
        broadcast(room, { t: 'settings', settings: publicSettings(room) });
        return;
      }
      case 'pin': {
        const lat = num(msg.lat, -90, 90); const lon = num(msg.lon, -180, 180);
        if (lat === null || lon === null) return fail(ws, 'bad_message');
        broadcast(room, { t: 'pin', lat, lon, label: cleanText(msg.label, 40, '') }, ws.peerId);
        return;
      }
      case 'unpin': broadcast(room, { t: 'unpin' }, ws.peerId); return;
      default: fail(ws, 'unsupported_type');
    }
  }

  wss.on('connection', (ws, req) => {
    ws.ip = clientIp(req);
    const n = (connsByIp.get(ws.ip) || 0) + 1;
    if (n > cfg.maxConnsPerIp) { ws.close(1008, 'too_many_connections'); return; }
    connsByIp.set(ws.ip, n);
    ws.tokens = cfg.burst;
    ws.lastRefill = Date.now();
    ws.bad = 0;
    ws.alive = true;
    ws.room = null;
    ws.kind = null;
    ws.pendingJoin = false;
    ws.lastUpd = 0;
    ws.lastPairData = 0;
    ws.joinTimer = setTimeout(() => { if (!ws.room) ws.close(1008, 'join_timeout'); }, cfg.joinTimeoutMs);

    ws.on('pong', () => { ws.alive = true; });
    ws.on('message', (data, isBinary) => {
      if (isBinary) return fail(ws, 'bad_message', true);
      if (!allow(ws)) return fail(ws, 'rate_limited', true);
      let msg;
      try { msg = JSON.parse(data.toString('utf8')); } catch { msg = null; }
      if (!msg || typeof msg !== 'object' || Array.isArray(msg) || typeof msg.t !== 'string') {
        fail(ws, 'bad_message');
        if (++ws.bad >= cfg.maxBadMessages) ws.close(1008, 'too_many_bad_messages');
        return;
      }
      if (msg.t === 'join') return handleJoin(ws, msg);
      if (msg.t === 'group-create') return handleGroupCreate(ws, msg);
      if (msg.t === 'group-join') return handleGroupJoin(ws, msg);
      if (msg.t === 'upd') return handleUpd(ws, msg);
      if (msg.t === 'pdata') return handlePairData(ws, msg);
      if (GROUP_HOST_KINDS.has(msg.t)) return handleHost(ws, msg);
      if (msg.t === 'leave') { leave(ws); return; }
      if (RELAY_KINDS.has(msg.t)) return handleRelay(ws, msg);
      // Anything else (for example a location or step message) is refused, never forwarded.
      fail(ws, 'unsupported_type');
      if (++ws.bad >= cfg.maxBadMessages) ws.close(1008, 'too_many_bad_messages');
    });
    ws.on('close', () => {
      clearTimeout(ws.joinTimer);
      leave(ws);
      const c = (connsByIp.get(ws.ip) || 1) - 1;
      if (c <= 0) connsByIp.delete(ws.ip); else connsByIp.set(ws.ip, c);
    });
    ws.on('error', () => {});
  });

  const sweeper = setInterval(() => {
    const now = Date.now();
    let expired = 0;
    for (const [code, room] of rooms) {
      if (room.kind === 'group') {
        const hostGone = room.hostAwaySince !== null && now - room.hostAwaySince > cfg.hostGraceMs;
        for (const [pid, p] of room.pending) {
          if (now - p.at > cfg.pendingTimeoutMs) {
            room.pending.delete(pid);
            send(p.ws, { t: 'denied' }); p.ws.room = null; p.ws.pendingJoin = false; p.ws.close(1000, 'denied');
            const host = room.members.get(room.hostPeer);
            if (host) send(host.ws, { t: 'join-cancelled', peer: pid });
          }
        }
        if (now > room.expiresAt || now - room.lastActive > cfg.groupIdleMs || hostGone) {
          closeGroup(code, room, 'room-expired');
          expired++;
        }
        continue;
      }
      if (now - room.createdAt > cfg.roomTtlMs || now - room.lastActive > cfg.idleTtlMs) {
        for (const ws of room.peers.values()) { send(ws, { t: 'error', code: 'room_expired' }); ws.room = null; ws.close(1000, 'room_expired'); }
        rooms.delete(code);
        expired++;
      }
    }
    for (const [ip, list] of createsByIp) {
      const fresh = list.filter((t) => now - t < 60 * 60 * 1000);
      if (fresh.length) createsByIp.set(ip, fresh); else createsByIp.delete(ip);
    }
    for (const [ip, list] of joinsByIp) {
      const fresh = list.filter((t) => now - t < 60 * 1000);
      if (fresh.length) joinsByIp.set(ip, fresh); else joinsByIp.delete(ip);
    }
    if (expired) log('rooms_expired', { count: expired });
  }, cfg.sweepMs);
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.alive) { ws.terminate(); continue; }
      ws.alive = false;
      try { ws.ping(); } catch { /* ignore */ }
    }
  }, cfg.heartbeatMs);
  sweeper.unref(); heartbeat.unref();

  return {
    httpServer,
    config: cfg,
    stats: () => ({ rooms: rooms.size, connections: wss.clients.size }),
    listen: () => new Promise((resolve) => httpServer.listen(cfg.port, cfg.host, () => resolve(httpServer.address().port))),
    close: () => new Promise((resolve) => {
      clearInterval(sweeper); clearInterval(heartbeat);
      for (const ws of wss.clients) ws.terminate();
      wss.close(() => httpServer.close(() => resolve()));
    }),
  };
}

module.exports = { createServer, CODE_RE, PEER_RE, DEFAULTS, PAIR_DATA_TYPES, SERVER_VERSION, cleanUpdate, cleanText };

if (require.main === module) {
  const srv = createServer({
    port: Number(process.env.PORT) || 8080,
    host: process.env.HOST || '0.0.0.0',
    trustProxy: process.env.TRUST_PROXY === '1',
    publicUrl: process.env.PUBLIC_URL || '',
    maxGroupMembers: Number(process.env.MAX_GROUP_MEMBERS) || DEFAULTS.maxGroupMembers,
    roomTtlMs: Number(process.env.ROOM_TTL_MS) || DEFAULTS.roomTtlMs,
    idleTtlMs: Number(process.env.IDLE_TTL_MS) || DEFAULTS.idleTtlMs,
    logger: (ev, info) => console.log(JSON.stringify({ ev, ...info })),
  });
  srv.listen().then((port) => console.log(JSON.stringify({ ev: 'listening', port })));
  const stop = () => srv.close().then(() => process.exit(0));
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
