# Walk Buddy server

Two jobs, both in memory only: **signaling for partner walks** (below) and **relaying open-group walks** (see
[Open group rooms](#open-group-rooms)).

## Partner walks: signaling

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
npm test             # 49 tests with real WebSocket clients
```

Environment: `PORT`, `HOST`, `TRUST_PROXY=1` (use `X-Forwarded-For` for per-IP limits behind a reverse proxy),
`ROOM_TTL_MS`, `IDLE_TTL_MS`, `PUBLIC_URL` (for example `wss://walk.example.org`, used by the invite landing page), `MAX_GROUP_MEMBERS` (default 50).

### Free hosting (Render, no tunnel needed)

1. Open https://render.com/deploy?repo=https://github.com/Dante3750/walk-buddy and sign in with GitHub.
2. Accept the blueprint (`render.yaml`, free plan). Wait for the first deploy to finish.
3. Your server is `https://walk-buddy-server-XXXX.onrender.com`. The app has no server setting: set `ServerConfig.URL`
   (and `HEALTH_URL`) in `domain/src/main/kotlin/com/walkbuddy/domain/ServerConfig.kt` to the same address with `wss://` and rebuild.
   The official build already points at `wss://walk-buddy-server-sxpz.onrender.com`.

The free plan sleeps after ~15 min idle, so the first connection after a break can take ~30 s.

### Docker

```bash
docker build -t walk-buddy-signaling server
docker run -p 8080:8080 walk-buddy-signaling
```

Put it behind TLS (a reverse proxy, Fly.io, Cloud Run, Render ...) and set `ServerConfig.URL` in the app source to its `wss://`
URL, then rebuild. Check it with `curl https://your-host/health`. The app only talks to the server named in that constant;
invite links cannot override it.

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

## Open group rooms

A peer-to-peer mesh does not scale past a handful of phones, so open groups use the server as a **relay** for small
update messages. Up to 50 members (`maxGroupMembers`). The server never stores a location: an update is validated,
forwarded to the other members and forgotten. What a room holds in memory is only member ids, nicknames, a
per-member reconnect key, the host's settings (approval on/off, step goal, title) and timers. There is no directory,
no search, no listing endpoint and no persistence. A room can be found only by its 6-character code.

Client to server:

| Message | Meaning |
|---|---|
| `{"t":"group-create","peer","key","name","approval":false,"ttlMin":240,"goalSteps":0,"title":""}` | Create a room; the server picks the code (CSPRNG). `key` is a random secret (8-64 chars) that proves "same phone" when reconnecting. Replies with `group-joined`. |
| `{"t":"group-join","code","peer","key","name"}` | Join. With approval on, a new member gets `pending` until the host approves. Known members (same id and key) reconnect without approval and silently take over their old slot. |
| `{"t":"upd","d":{"lat","lon","steps","ts","acc","spd","cad","dist"}}` | A small update, relayed to everyone else as `{t:"upd",from,d}`. lat/lon come together or not at all. Anything else in `d` is dropped. Updates closer than 0.8 s apart are dropped quietly. |
| `{"t":"leave"}` | Leave. |
| Host only: `approve` / `deny` / `kick` `{peer}`, `close-room`, `settings {approval, goalSteps, title}`, `pin {lat, lon, label}`, `unpin` | Anyone else gets `error not_host`. A kicked id cannot rejoin that room. |

Server to client: `group-joined {code, you, host, roster:[{peer,name,host}], settings, expiresInSec}` (the late-join
snapshot: who is here now, no positions), `member-joined`, `member-left {reason}`, `upd`, `settings`, `pin`, `unpin`,
`pending`, `join-request {peer,name}` and `join-cancelled` (host), `denied`, `kicked`, `host-away`, `host-back`,
`room-closed`, `room-expired`, and `error {code}` (adds `wrong_mode no_such_room removed bad_key bad_update not_host
too_many_rooms too_many_pending server_busy` to the partner codes).

The meeting pin is relayed, not stored (the host app re-sends it when someone joins), so the server still holds no
coordinates at all.

Limits and lifetimes: group TTL 15 min to 12 h (default 4 h, chosen by the host); closed after 90 min without an
update; closed 10 min after the host disappears; unanswered join requests dropped after 5 min; at most 20 pending
requests; 12 new groups per IP per hour; the same join-per-minute and connection caps as partner rooms; at most
5000 rooms. Nicknames and titles are stripped of control characters and capped.

`GET /g/ABC234` serves a static invite page (no script, a strict CSP) with an "Open in Walk Buddy" button for the
`walkbuddy://group/ABC234?s=...` link, so an `https://` invite link works in chat apps that do not linkify custom
schemes. It does not check or reveal whether the room exists. Set `PUBLIC_URL` behind a proxy.

## Privacy note

Whoever runs the server can see IP addresses that connect and the (opaque) WebRTC session descriptions, which include
network candidates. **For open groups the operator's process also handles live positions in transit** (it does not
store or log them, a test checks that, but you are trusting the operator not to copy them). Run your own instance for
groups you care about. Run your own instance if you do not want to trust someone else's. WebRTC data channels are
encrypted (DTLS) between the phones.
