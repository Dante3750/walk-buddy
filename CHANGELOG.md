# Changelog

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
