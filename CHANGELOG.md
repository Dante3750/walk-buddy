# Changelog

## alpha 1.2 (1.2.0-alpha)

- UI redesign: giant centred tabular step hero, thicker gradient ring with seamless sweep and glow, single card style and radius scale, 48dp touch targets, stats strip, tonal secondary buttons.
- Bundled fonts (Bricolage Grotesque, Figtree; OFL). Custom Canvas tab icons, bottom bar and rail.
- New adaptive launcher icon with monochrome layer, and matching splash.
- Screens made stateless (`XContent`) so they can be rendered in tests; Paparazzi screenshot suite and non-blocking CI `screenshots` job publishing to `ci-screenshots`.
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
