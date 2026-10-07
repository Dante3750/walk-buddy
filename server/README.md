# Walk Buddy signaling server

A tiny relay that lets two to four phones find each other. Phones that share a 6-character code exchange WebRTC
`offer` / `answer` / `ice` messages through it, then talk **directly** over a WebRTC data channel. Steps, pace and
locations never pass through this server.

- Node 18+, one dependency (`ws`).
- Rooms live in memory only, keyed by code. Max 4 peers per room.
- A room is deleted when it is empty, after 1 hour without relayed messages, or after 6 hours at the latest.
- Relays only `offer`, `answer`, `ice` in a fixed envelope (`{t, from, to, data}`). The sender id is stamped by the
  server. Any other message type (for example something that looks like a location) is refused, not forwarded.
- Limits: 20 KB frame, 16 KB payload, per-connection token bucket (burst 60, 20/s), 20 connections and 20 join
  attempts per minute per IP (so codes cannot be guessed quickly), 10 s to join after connecting.
- Never logs codes, peer ids or payloads. The only log lines are `listening` and `rooms_expired {count}`. `/health`
  returns `{ok, rooms}` (a number, no codes). This is covered by a test.

## Run

```bash
cd server
npm install
npm start            # listens on :8080 (PORT, HOST env vars)
npm test             # 21 tests with real WebSocket clients
```

Environment: `PORT`, `HOST`, `TRUST_PROXY=1` (use `X-Forwarded-For` for per-IP limits behind a reverse proxy),
`ROOM_TTL_MS`, `IDLE_TTL_MS`.

### Docker

```bash
docker build -t walk-buddy-signaling server
docker run -p 8080:8080 walk-buddy-signaling
```

Put it behind TLS (a reverse proxy, Fly.io, Cloud Run, Render ...) and give the app the `wss://` URL in
Settings > Signaling server. Plain `ws://` is fine on a trusted LAN for testing. The app has a "Test connection" button.

## Protocol

Client to server (JSON text frames):

| Message | Meaning |
|---|---|
| `{"t":"join","code":"K7M2QX","peer":"<id>"}` | Join (or create) the room. Code alphabet: `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`. Peer id: `[A-Za-z0-9_-]{1,32}`. |
| `{"t":"offer"\|"answer"\|"ice","to":"<id>","data":"<string>"}` | Relay to one peer in the same room. |
| `{"t":"leave"}` | Leave the room. |

Server to client: `joined {you, peers[]}`, `peer-joined {peer}`, `peer-left {peer}`, relayed `{t, from, to, data}`, and
`error {code}` with one of `bad_code bad_peer room_full id_taken not_joined unknown_peer payload_too_large
rate_limited too_many_joins unsupported_type bad_message room_expired already_joined`.

The Kotlin side of the same protocol lives in `domain/.../Protocol.kt` (`SignalingCodec`).

## Privacy note

Whoever runs the server can see IP addresses that connect and the (opaque) WebRTC session descriptions, which include
network candidates. Run your own instance if you do not want to trust someone else's. WebRTC data channels are
encrypted (DTLS) between the phones.
