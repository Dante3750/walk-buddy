<div align="center">

# Walk Buddy

**Walk together. See each other. Stay side by side.**

A native Android app for evening walks with a partner, or an open group of up to about 50, with live steps, pace and a shared map. No accounts, no ads, no analytics.

[![CI](https://github.com/Dante3750/walk-buddy/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Dante3750/walk-buddy/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF)
![Status](https://img.shields.io/badge/status-alpha%201.4-orange)

</div>

> **Honest status (alpha 1.4):** the app compiles and CI is green (domain tests, server tests, debug APK). It has **not been run on a phone yet**, so GPS, the foreground service, WebRTC pairing, QR scanning, the widget and the Android 16 live update are untested on a device. See [Privacy and honest limitations](#privacy-and-honest-limitations).

## Why

Walking is better with someone, but most step apps are built for streaks and leaderboards. Walk Buddy was written for a couple who walk every evening, then widened to friends, families and walking clubs. It shows who is where, nudges gently when someone drifts a little apart, and builds a calm daily habit. There is no ranking and no shaming: steps from a group add up to one number.

## Features

**Steps hero**
- One huge, centred step count that counts up inside an animated gradient ring, with a goal marker and a glow while you walk.
- *Verified* steps next to raw sensor steps: vehicle and bicycle time and impossible bursts are left out.
- Streak flame with rest tokens, 12 badges, hour-by-hour "best walking hour", month heat-map, weekly recap, gentle-day and rest-day modes, confetti when you reach the goal.

**Partner mode** (2 people, phone to phone)
- Pair by code, link or QR. Live distance, ahead or behind, pace zone, a "together" score and soft catch-up nudges (with cooldowns, so they never spam).
- Couple extras: walk dates, "Our week", shared odometer milestones, quick reactions, anniversary countdown, favourite spots.
- Live data goes over an encrypted WebRTC data channel; the server only introduces the two phones.

**Open group walks** (up to about 50, with QR join)
- Create a group: optional host approval, time limit (1 to 8 h), optional shared step goal, meeting point.
- Join by **QR scan**, `walkbuddy://` or https link, or the 6-character code, even after the walk has started.
- People tab with initial avatars, role tags (front, back), "a little apart" shown softly, and one shared steps bar. Quiet mode, sweeper role, and a switch to stop sharing location.
- Host can approve, remove, set the goal and meeting point, or end the walk for everyone.

**Map** (both modes)
- Offline canvas map: a dot with initial and a trail per person, follow me or whole group, meeting-point flag, scale bar. No network request by default.
- Opt-in OpenStreetMap tiles (with attribution) and opt-in local GPX route saving.

**Health, food and calorie tools** (all opt-in, wellness only)
- Adaptive daily goal, pace zones, active minutes against the 150 min/week guidance, sitting-break reminders.
- Calories: hidden by default, always shown as an estimated range, net of resting energy, no deficit or weight-loss targets.
- Refuel ideas: general, balanced suggestions with a vegan / vegetarian / egg switch. No diets, no targets, no BMI.

**Privacy**
- No accounts, no analytics, no ads, no microphone, no voice or video. Camera only on the QR scan screen, nothing saved.
- Your data lives on your phone: CSV export and delete-all in Settings.

**Around the system:** Glance home-screen widget, Quick Settings tile, shortcuts, edge-to-edge, predictive back, tablet layouts, light, dark (true black) and optional dynamic colour, English and Hindi system strings.

## Screenshots

| Home | Home (dark) | Live walk | Weekly recap |
|:---:|:---:|:---:|:---:|
| <img src="docs/screens/home.png" width="180" alt="Home with the step hero"> | <img src="docs/screens/home-dark.png" width="180" alt="Home in true-black dark theme"> | <img src="docs/screens/live-walk.png" width="180" alt="Live walk screen"> | <img src="docs/screens/weekly-recap.png" width="180" alt="Weekly recap"> |

> **These images are from alpha 1.2 and are not refreshed.** Alpha 1.4 changed the home screen (the two walk-mode cards) and the group screens, but CI screenshots are only available as a workflow artifact that GitHub lets you download after signing in, and they could not be fetched automatically. To see the current UI, download the `screenshots` artifact from the [latest CI run](https://github.com/Dante3750/walk-buddy/actions/workflows/ci.yml?query=branch%3Amain+is%3Asuccess), or build the APK.

## Quick start

### Install the debug APK

1. Open the [latest green CI run](https://github.com/Dante3750/walk-buddy/actions/workflows/ci.yml?query=branch%3Amain+is%3Asuccess) and download the `walk-buddy-debug-apk` artifact (GitHub asks you to sign in for artifacts, even on a public repo).
2. Unzip, copy `app-debug.apk` to the phone and install it (allow "install unknown apps" for your file manager). It is a debug build for trying the app, not for the Play Store.
3. No buddy yet? Turn on **Demo mode** (onboarding or Settings): no permissions and no second phone needed.

### Build it yourself

Android Studio Ladybug (2024.2) or newer, JDK 17+. Open the folder and run `app`, or:

```bash
./gradlew :app:assembleDebug
```

### Run the server

Partner pairing and group walks both use one small Node signaling and relay server (one dependency: `ws`).

```bash
# Node 18+
cd server && npm install && npm start          # listens on :8080

# or Docker
docker build -t walk-buddy-server server
docker run -p 8080:8080 walk-buddy-server
```

Put it behind TLS and enter its `wss://` address in the app (Settings, Signaling server, with a Test connection button). Limits and the privacy note are in [`server/README.md`](server/README.md).

### Join a group walk

1. **Scan:** Home, Open group walk, Join a group, **Scan a QR code**. Camera permission is asked only there.
2. **Link:** tap a `walkbuddy://group/...` or `https://your-server/g/CODE` link, or paste it into the join screen.
3. **Code:** type the 6-character code. A bare code does not say which server to use, so the join screen also asks for the server address unless it is saved in Settings.

To host: Home, Open group walk, **Create a group**, then share the QR, code or link from the **Invite** tab.

## Architecture

```
domain/   pure Kotlin JVM, no Android: protocol and QR encoder, geo, together/nudge/pace engines, group engine
          (centroid, stragglers, roles, collective goal), verified steps, health, calories, refuel catalogue (unit tested)
app/      Android: Compose UI, Room + DataStore, sensors, WebRTC peer link, group session, foreground service, notifications
widget/   Glance home-screen widget, its own module (switch off with -Pwidget=false)
server/   Node 18+ signaling and group relay, in-memory rooms, Dockerfile, node --test tests
```

Partner mode: phones swap WebRTC offers through the server, then talk directly; each phone runs the same pure-Kotlin engine on what it receives. Open groups: phones send small validated updates to the server, which relays them to that one group and keeps nothing. The wire formats are versioned JSON, documented in [`server/README.md`](server/README.md).

Tests and CI:

```bash
./gradlew :domain:test                 # 284 tests
scripts/domain-test-offline.sh         # same tests with only the jars inside a Gradle distribution
cd server && npm test                  # 49 tests
```

CI (`.github/workflows/ci.yml`) runs the server tests on Node 18 and 22, then `:domain:test`, `:app:assembleDebug` and lint (reported, not blocking), uploads the debug APK, and renders every screen with Paparazzi in a separate non-blocking job (the `screenshots` artifact). It never pushes a branch or a tag.

## Privacy and honest limitations

- **Where data goes.** Steps, walks, spots and settings stay in a local database (`allowBackup` is off). Partner mode sends live data phone to phone. In an open group your name, steps and a possibly blurred position (exact, about 100 m or about 500 m grid, applied on your phone) go to the server you chose, which relays them to that group and stores nothing. Other members' data is never saved on your phone.
- **Trust.** Anyone with the code or QR can join an open group unless the host approves each person, so share it only with people you trust. The server operator can see IP addresses and traffic while it passes through; use your own server or one you trust. STUN uses Google's public servers by default.
- **Not tested on a device.** No phone has run this build. GPS behaviour, the foreground service, haptics, QR scanning, map gestures, OSM tiles and WebRTC connectivity are untested.
- **No TURN relay.** Pairing uses STUN only, by design. Carrier-grade NAT (common on mobile data) and strict corporate Wi-Fi can block direct connections; same Wi-Fi works best.
- **Health Connect** (`-PhealthConnect=true`) is optional and currently fails to build in KSP (non-blocking in CI).
- **Calorie table** values were transcribed from memory of the 2011 Compendium of Physical Activities; verify them against the published tables.
- **Steps outside walks** are only plausibility-checked; vehicle filtering needs GPS and applies during walks.
- A QR code holds an invite of up to about 106 bytes, so a very long server address shows the link and code only.
- General wellness only: not a medical device, no medical or nutrition advice.

## Roadmap

- First real-device pass: GPS jitter and nudge thresholds, pairing, group walks.
- Optional TURN configuration for networks that block direct connections.
- BLE heart-rate strap and a walking-steadiness trend (opt-in).
- Baseline profile, group-screen screenshot tests, full Hindi translation.

## Contributing

Issues and pull requests are welcome. Keep logic that deserves tests in `:domain` (or `server/`), run `./gradlew :domain:test` and `cd server && npm test` before pushing, and keep to the privacy rules above: no accounts, no analytics, no new permissions without a clear reason. Android code only compiles on CI in this project, so check the CI run on your change. Release history: [CHANGELOG.md](CHANGELOG.md) and [RELEASES.md](RELEASES.md).

## Licence

MIT, see [LICENSE](LICENSE). Bundled fonts (Bricolage Grotesque, Figtree) are SIL OFL, see `docs/OFL-*.txt`.
