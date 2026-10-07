'use strict';
/**
 * Walk Buddy signaling server.
 *
 * Does exactly one job: lets 2-4 phones that share a 6-character code exchange WebRTC offer/answer/ICE messages.
 * After that the phones talk directly. It never sees, stores or logs step counts or locations (those never come
 * here), keeps rooms only in memory, and forgets them when they are empty or expired.
 */
const http = require('node:http');
const { WebSocketServer } = require('ws');

const CODE_RE = /^[2-9A-HJ-NP-Z]{6}$/;
const PEER_RE = /^[A-Za-z0-9_-]{1,32}$/;
const RELAY_KINDS = new Set(['offer', 'answer', 'ice']);

const DEFAULTS = {
  port: 8080,
  host: '0.0.0.0',
  maxPeers: 4,
  roomTtlMs: 6 * 60 * 60 * 1000, // hard cap on a room's life
  idleTtlMs: 60 * 60 * 1000, // closed after this long without any relayed message
  sweepMs: 30 * 1000,
  maxMessageBytes: 20 * 1024, // transport-level cap
  maxPayloadChars: 16 * 1024, // SDP / candidate payload cap
  joinTimeoutMs: 10 * 1000,
  burst: 60, // per-connection token bucket
  refillPerSec: 20,
  maxBadMessages: 5,
  maxConnsPerIp: 20,
  joinsPerIpPerMin: 20,
  trustProxy: false,
  heartbeatMs: 30 * 1000,
  logger: null, // optional (event, aggregateInfo) => void; never receives codes, ids or payloads
};

function createServer(options = {}) {
  const cfg = { ...DEFAULTS, ...options };
  const rooms = new Map(); // code -> { peers: Map(id -> ws), createdAt, lastActive }
  const connsByIp = new Map();
  const joinsByIp = new Map(); // ip -> [timestamps]
  const log = (ev, info) => { if (cfg.logger) cfg.logger(ev, info || {}); };

  const httpServer = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
      return;
    }
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
  });

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
    if (room.peers.get(ws.peerId) === ws) {
      room.peers.delete(ws.peerId);
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
    if (!joinAllowed(ws.ip)) return fail(ws, 'too_many_joins', true);
    let room = rooms.get(msg.code);
    if (!room) {
      room = { peers: new Map(), createdAt: Date.now(), lastActive: Date.now() };
      rooms.set(msg.code, room);
    }
    if (room.peers.size >= cfg.maxPeers) return fail(ws, 'room_full');
    if (room.peers.has(msg.peer)) return fail(ws, 'id_taken');
    const existing = [...room.peers.keys()];
    room.peers.set(msg.peer, ws);
    room.lastActive = Date.now();
    ws.room = msg.code;
    ws.peerId = msg.peer;
    clearTimeout(ws.joinTimer);
    send(ws, { t: 'joined', you: msg.peer, peers: existing });
    for (const other of room.peers.values()) if (other !== ws) send(other, { t: 'peer-joined', peer: msg.peer });
  }

  function handleRelay(ws, msg) {
    const room = ws.room && rooms.get(ws.room);
    if (!room) return fail(ws, 'not_joined');
    if (typeof msg.to !== 'string' || typeof msg.data !== 'string') return fail(ws, 'bad_message');
    if (msg.data.length > cfg.maxPayloadChars) return fail(ws, 'payload_too_large');
    const target = room.peers.get(msg.to);
    if (!target || target === ws) return fail(ws, 'unknown_peer');
    room.lastActive = Date.now();
    // Fixed envelope: the server never forwards arbitrary fields.
    send(target, { t: msg.t, from: ws.peerId, to: msg.to, data: msg.data });
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
      if (now - room.createdAt > cfg.roomTtlMs || now - room.lastActive > cfg.idleTtlMs) {
        for (const ws of room.peers.values()) { send(ws, { t: 'error', code: 'room_expired' }); ws.room = null; ws.close(1000, 'room_expired'); }
        rooms.delete(code);
        expired++;
      }
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

module.exports = { createServer, CODE_RE, PEER_RE, DEFAULTS };

if (require.main === module) {
  const srv = createServer({
    port: Number(process.env.PORT) || 8080,
    host: process.env.HOST || '0.0.0.0',
    trustProxy: process.env.TRUST_PROXY === '1',
    roomTtlMs: Number(process.env.ROOM_TTL_MS) || DEFAULTS.roomTtlMs,
    idleTtlMs: Number(process.env.IDLE_TTL_MS) || DEFAULTS.idleTtlMs,
    logger: (ev, info) => console.log(JSON.stringify({ ev, ...info })),
  });
  srv.listen().then((port) => console.log(JSON.stringify({ ev: 'listening', port })));
  const stop = () => srv.close().then(() => process.exit(0));
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
