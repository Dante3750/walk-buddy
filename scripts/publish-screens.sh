#!/usr/bin/env bash
# Copies the curated Paparazzi PNGs into docs/screens/<name>.png (downscaled and palette-reduced to stay small).
# Usage: scripts/publish-screens.sh   (run after `gradle :app:recordPaparazziDebug`)
set -u
dest=docs/screens
mkdir -p "$dest"
if ! command -v convert >/dev/null && ! command -v magick >/dev/null; then
  sudo apt-get update -qq && sudo apt-get install -y -qq --no-install-recommends imagemagick >/dev/null
fi
IM=convert; command -v convert >/dev/null || IM="magick"
# name=test method (Paparazzi names files <package>_<Class>_<method>[_<label>].png)
MAP="
home=home_light
home-dark=home_dark
home-goal=home_goal_light
live-walk=live_light
live-walk-dark=live_dark
lobby=lobby_light
summary-card=summary_light
badges=badges_light
trends=trends_light
couple=couple_light
couple-streak=couple_streak_light
settings=settings_light
settings-dark=settings_dark
weekly-recap=recap_light
onboarding=onboarding0_light
selfcheck=selfcheck_light
selfcheck-dark=selfcheck_dark
challenges=challenges_light
challenges-dark=challenges_dark
track-metro=track_metro_light
track-metro-dark=track_metro_dark
track-train=track_train
track-trail=track_trail
track-nightsky=track_nightsky_dark
track-spring=track_spring
track-summer=track_summer
track-autumn=track_autumn
track-winter=track_winter
group-live=group_live_light
history=history_list_light
about=about_light
"
find_png() {
  local m="$1" f
  for root in app/src/test/snapshots/images app/build/reports/paparazzi; do
    [ -d "$root" ] || continue
    f=$(find "$root" -name "*Test_${m}.png" | head -n1); [ -n "$f" ] && { echo "$f"; return; }
    f=$(find "$root" -name "*Test_${m}_*.png" | head -n1); [ -n "$f" ] && { echo "$f"; return; }
  done
}
n=0
echo "::notice title=publish-screens::snapshots: $(find app/src/test/snapshots app/build/reports/paparazzi -name '*.png' 2>/dev/null | wc -l) png, imagemagick: $(command -v convert || command -v magick || echo none)"
for line in $MAP; do
  name="${line%%=*}"; method="${line#*=}"
  src=$(find_png "$method")
  if [ -z "$src" ]; then echo "::warning title=publish-screens::missing $method"; continue; fi
  $IM "$src" -resize 540x -strip -colors 160 PNG8:"$dest/$name.png" && n=$((n+1)) || echo "::warning title=publish-screens::convert failed for $name"
  ls -l "$dest/$name.png" | awk '{print $5, $9}'
done
if [ -f "$dest/settings.png" ]; then  # top of Settings = Battery card
  $IM "$dest/settings.png" -crop 540x1060+0+0 +repage -colors 128 PNG8:"$dest/settings-battery.png"
fi
echo "::notice title=publish-screens::published $n screens"
