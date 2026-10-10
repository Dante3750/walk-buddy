# Make your own release keystore

Walk Buddy never ships a signing key, and this repository never contains one. A *keystore* is the private file that proves an APK
came from you. **You** create it, **you** keep it, and nobody else (including this repository's CI) ever needs to see it.

## 1. Create it (once)

```bash
scripts/make-release-keystore.sh                 # writes ~/walk-buddy-release.jks
scripts/make-release-keystore.sh /safe/place/walkbuddy.jks mykeyalias
```

The script only runs `keytool -genkeypair` on your machine and asks you for the passwords. It will not overwrite an existing file.

**Back it up.** If you lose the keystore or its passwords you can never publish an update that installs over your old version.
Do not commit it: `.gitignore` already excludes `*.jks`, `*.keystore` and `keystore.properties`.

## 2. Tell the build where it is

Either add these lines to `local.properties` (git-ignored), or export the same values as environment variables:

| `local.properties`      | environment variable    |
|-------------------------|-------------------------|
| `wb.keystore.path`      | `WB_KEYSTORE_PATH`      |
| `wb.keystore.password`  | `WB_KEYSTORE_PASSWORD`  |
| `wb.key.alias`          | `WB_KEY_ALIAS`          |
| `wb.key.password`       | `WB_KEY_PASSWORD`       |

## 3. Build

```bash
./gradlew :app:assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Check its signature with
`apksigner verify --print-certs app-release.apk`.

If no keystore is configured the build falls back to the **debug** key. That APK installs and runs, but it is for testing only: do not
publish it, because a debug key is public knowledge and cannot be used for trustworthy updates.

## 4. Optional: signed builds in CI

Add the four values as repository secrets (`WB_KEYSTORE_PASSWORD`, `WB_KEY_ALIAS`, `WB_KEY_PASSWORD`) and provide the keystore file in
the workflow yourself (for example restore a base64 secret to a file and set `WB_KEYSTORE_PATH`). The default workflow does not do
this, so CI release APKs are debug-signed.
