# Sharing the app with friends

Walk Buddy is not on an app store yet. To let someone try it, send them a debug APK.

## Build the APK in Android Studio

1. Open the project and let Gradle sync.
2. Menu **Build > Build APK(s)** (choose the `debug` variant if asked).
3. Click **locate** in the notification, or open `app/build/outputs/apk/debug/app-debug.apk`.

From a terminal: `./gradlew :app:assembleDebug` produces the same file.

No Android Studio? Every push to `main` is built on GitHub Actions; the `walk-buddy-debug-apk` artifact of the latest green **CI** run contains the APK (downloading needs a GitHub sign-in).

## Install it on another phone

Send the file (chat, email, cloud drive). On the receiving phone, open it and allow **Install unknown apps** for the app used to open it (Settings > Apps > Special access > Install unknown apps, or accept the prompt). A debug APK is signed with a debug key, so Play Protect may warn; it is safe to continue if you built it yourself.

## Server

The app talks to the built-in server `wss://walk-buddy-server-sxpz.onrender.com`; friends need no setup. It sleeps when idle, so the first group or partner connection can take up to a minute.

## Release APK

For a friendlier build than the debug APK, make a keystore with `scripts/make-release-keystore.sh` (kept outside the repo, never commit it), export the four `WB_KEYSTORE_PATH`, `WB_KEYSTORE_PASSWORD`, `WB_KEY_ALIAS`, `WB_KEY_PASSWORD` variables or put them in `local.properties`, then run `./gradlew :app:assembleRelease`. The file is `app/build/outputs/apk/release/app-release.apk`. Without a keystore it is signed with the debug key. CI also publishes an `assembleRelease` artifact. Updates are manual: friends install the new APK over the old one (same signing key) or use **About, Check for updates** which opens the GitHub releases page.
