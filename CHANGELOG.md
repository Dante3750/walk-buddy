# Changelog

## Unreleased (docs and CI only, no app change)

- CI: after Paparazzi renders, the `screenshots` job copies a curated, downscaled set of PNGs to `docs/screens/` and commits them to `main` (`[skip ci]`, only on push to main, only when they changed, `git pull --rebase` first, one publish at a time). No branch or tag is created.
- README: screenshots refreshed for alpha 2.0 (home, live walk, Track themes, group, history, challenges, Self-check, Battery card, settings, walk summary); status badge and notes corrected; server README test count and wake-up time corrected.

## alpha 2.0 (2.0.0-alpha)

In-app improvements, no server change. Compiled and tested on CI (domain 515 tests, server 60 tests); nothing was run on a phone.

- Self-check screen, redacted local diagnostics log with Export diagnostics (share sheet, no upload).
- Resume a walk after the app is killed (snapshot every ~20 s, resume or discard on launch).
- Meeting-point navigation (distance, bearing, ETA) and live compass arrow; group pins are host-only.
- Local challenges (Room, database 4 to 5 with a migration), couple streaks with rest tokens, celebration.
- Walk reminders with snooze, can't today and skip; pace coaching policy.
- Opt-in weather suggestion (Open-Meteo, rounded to 0.1 degree, cached 3 h, no background polling).
- Track themes, avatar accessories, shareable summary card (route hidden by default), walk notes and photos.
- Widget partner line, Android 16 live-update chip, opt-in Health Connect compare and write.
- Hindi strings for all new screens, in-app language picker, locales config.
- Release readiness: R8, env or local.properties signing with debug fallback, keystore script and guide, CI release artifact, About screen with manual update check.
- Paparazzi screenshot coverage for the new screens.
- Skipped: TURN relay and an always-on server (server and hosting are unchanged).

## alpha 1.8 (1.8.0-alpha)

Partner link rewrite, Track view, Walks together history, map fixes and a bug hunt. Compiled and tested on CI (domain 408 tests, server 60 tests); nothing was run on a phone.

Root causes found (alpha 1.7 behaviour):

- Link drops: only two STUN servers; the channel's `open` flag was never set again after a drop; no ICE restart, watchdog or network-change handling; the server's `peer-left` disposed a healthy direct channel; signalling reconnects gave up after 6 tries; there was no second path.
- "The map does not work": a 30 m accuracy gate dropped weak or approximate fixes, so there was no dot and nothing was shared; the buddy's position was erased whenever the link dropped; a 60 m display limit hid most dots; follow-me stayed at world zoom; approximate-only location permission was treated as denied; GPS and network providers were only requested on plan changes; an empty map gave no explanation.

What changed:

- Layered link: direct WebRTC with ICE restart, more STUN servers, continual gathering, watchdog with backoff and jitter, network-change restart; automatic hot-standby relay through the server with sequence numbers and de-duplication; link indicator (Direct, Via server, Reconnecting...) and last-seen age; walks always recorded locally. Server version 2: `pdata` relay, session keys, avatar codes, `/health` shows `v:2`.
- New domain code with tests: `LinkQuality`, `LinkWatchdog`, `LinkRoute`, `SeqGate`, track layout and camera, track builders, polyline, shared-walk recorder, codec, replay, history grouping and export.
- Track view with original walkers (boy, girl, neutral; choose avatar and colour in Settings or the lobby), Track / Map / Overview switch.
- Walks together: new Room table (database 3 to 4 with a migration), history by month, detail with replay, delete and clear, GPX and JSON export, from Home and Settings.
- Map: fixes above, a notice card that says why the map is empty and how to fix it.

Notable bug fixes:

- Group walks lost their saved route because the recorder was cleared before the async save.
- `!!` and `lateinit` risks removed in the peer link, notices, map labels and lobby.
- Back button: leaving a lobby, moving a running walk to the background and finishing from the summary now behave predictably.
- Deleting all data also clears saved shared walks.

