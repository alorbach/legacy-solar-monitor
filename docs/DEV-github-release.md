# DEV: GitHub Release APK and AAB

Pushing a version tag (`v*`) builds **signed** release APKs (sideload) and an AAB (Play Console), attaches them to a GitHub Release with bilingual notes and QR codes for phone download.

Workflow: [`.github/workflows/release.yml`](../.github/workflows/release.yml). Drive OAuth for the AAB still needs the publisher Cloud Console steps in [DEV-google-drive.md](DEV-google-drive.md). Play listing copy: [PLAY-LISTING.md](PLAY-LISTING.md).

Do **not** commit keystores, `local.properties`, or OAuth client IDs.

## 1. Upload keystore

Create a dedicated **upload** key (Java keystore). Play App Signing can re-sign what users install.

```powershell
keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

Keep `upload.jks` and the passwords offline. Encode the file for GitHub:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks")) | Set-Clipboard
```

SHA-1 of this key (Android OAuth client for CI/GitHub APKs):

```powershell
keytool -list -v -keystore upload.jks -alias upload
```

After the first Play upload, add a **second** Android OAuth client with the **Play App Signing** SHA-1 from Play Console → App integrity. Package: `com.alorbach.solarmonitor`.

## 2. GitHub Actions secrets

Repo → **Settings → Secrets and variables → Actions**. Add:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of `upload.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | e.g. `upload` |
| `ANDROID_KEY_PASSWORD` | Key password |
| `GOOGLE_WEB_CLIENT_ID` | Web client ID (`….apps.googleusercontent.com`) from [DEV-google-drive.md](DEV-google-drive.md) |

The workflow **fails** if any of these is empty. It never falls back to the debug keystore.

## 3. Cut a release

`versionCode` / `versionName` live in `app/build.gradle.kts`. Bump `versionCode` in git **before** tagging. The workflow reads those values from Gradle and **fails** if the tag does not match `versionName` (`v1.0.0` for `1.0.0`).

```text
git tag v1.0.0
git push origin v1.0.0
```

The tagged commit must already be on `origin/main`. The workflow refuses tags that point at other branches.

The job:

1. Runs `:app:testDebugUnitTest`
2. Builds `:app:assembleRelease -PenableAbiSplits=true` (ABI splits: `armeabi-v7a`, `arm64-v8a`, `x86_64`, plus universal APK), then `:app:bundleRelease` separately — AGP forbids splits and the AAB in one Gradle invocation
3. Collects commit context, GitHub auto-changelog, and optional AI bilingual notes via GitHub Models (`models: read`; falls back to technical changelog if inference fails)
4. Generates QR PNGs (`qrencode`) pointing at deterministic APK download URLs
5. Assembles the release description and creates or updates the GitHub Release with:

- `legacy-solar-monitor-1.0.0-vc1019-universal.apk` — recommended sideload
- `legacy-solar-monitor-1.0.0-vc1019-arm64-v8a.apk` — smaller, modern phones
- `legacy-solar-monitor-1.0.0-vc1019-armeabi-v7a.apk`
- `legacy-solar-monitor-1.0.0-vc1019-x86_64.apk`
- `legacy-solar-monitor-1.0.0-vc1019.aab` — Play Console only
- `qr-universal.png`, `qr-arm64-v8a.png`, `qr-armeabi-v7a.png`, `qr-x86_64.png` — embedded on the release page with vertical spacing

Scripts: [`scripts/collect-release-context.js`](../scripts/collect-release-context.js), [`scripts/generate-ai-release-notes.js`](../scripts/generate-ai-release-notes.js), [`scripts/assemble-release-notes.js`](../scripts/assemble-release-notes.js). Prompt reference: [`.github/prompts/release-notes.prompt.yml`](../.github/prompts/release-notes.prompt.yml).

Upload the **AAB** in Play Console. Use the **universal APK** (or an ABI-specific APK) for GitHub / sideload; scan the QR on the release page from a phone.

**Sideload tip:** If the phone shows “App not installed” / „App nicht installiert“ with no detail, uninstall any existing Studio/debug build of `com.alorbach.solarmonitor` first (signature mismatch), then install the universal release APK again.

## 4. Local signed release (optional)

Same env vars Gradle reads in CI:

```powershell
$env:RELEASE_STORE_FILE = "C:\path\to\upload.jks"
$env:RELEASE_STORE_PASSWORD = "..."
$env:RELEASE_KEY_ALIAS = "upload"
$env:RELEASE_KEY_PASSWORD = "..."
$env:GOOGLE_WEB_CLIENT_ID = "....apps.googleusercontent.com"
.\gradlew.bat :app:assembleRelease -PenableAbiSplits=true
.\gradlew.bat :app:bundleRelease
```

The same values can live in `local.properties` as `release.store.file`, `release.store.password`, `release.key.alias`, `release.key.password` (and `google.web.client.id`). When set, **debug** Studio installs also use the upload key so Drive OAuth matches the GitHub APK SHA-1.

Without those env vars, `release` is not signed with the upload key (no debug-key fallback). If any one of them is set, Gradle fails instead of producing an unsigned package.

With `-PenableAbiSplits=true`, APK outputs land under `app/build/outputs/apk/release/` as `app-universal-release.apk` and `app-<abi>-release.apk`. Do not pass that property when building the AAB.

## 5. Play Console after the AAB

Copy [PLAY-LISTING.md](PLAY-LISTING.md) and [PLAY-DATA-SAFETY.md](PLAY-DATA-SAFETY.md): Data Safety, location (BT discovery), FGS types, battery exemption, IARC, privacy URL, feature graphic, screenshots. Publish the OAuth consent screen for Drive.
