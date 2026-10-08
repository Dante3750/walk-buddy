'use strict';
const WebSocket = require('ws');
const { createServer } = require('../server.js');

async function boot(opts = {}) {
  const srv = createServer({ port: 0, host: '127.0.0.1', ...opts });
  const port = await srv.listen();
  return { srv, url: `ws://127.0.0.1:${port}`, http: `http://127.0.0.1:${port}`, port };
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
    /** Waits for a message of the given type, skipping others. */
    until: async (type, ms = 1500) => {
      for (;;) { const m = await api.next(ms); if (m.t === type) return m; }
    },
    none: (ms = 150) => new Promise((res, rej) => setTimeout(() => (queue.length ? rej(new Error('unexpected ' + JSON.stringify(queue[0]))) : res()), ms)),
    closed: () => new Promise((res) => (ws.readyState === ws.CLOSED ? res({ code: ws.closeCode }) : ws.once('close', (code, reason) => res({ code, reason: reason.toString() })))),
    close: () => ws.close(),
  };
  return api;
}

const key = (n) => `key-${n}-0123456789`;

/** Creates a group and returns { c, joined, code }. */
async function createGroup(url, peer = 'host', extra = {}) {
  const c = client(url);
  await c.open();
  c.send({ t: 'group-create', peer, key: key(peer), name: peer, ...extra });
  const joined = await c.next();
  return { c, joined, code: joined.code, peer };
}

async function joinGroup(url, code, peer, extra = {}) {
  const c = client(url);
  await c.open();
  c.send({ t: 'group-join', code, peer, key: key(peer), name: peer, ...extra });
  const first = await c.next();
  return { c, first, peer };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = { boot, client, key, createGroup, joinGroup, sleep };