Not verified: any device behaviour, the migration on a real older database, relay behaviour between two real phones on strict NAT. A walk is not restored if Android kills the app mid-walk. The server must redeploy (Render auto-deploys from main): check https://walk-buddy-server-sxpz.onrender.com/health shows `"v":2`.

## alpha 1.7 (1.7.0-alpha)

Battery and resource audit of the whole app, then fixes. No device was available: every claim below comes from reading the code and Android's power documentation, none is measured.

Top findings (alpha 1.6 behaviour):

- Step sensor batching was only 60 s, the service woke the CPU every minute just to ask what day it is, and the notification and widget path (including a read of the whole history) ran on every step write.
- A 15 minute `ELAPSED_REALTIME_WAKEUP` alarm and a 15 minute WorkManager job did the same work (two wake-ups for one result).
- Walks asked for GPS and network location every second with no distance filter, on both providers, whatever the screen or movement; the group sent every 3 s and the partner every 2 s, plus a full history read every 10 s for the partner's daily ring.
- Reconnects were a fixed linear delay with no jitter and no knowledge of the network; every WebSocket built its own OkHttp client.
- The accelerometer fallback streamed at game rate from a foreground service all day.
- UI: the flame and the walking glow re-composed their whole composable at display rate, forever; Settings polled every 2 s; trend, recap, badge and fuel flows were recomputed on every database write even with the app in the background.

What changed:

