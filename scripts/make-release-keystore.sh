#!/usr/bin/env bash
# Creates YOUR OWN release keystore on YOUR machine. Nothing is uploaded and nothing is stored in this repository.
# Keep the .jks file and its passwords somewhere safe (a password manager and a backup): if you lose it you can never update an app
# you published with it. See scripts/make-release-keystore.md.
set -euo pipefail

if ! command -v keytool >/dev/null 2>&1; then
  echo "keytool not found. Install a JDK (17 or newer) first." >&2
  exit 1
fi

out="${1:-$HOME/walk-buddy-release.jks}"
alias_name="${2:-walkbuddy}"
if [ -e "$out" ]; then
  echo "Refusing to overwrite $out. Pick another path: $0 /path/to/new.jks" >&2
  exit 1
fi

echo "This will create: $out (alias: $alias_name)"
echo "keytool will now ask you for a keystore password, a key password and your name details. Choose strong passwords."
keytool -genkeypair -v -keystore "$out" -alias "$alias_name" -keyalg RSA -keysize 4096 -validity 10000

cat <<MSG

Done. Next, tell the build where it is, WITHOUT committing anything:

  Option A, local.properties (this file is git-ignored):
    wb.keystore.path=$out
    wb.keystore.password=<the keystore password you chose>
    wb.key.alias=$alias_name
    wb.key.password=<the key password you chose>

  Option B, environment variables:
    export WB_KEYSTORE_PATH="$out"
    export WB_KEYSTORE_PASSWORD=...
    export WB_KEY_ALIAS=$alias_name
    export WB_KEY_PASSWORD=...

Then:  ./gradlew :app:assembleRelease
The signed APK appears in app/build/outputs/apk/release/.
MSG
