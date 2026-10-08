# Walk Buddy

[![CI](https://github.com/Dante3750/walk-buddy/actions/workflows/ci.yml/badge.svg)](https://github.com/Dante3750/walk-buddy/actions/workflows/ci.yml)

A native Android app (Kotlin, Jetpack Compose, Room) for walking **together**. Walk with one to three buddies and see each other's steps, pace and distance live, notice when you drift apart, and build a gentle daily walking habit. It is about being side by side and healthy habits, not racing: there are no leaderboards and no shaming.

It was built for a couple who walk together every evening, so a two-person **couple mode** is a first-class part of it, written to feel warm and stay neutral enough for any couple (or friends, or family).

No accounts. No analytics. No ads. No voice or video, and the app never asks for the microphone or camera. Everything stays on the phone except live walk data, which goes **phone to phone** over an encrypted WebRTC data channel to the people you invite.

> **Honest status (alpha 1.1):** the app compiles and CI is green (domain tests, server tests, debug APK). It has **not been run on a phone yet**, so GPS, the foreground service, haptics, WebRTC pairing, the widget and the Android 16 live update are all untested on a device. See [Status](#status).

## Get the debug APK

1. Open the [latest green run of the CI workflow](https://github.com/Dante3750/walk-buddy/actions/workflows/ci.yml?query=branch%3Amain+is%3Asuccess).
2. Scroll to **Artifacts** and download `walk-buddy-debug-apk`. GitHub asks you to **sign in** before it lets you download artifacts, even on a public repo.
3. Unzip, copy `app-debug.apk` to the phone and install it (allow "install unknown apps" for your file manager). Debug builds are unsigned for release and are for trying the app, not for the Play Store.
4. No buddy yet? Turn on **Demo mode** (onboarding, or Settings). It needs no permissions and no second phone.

## Alpha 1.2 feature tour

<p>
<img src="docs/screens/home.png" width="190" alt="Home with the giant step hero">
<img src="docs/screens/home-dark.png" width="190" alt="Home in true-black dark theme">
<img src="docs/screens/live-walk.png" width="190" alt="Live walk screen">
<img src="docs/screens/weekly-recap.png" width="190" alt="Weekly recap">
</p>

- **Redesign:** one centred step hero, one card style, bundled fonts (Bricolage Grotesque and Figtree, both SIL OFL, see `docs/OFL-*.txt`), custom tab icons, a new adaptive launcher icon (with themed monochrome layer) and a matching splash.
- **Screenshot loop:** CI renders every screen with Paparazzi (no emulator) in light, dark and 1.3x font scale, and force-pushes the PNGs to the `ci-screenshots` branch (separate, non-blocking job). Fetch it with `git fetch origin ci-screenshots`. The branch is disposable and can be deleted, along with the `screenshots` job, at any time.

## Alpha 1.1 feature tour

- **The hero:** a huge centred, tabular step count that springs up inside a thick gradient ring with a goal marker and a soft glow while you walk. Under it: a verified-steps line, a raw-vs-verified chip, and pills for distance, active minutes and (only if enabled) calories. Buddies sit on the ring as avatar dots with "ahead / behind" text. Reaching the goal brings confetti, a haptic and a shareable card. The same big number stays central on the live walk screen.
- **Streak flame** with rest tokens, **badges** (12, with unlock snackbars), an **hour-by-hour histogram** ("your best walking hour") and a **month heat-map** calendar.
- **Mood check-in** after a walk (emoji and a one-line note, stored locally) with an insight that says plainly it shows a pattern, not a cause.
- **Couple extras:** "Our week" card, warm preset quick-reactions over the data channel (rate-limited), anniversary countdown, walk-date reminders.
- **Gentle-day mode** (a softer goal on a low day that never raises the real goal) and a swipeable, story-style **weekly recap**.
- **Share card:** stats only, drawn to a Bitmap and shared through FileProvider (no map, no location).
- **Onboarding** in 3 steps, **demo mode**, and settings for km/mi, step-length calibration, reduce-motion, haptics and quiet hours.
- **Around the system:** Glance home-screen widget, Quick Settings tile, app shortcuts, edge-to-edge, predictive back, splash screen, tablet/foldable layouts (nav rail), English and Hindi system strings, and on Android 16 a progress-style live-update notification during a walk.

## Design notes

"Dusk": a warm evening palette (amber to coral to berry) for people who walk after work. Material 3 with **dynamic colour on Android 12+ (opt-in in Settings)** and a hand-tuned brand palette as the default and fallback. Dark theme is true black for OLED. The step number uses a bold, tabular-figure display style so digits do not jiggle while counting. Shapes are generously rounded; the ring is the one loud element and everything else stays quiet. All motion respects the reduce-motion setting (count-up, glow, confetti, flame, pager).

## Tech choices and the reason for each new dependency

| Piece | Why |
|---|---|
| Navigation Compose 2.8 type-safe routes | `@Serializable` route objects instead of strings (kotlinx.serialization plugin was already applied). |
| `androidx.core:core-splashscreen` 1.0.1 (new) | One splash API for API 26 to 35. |
| `androidx.glance:glance-appwidget` 1.1.1 (new) | Compose-style home-screen widget. Isolated in its own `:widget` module and switchable with `-Pwidget=false`, so a Glance problem cannot break the app build. The app and widget only share a tiny SharedPreferences file and a broadcast. |
| Android 16 `Notification.ProgressStyle` | Reached by reflection and gated by `SDK_INT >= 36` (compileSdk is 35), with the normal ongoing notification as fallback. No new dependency. |
| `collectAsStateWithLifecycle`, immutable UI state, stable lazy keys | Lifecycle-safe collection and cheaper recomposition. |
| Plain canvas share card, no image library | One Bitmap and a FileProvider are enough. |
| WindowSizeClass-aware layout (`Adaptive.kt`) | Nav bar on phones, rail on wide screens. |

Not done: shared-element transitions (cut to keep the build safe), a baseline profile (a good next step: add the Macrobenchmark module and generate one), and the Hindi translation covers system surfaces (app name, widget, shortcuts, tile, notification) while in-app copy is English only.

## Features

| Area | What you get |
|---|---|
| **Pairing** | 6-character session code (no look-alike letters), join link `walkbuddy://join/ABC234` (optionally carrying your server), QR code drawn by a pure-Kotlin encoder, or just type the code. Up to 4 people per walk. |
| **Live walk** | Per buddy: distance, ahead/behind along your direction of travel, pace zone, steps. A "together" ring, calm status wording ("Sam has paused. No rush."), a nudge banner with a soft haptic. |
| **Together engine** | Haversine distance with GPS-jitter handling (low-accuracy fixes dropped, impossible speeds dropped, stationary wobble ignored). Together score (share of walk time everyone is within your radius, default 50 m) and longest streak. Catch-up nudges with a sustained-gap timer, hysteresis, cooldown and a per-walk cap, so they never spam. Pace-match suggestion (the slowest moving buddy's rolling pace). Quiet mode mutes everything. |
| **Honest steps** | Raw and verified steps side by side. Verified drops vehicle and bicycle time, and anything above walking speed (GPS above ~3.5 m/s for 8 s), and caps impossible step bursts. |
| **Health logic** | Adaptive daily goal (14-day median plus ~10%, clamped, rest days ignored), pace zones from cadence (easy / brisk / vigorous), active minutes against the commonly cited 150 min/week guidance, sitting-break reminders (about an hour still, then a 2 minute stand or stroll), streaks with rest days and rest tokens, cooperative team goals, weekly report (steps trend, active minutes, best time of day, together score). |
| **Calories (opt-in)** | Hidden by default and clearly labeled "estimate". Step length from height (0.415 / 0.413 / 0.414 x height) or calibrated from a GPS walk, speed from GPS or cadence, MET from a walking-speed lookup table with linear interpolation, **net** kcal = (MET - 1) x kg x hours, always shown as a range (about +/-25%). No weight-loss targets, no deficit maths, no BMI. Implausible profile values hide the numbers and show neutral copy. |
| **Refuel ideas (opt-in)** | From the month's pattern (steps, active minutes, share of brisk walking) the app classifies light / moderate / high activity and suggests general, balanced ideas: water first, protein with complex carbs after longer or brisker walks, a salty drink on hot days, fruit and nuts. India-first, data-driven catalogue ([`refuel_catalogue.json`](domain/src/main/resources/refuel_catalogue.json)) with a vegan / vegetarian / vegetarian + egg switch, tested so a vegan filter never returns dairy or egg. No calorie targets, no diet plans, no weight advice, always with the disclaimer. |
| **Couple mode** | Walk dates (opens your calendar with an optional weekly repeat, no calendar permission), "walked together 4 of 7 days", a shared odometer with fun milestones (marathon, Bengaluru to Mysuru about 140 km by road and so on, marked approximate), an opt-in rate-limited "thinking of you" haptic ping, favorite walking spots (stored locally, shareable to your partner during a session), a stats-only highlights card (no map), pace-sync mode (the faster partner gets the gentle nudge), quiet mode. |
| **Privacy and safety** | "General wellness, not a medical device." "Share live location only with people you trust." Sharing ends when the walk ends. CSV export of your own data. A delete-all button. |
| **Android** | Staged permissions (location while in use, foreground service of type location, activity recognition, notifications; **no microphone**), plain `LocationManager` and `SensorManager` step counter (no Play Services), Room history, DataStore settings, light and dark theme, empty and error states, content descriptions. Optional Health Connect behind a build flag. |

## How it works

```
  Phone A                                           Phone B
 +--------------------------+                      +--------------------------+
 | Compose UI  (ui/)        |                      | Compose UI               |
 |   Home Week Fuel Us Set. |                      |                          |
 |   Lobby / Live / Summary |                      |                          |
 +------------+-------------+                      +------------+-------------+
              | StateFlow                                        |
 +------------v-------------+   WebRTC data channel  +-----------v-------------+
 | WalkSession + WalkService|<======================>| WalkSession + Service   |
 |  (foreground, type loc.) |  hello / pos / ping /  |                         |
 +---+----------+-----------+  spot / bye (JSON v1)  +-------------------------+
     |          |   ^
     |          |   | offer / answer / ice only
     |          v   |
     |   +-------------------+      WebSocket      +--------------------------+
     |   | PeerLink + OkHttp |<------------------->| server/  (Node, `ws`)    |
     |   +-------------------+   (introductions)   | in-memory rooms by code, |
     |                                             | never sees locations     |
 +---v--------------------------------------+      +--------------------------+
 | :domain  (pure Kotlin, JUnit tested)      |   STUN (stun.l.google.com) helps phones
 |  WalkEngine = FixFilter + Distance +      |   find a direct path; it is not a relay.
 |  Heading + Together + Nudge + Pace +      |
 |  VerifiedSteps + Calories + Food + Couple |
 |  + Protocol + QR + CSV                    |
 +---+--------------------------------+------+
     |                                |
 +---v----------+              +------v--------------+
 | SensorManager|              | Room (days, walks,  |
 | LocationMgr  |              | spots, dates) +     |
 +--------------+              | DataStore settings  |
                               +---------------------+
```

1. One person taps **Start a walk together**. The app makes a code and a link. The other person scans the QR, taps the link, or types the code.
2. Both phones connect to the tiny signaling server for a moment and swap WebRTC `offer`, `answer` and `ice` messages. After the data channel opens, the phones talk directly.
3. During the walk each phone broadcasts a small position message every 2 seconds. Each phone runs the **same pure-Kotlin engine** on what it receives: distance between you, ahead or behind, the together score, nudges. Nothing is computed on a server.
4. When you end the walk the channel is closed, the signaling socket is closed, and a summary is saved on your phone only.

```
domain/    pure Kotlin JVM, no Android: protocol + QR, geo, together/nudge/pace engine, verified steps,
           health logic, calories, refuel catalogue, couple features, CSV, WalkEngine (unit tested)
app/       Android: data/ (Room, DataStore, repository), sensors/, rtc/ (OkHttp signaling + WebRTC),
           session/ (WalkSession, foreground service), notify/, health/ (optional), ui/ (Compose)
server/    Node 18+ signaling relay (one dependency: ws), Dockerfile, node --test tests
```

### The message protocol

Peer messages are versioned JSON, `{"v":1,"t":"pos", ...}`: `hello`, `pos`, `ping`, `spot`, `react`, `day`, `bye`. The decoder is tolerant (unknown fields ignored, numbers sent as strings accepted, newer versions and unknown types accepted as `Unknown`) and strict about values (ranges, sizes, finite numbers, control characters stripped from names), so a buggy peer cannot crash or confuse your screen. The signaling protocol is documented in [`server/README.md`](server/README.md).

## Run the signaling server

```bash
cd server
npm install
npm start          # :8080
npm test           # 21 tests
```

Or with Docker: `docker build -t walk-buddy-signaling server && docker run -p 8080:8080 walk-buddy-signaling`. Put it behind TLS and enter the `wss://` address in the app under Settings > Signaling server (there is a Test connection button). Details, limits and the privacy note are in [`server/README.md`](server/README.md).

## Build the app

1. Android Studio Ladybug (2024.2) or newer, JDK 17+.
2. Open this folder. The Gradle wrapper is included (`./gradlew`).
3. Run the `app` configuration on a device or emulator (API 26+), or `./gradlew :app:assembleDebug`.

CI (`.github/workflows/ci.yml`) runs the server tests on Node 18 and 22, then on the GitHub runner's preinstalled Android SDK: `:domain:test`, `:app:assembleDebug`, `:app:lintDebug` (reported, not blocking) and a non-blocking Health Connect compile. The debug APK is uploaded as the `walk-buddy-debug-apk` artifact.

Release history: [CHANGELOG.md](CHANGELOG.md) and [RELEASES.md](RELEASES.md).

Change `applicationId` / `namespace` in `app/build.gradle.kts` before publishing.

### Tests

```bash
./gradlew :domain:test                 # 205 tests
scripts/domain-test-offline.sh         # same tests with only the jars inside a Gradle distribution (no Maven needed)
cd server && npm test                  # 21 tests
```

### Health Connect (optional, off by default)

`./gradlew :app:assembleDebug -PhealthConnect=true` adds `androidx.health.connect:connect-client` and compiles `app/src/healthconnect/` (read today's steps, write each finished walk as an exercise session). It is off by default so an API mismatch in an alpha library cannot break the main build. The app talks to it only through the `HealthBridge` interface and loads the implementation by name. CI tries this build separately and does not fail the pipeline if it breaks.

## Privacy

- No accounts, no analytics, no ads, no third-party SDKs that phone home. The app has no microphone or camera permission (the manifest actively removes them if a library adds them).
- Steps, walks, spots, dates and settings live in a local database on the phone. `allowBackup` is off. Export them as CSV, or delete everything from Settings.
- Live location goes only to the buddies who joined your session, directly phone to phone. Sharing starts when you open a session and ends when the walk ends.
- The signaling server sees IP addresses and opaque WebRTC connection descriptions for a few seconds. It never receives locations or steps, keeps rooms in memory only, and logs no codes or payloads (a test enforces it). Run your own if you prefer.
- STUN uses Google's public servers by default, which sees that your phone asked for its public address.
- General wellness only. This is not a medical device and gives no medical or nutrition advice.

## Status

What is verified, and what is not:

- **Verified here:** `:domain` compiles with Kotlin 2.0.21 (via Gradle's bundled compiler) and all 205 tests pass. The server's 21 tests pass on Node 22 against real WebSocket clients. The pure-Kotlin QR encoder's output was decoded successfully by OpenCV's QR detector for several payloads (a one-off manual check; the unit tests cover Reed-Solomon, format bits and structure with published vectors).
- **Compiled by CI:** the whole `:app` and `:widget` build on the GitHub runner (Kotlin 2.0.21, AGP 8.7.3, compileSdk 35). `stream-webrtc-android:1.3.7` and OkHttp 4.12.0 resolve and compile. That proves they compile, not that pairing works.
- **Unverified:** the optional Health Connect build (`-PhealthConnect=true`) currently fails in KSP and is non-blocking in CI; Android 16 `ProgressStyle` by reflection; Glance widget rendering on real launchers; the Quick Settings tile and shortcuts on devices.
- **Not tested on a device.** No phone has run this. GPS behaviour, the foreground service, haptics and WebRTC connectivity are all untested.
- **No TURN relay.** Pairing uses STUN only, as designed (everything stays phone to phone). Some mobile networks (carrier-grade NAT, common on Indian mobile data) and strict corporate Wi-Fi block direct connections, and then two phones will simply fail to connect. Same-Wi-Fi or a friendly network works best. Adding TURN is a one-line change in `PeerLink.iceServers` plus a server you trust.
- **Calorie table:** the walking MET values were transcribed from memory of the 2011 Compendium of Physical Activities. The source is cited in `Calories.kt`; verify the numbers against the published tables.
- **Steps outside walks:** with no GPS running, steps between periodic samples (about every 15 minutes) are only plausibility-checked (a cap on steps per minute). Vehicle and bicycle filtering needs GPS, so it applies during walks.
- **Activity recognition** is a simple speed + cadence classifier in `:domain`, not Google's Activity Recognition API, so there is no Play Services dependency. The `ACTIVITY_RECOGNITION` permission is still requested for the hardware step counter.
- Pings and shared spots only work while two phones are connected (lobby or walk), because there is deliberately no server that stores anything.
- The app layer has no unit tests; all logic worth testing lives in `:domain`. Compose previews (light, dark, large font) exist for the hero only.

## Roadmap

- v1.1: BLE heart-rate strap (live heart rate on the walk screen, opt-in).
- v1.1: walking-steadiness trend.
- Optional TURN configuration for networks that block direct connections.
- Compile and device-test pass once CI is green; first real GPS walk to tune the jitter and nudge thresholds.
- Baseline profile, more previews, full Hindi translation.

## Credits and licence

MIT, see [LICENSE](LICENSE). Gradle and CI setup follow the Builder's Ledger project.