- New pure-Kotlin `SamplingPolicy` (domain, with `PowerState`, `MotionGate`, `SendGate`, `Backoff`, `StepNotifyThrottle`, `StepBuckets`, `BatteryCopy`; 33 new tests, domain total 340). One place decides sensor latency, location rate, send rate, loop rate, notification and widget spacing from screen visibility, walk, movement, group size, Android Battery Saver, the new Battery saver switch and charging. `PowerMonitor` feeds it using three rare system broadcasts (not `ACTION_BATTERY_CHANGED`).
- Steps: report latency 5 min with the screen off (10 min in saver, 60 s on a charger, immediate with the app open, 5-10 s during a walk). Event bursts are merged per clock hour before storage and the timestamp tolerance was widened to match, so hourly and midnight attribution stay exact. The accelerometer fallback is registered only with the screen on or during a walk, and no foreground service is started for it.
- Safety nets: the alarm is gone (and cancelled on update); one WorkManager job, 30 min with a 10 min flex window, battery-not-low, runs the sample, widget and reminders and skips runs that come too soon. `BootReceiver` is no longer exported. No wake locks, alarms, exact alarms or `JobScheduler` anywhere.
- `StepService`: no minute loop (date, time and time-zone broadcasts instead), a repeated start no longer restarts the pipeline, the notification is throttled (about 100 steps and 5 min hidden, 20 steps and 15 s visible, refreshed when the app opens), widget refreshes are spaced.
- Walks: location only during an active walk and re-requested as the plan changes (3 s / 3 m moving, 5 s / 5 m screen off, 6-10 s still, saver slower); GPS is the source, network is a slower backup. Walk loop 1 s visible, 5 s hidden (the walk engine's step cap is now 10 s). Group and partner sends follow the policy (3-8 s, never above 10 s), bursts coalesce, partner daily ring every 30-120 s, walk notification at most every 10-30 s.
- Network: exponential backoff with jitter (`Backoff`), waits for the system network callback when offline, one shared OkHttp client, ping every 40 s, everything closed when a walk ends.
- UI: `rememberPulse` (10-12 fps, resumed only, draw-only) replaces the infinite transitions; Battery saver acts like Reduce motion; Settings refreshes every 15 s while resumed; trends, recap, badges, fuel, weekly and hourly data load only while subscribed; widget push and badge checks run only with the app open; OSM tile cache capped by bytes (8 MB).
- Settings: new "Battery" card (current mode, what it means, tips, Battery saver mode switch). README "Battery" section.
- Not verified on a device: real battery numbers, sensor-hub batching on specific phones (some ignore `maxReportLatency`; the app then just gets events sooner), Doze behaviour, OEM background limits.

## alpha 1.6 (1.6.0-alpha)

Fix: steps were not counting, and did not survive the app being closed.

- Root cause: outside a walk, steps were only sampled by a 15 minute inexact alarm. There was no foreground service or WorkManager safety net, the boot receiver only re-armed that alarm, sampling was skipped until onboarding finished and during walks, the baseline lived in DataStore separately from the day totals, and the activity permission was asked once in onboarding with no visible state afterwards (and reported as denied on Android 8 and 9).
- New always-on counter: `StepService`, a low-priority foreground service (type health on Android 14+, `FOREGROUND_SERVICE_HEALTH`) holding one batched `TYPE_STEP_COUNTER` listener (60 s max report latency, immediate while the app is on screen), with a quiet "N steps today" notification.
- Safety nets: WorkManager (15 min) and the alarm read the cumulative counter and store the delta, even if the service was killed. Boot and app-update receivers restart everything, tolerating Android 12+ background-start refusals.
- Storage: day totals and the counter baseline are written in one Room transaction (new `step_state` table, database version 3 with a migration). The UI reads the database only. Works fully offline.
- Domain (pure Kotlin, 24 new tests): `StepLedger` (baseline, delta, reboot by lower value or later boot time, clock set back, midnight and DST day splitting with exact sums), `StepHealth` status, `AccelStepDetector` fallback. Fallbacks: step detector, then accelerometer.
- Walk and group sessions read the same feed and no longer write daily steps (no double counting). Sitting reminders use today's total instead of the old 15 minute delta.
- Home: permission rationale card, blocked state with "Open app settings", no-sensor state, and a dismissible battery tip. Settings: "Step counting health" (permission, sensor, last reading, service, battery) with links to app settings, battery settings and dontkillmyapp.com.
- Widget is refreshed by the service and the safety nets.
- Docs: README "How step counting works" and troubleshooting.
- Not verified on a device (CI compiles and runs the domain tests only): real-sensor batching, OEM battery killers, Android 12+ start restrictions, reboot recovery.

## alpha 1.5 (1.5.0-alpha)

- One built-in server, `wss://walk-buddy-server-sxpz.onrender.com`, held in a single constant (`ServerConfig.URL` in the domain module). It is the only server for partner signaling and open groups.
- Removed the user-facing "Signaling server" setting (field, Test connection, stored preference) and the server fields on the create/join group screens. Self-hosting means changing the constant and rebuilding.
- Invite links and QR codes no longer carry a server (smaller QR, 21 to 29 modules). A `?s=` server in a link is ignored; a `/g/CODE` web link on any other host is rejected.
- The free host sleeps, so connecting shows "Waking up the server, this can take up to a minute..." with a 75 s connect timeout and automatic retries (6 for partner, 8 for groups).
- Docs: README and server README updated, new `docs/SHARING.md` (build and share a debug APK). CI already uploads the debug APK as the `walk-buddy-debug-apk` artifact.
- Tests: domain 283 (invite/link tests rewritten for the built-in server), server 49.

## alpha 1.4 (1.4.0-alpha)

- Home: the two ways to walk are now distinct cards, each with a coloured header band (title, tagline, person glyphs) over the explanation and actions, under a "Walk together" heading.
- Group screens: initial avatars (up to two letters, stable colour per person) on the roster, tonal tags for host, role and "a little apart", a status pill in the header, invite code shown as six tiles, a "Leave group" button that is not styled like "End walk", a connecting indicator while waiting.
- Group goal: one segmented bar made of every member's steps, so the group visibly adds up with no ranking; group totals and together score count up.
- Progress bars ease to their value and counts animate; both snap when reduce-motion is on.
- Create-group screen: goal chips scroll instead of clipping on narrow phones; cards fade in.
- New shared components: `Avatar`, `PersonDot`, `StatusPill`, `TagChip`, `ModeCard`, `SegmentedProgress`, `AnimatedCount`.
- README rewritten as a landing page. Docs screenshots are still the alpha 1.2 images (the CI artifact needs a signed-in download), and the README says so.
- No behaviour, protocol or data changes. Domain tests 284, server tests 49.

## alpha 1.3 (1.3.0-alpha)

- Two modes on the home screen: **Walk with partner** (couple mode, unchanged) and **Open group walk** (up to about 50 people).
- Open groups: create with host controls (optional approval, kick, end for everyone, step goal, meeting point, time limit); join by QR, invite link or 6-character code, including mid-walk; roster snapshot for late joiners; nickname; reconnect keeps your place.
- Server: group rooms relay small validated location and step updates over WebSocket; rate limits, TTL, no persistence, no location storage, no public directory. Pair signaling is unchanged.
- Domain: together score and nudges for N members (robust centroid, stragglers, leader and sweeper, collective step goal with no ranking), optional location blur (about 100 m or 500 m grid), group invite link and QR payload encode/decode.
- Map shared by both modes: offline canvas with trails, follow me / whole group, meeting-point flag, scale bar; opt-in OpenStreetMap tiles with attribution; opt-in local GPX route saving with share and delete.
- QR scanning with CameraX and ZXing (no Play Services); camera permission only on the scan screen, with paste-link and enter-code fallbacks.
- CI no longer pushes a `ci-screenshots` branch; screenshots are a workflow artifact only.
- Tests: domain 205 to 284, server 21 to 49.

## alpha 1.2 (1.2.0-alpha)

- UI redesign: giant centred tabular step hero, thicker gradient ring with seamless sweep and glow, single card style and radius scale, 48dp touch targets, stats strip, tonal secondary buttons.
- Bundled fonts (Bricolage Grotesque, Figtree; OFL). Custom Canvas tab icons, bottom bar and rail.
- New adaptive launcher icon with monochrome layer, and matching splash.
- Screens made stateless (`XContent`) so they can be rendered in tests; Paparazzi screenshot suite and non-blocking CI `screenshots` job uploading the `screenshots` artifact (alpha 1.3 stopped pushing a branch).
- Recap, summary, mood, lobby, badges, settings polish; large-font and long-text checks.
- No behaviour, protocol or data changes. Domain tests 205, server tests 21.

## alpha 1.1 (1.1.0-alpha)

- New hero: giant tabular step count inside an animated gradient ring, buddy dots, verified/raw chip, stat pills, goal confetti and haptic.
- Streak flame with rest tokens, 12 badges with unlock events, hour-by-hour histogram, month heat-map.
- Mood check-in with a correlation-not-causation insight; mood rows in the CSV export.
- Couple extras: Our week, quick reactions (rate-limited), anniversary countdown, walk-date reminders.
- Gentle-day mode, swipeable weekly recap, stats-only share card via FileProvider.
- 3-step onboarding, demo mode (no buddy, no permissions), settings for km/mi, step length, reduce-motion, haptics, quiet hours.
- Dusk design system: brand palette plus optional dynamic colour, true-black dark, bold tabular display type.
- Platform: edge-to-edge, type-safe Navigation 2.8, predictive back, splash screen, adaptive layouts, Glance widget (separate module), Quick Settings tile, shortcuts, en/hi locales, Android 16 live-update notification (reflection, gated).
- Database v2 (migration from v1); protocol adds `react` and `day` messages.
- Domain tests 154 to 205; server tests 21.

## alpha (1.0)

- First version: live walking together over WebRTC data channels, together engine, verified steps, health logic, opt-in calories and refuel ideas, couple mode, Room history, CSV export, Node signaling server. Compiled green on CI with no changes (commit fa62bdd).
