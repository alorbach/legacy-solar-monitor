# Play submission checklist — Legacy Solar Monitor

Use with [PLAY-LISTING.md](PLAY-LISTING.md), [PLAY-DATA-SAFETY.md](PLAY-DATA-SAFETY.md), [PRIVACY.md](PRIVACY.md), and [DEV-github-release.md](DEV-github-release.md).

**Package / applicationId:** `com.alorbach.solarmonitor` (immutable)
**Target / compile SDK:** **36** (required for new apps and updates from 31 August 2026)
**minSdk:** 28

---

## Pre-upload gates

- [ ] `versionCode` bumped for this shipping commit; `versionName` matches intended tag
- [ ] `targetSdk` / `compileSdk` = 36
- [ ] No `SCHEDULE_EXACT_ALARM` in the merged manifest
- [ ] `allowBackup=false` and `dataExtractionRules` / `fullBackupContent` wired
- [ ] Unit tests + lint + `assembleRelease` / `bundleRelease` green
- [ ] Merged release manifest: only expected exported components (`MainActivity`, boot receiver); FileProvider not exported
- [ ] Release build minified; Drive Web client ID present in the shipping AAB
- [ ] Privacy URL reachable on public `main`: `docs/PRIVACY.md`
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

## Local verification log (21 Aug 2026)

Automated (JDK 21):

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | PASS |
| `:app:lintDebug` | PASS (no fatal errors) |
| `:app:assembleDebug` | PASS → `app-debug.apk` |
| `:app:assembleRelease` | PASS (unsigned APK without upload-key env in this shell) |
| `:app:bundleRelease` | PASS → `app-release.aab` |
| Merged release manifest `targetSdkVersion` | **36** |
| `SCHEDULE_EXACT_ALARM` in merged release manifest | **Absent** |
| `allowBackup=false` + `dataExtractionRules` + `fullBackupContent` | **Present** |

Manual / Play Console (still required before production):

| Item | Status |
|---|---|
| Device smoke matrix (BT, live window, import, Drive, widgets, EN/DE) | Pending on hardware |
| Recapture `docs/screenshots/import.png` after URL-hint string change | Pending (UI string fixed) |
| Recapture `docs/screenshots/settings.png` without missing Web client ID error | Pending (needs build with `google.web.client.id`) |
| Internal-track Play-signed install | Pending |
| Data Safety / declarations paste in Console | Pending (docs ready) |

### Residual non-blockers

- Room destructive-migration API deprecation warnings (compile-time only).
- AGP 9.x obsolete DSL warnings (pre-existing).
- Screenshots are dark EN only; DE screenshots optional for listing quality.
- `ImportSourceEntity` remains in schema with little UI write path — documented as stored metadata if present.
