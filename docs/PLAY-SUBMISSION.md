# Play submission checklist — Legacy Solar Monitor

Use with [PLAY-LISTING.md](PLAY-LISTING.md), [PLAY-DATA-SAFETY.md](PLAY-DATA-SAFETY.md), [PRIVACY.md](PRIVACY.md), and [DEV-github-release.md](DEV-github-release.md).

**Package / applicationId:** `com.alorbach.solarmonitor` (immutable)
**Target / compile SDK:** **36** (required for new apps and updates from 31 August 2026)
**minSdk:** 28

---

## Pre-upload gates

- [ ] `versionCode` bumped for this shipping commit; `versionName` matches intended tag
- [ ] Release workflow confirms `versionCode` is greater than the maximum on any other `v*` tag
- [ ] `targetSdk` / `compileSdk` = 36
- [ ] No `SCHEDULE_EXACT_ALARM` in the merged manifest
- [ ] `allowBackup=false` and `dataExtractionRules` / `fullBackupContent` wired
- [ ] Unit tests + lint + `assembleRelease` / `bundleRelease` green
- [ ] Merged release manifest: app-owned exported components are limited to `MainActivity` and the boot receiver; expected library components may also be exported with protecting permissions such as `BIND_REMOTEVIEWS`, `BIND_JOB_SERVICE`, `DUMP`, or Google's revocation permission; FileProvider is not exported
- [ ] Release build minified; Drive Web client ID present in the shipping AAB
- [ ] Privacy URL reachable on public `main`: `https://github.com/alorbach/legacy-solar-monitor/blob/main/docs/PRIVACY.md`
- [ ] EN + DE store strings and screenshots reviewed (no red OAuth config error, no placeholder MACs)

## Play Console — App content

- [ ] Privacy policy URL
- [ ] Data Safety form filled from [PLAY-DATA-SAFETY.md](PLAY-DATA-SAFETY.md) (local storage ≠ collection; location permission ≠ collected GPS)
- [ ] Location declaration: foreground BT discovery only
- [ ] Foreground services: `connectedDevice` (live), `dataSync` (import / WorkManager)
- [ ] Battery optimization exemption rationale
- [ ] Exact alarm: **not declared**
- [ ] Content rating (IARC), target audience (not children), countries
- [ ] Ads / Advertising ID: none

## Store listing

- [ ] Title: **Legacy Solar Monitor** (no SMA in title)
- [ ] Short/full description EN + DE within limits ([PLAY-LISTING.md](PLAY-LISTING.md))
- [ ] Feature graphic + phone screenshots (clean UI; Settings without missing Web client ID error)
- [ ] What’s new for this release

## Signing and OAuth

- [ ] Play App Signing enabled; upload key only in CI secrets
- [ ] Android OAuth clients for upload SHA-1 and Play signing SHA-1
- [ ] OAuth consent: Production or verified for public Drive users; test users for Testing
- [ ] Scope remains `drive.file`

## Internal track validation

- [ ] Install Play-signed AAB from internal testing
- [ ] Bluetooth: unpaired + bonded scan; Test / Live / Sync
- [ ] Live window open/close; background opening shows a resume notification that starts live after the user taps it
- [ ] Import: file, HTTPS; optional LAN HTTP / SFTP
- [ ] Drive: sign-in, backup, restore, sign-out (Drive files remain)
- [ ] Widgets + reports (CSV full / PDF truncated)
- [ ] EN / DE + light / dark
- [ ] Delete device removes local import copies; uninstall clears app data

## Blocker watchlist (do not ship if open)

| Blocker | Why |
|---|---|
| `targetSdk` still 35 after 31 Aug 2026 | Play rejects updates |
| Exact-alarm permission reintroduced | Extra Play review / policy risk |
| Privacy / Data Safety contradict code | Policy rejection |
| Shipping AAB missing Web client ID | Drive broken for all users |
| Screenshots show config errors | Store quality / trust |

## After production

- [ ] Monitor ANRs / crashes in Play Console
- [ ] Keep next `versionCode` ready for hotfixes
- [ ] Do not reuse version codes

---

## Local verification log (23 Aug 2026)

Automated (JDK 21):

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | PASS |
| `:app:lintDebug` | PASS (no fatal errors) |
| `:app:assembleDebug` | PASS → `app-debug.apk` |
| `:app:assembleRelease` | PASS (unsigned APK without upload-key env in this shell) |
| `:app:bundleRelease` | PASS → `app-release.aab` (unsigned without upload-key env) |
| Merged release manifest `targetSdkVersion` | **36** |
| `SCHEDULE_EXACT_ALARM` in merged release manifest | **Absent** |
| `allowBackup=false` + `dataExtractionRules` + `fullBackupContent` | **Present** |
| Release `BuildConfig.GOOGLE_WEB_CLIENT_ID` | **Present** in this local build |
| Play listing short descriptions | **79 / 77 characters** (EN / DE) |

Manual / Play Console (still required before production):

| Item | Status |
|---|---|
| Device smoke matrix (BT, live window, import, Drive, widgets, EN/DE) | Pending on hardware |
| Recapture `docs/screenshots/import.png` after URL-hint string change | Pending (UI string fixed) |
| Recapture `docs/screenshots/settings.png` without missing Web client ID error | Pending (build configuration is ready; screenshot capture still required) |
| Android instrumentation tests | Not run — no device or emulator connected in this environment |
| Internal-track Play-signed install | Pending |
| Data Safety / declarations paste in Console | Pending (docs ready) |

### Residual non-blockers

- Room destructive-migration API deprecation warnings (compile-time only).
- AGP 9.x obsolete DSL warnings (pre-existing).
- Screenshots are dark EN only; DE screenshots optional for listing quality.
- `ImportSourceEntity` remains in schema with little UI write path — documented as stored metadata if present.
- Application-level cleartext traffic is enabled only because private-LAN HTTP URL imports
  remain supported; `UrlImportPolicy` rejects public HTTP. Revisit this trade-off if LAN HTTP
  support is ever removed.
