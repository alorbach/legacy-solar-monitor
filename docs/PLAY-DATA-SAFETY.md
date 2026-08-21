# Play Console Data Safety — answers from the code

Use this when filling **App content → Data safety**. It matches Legacy Solar Monitor as implemented (`com.alorbach.solarmonitor`). Privacy prose for users: [PRIVACY.md](PRIVACY.md).

**Last verified against code:** 21 August 2026
**Play definition:** [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469) — **“Collect” means transmitting data off the user’s device.** Local-only storage is not collection.

---

## Top-level answers

| Question | Answer |
|---|---|
| Does your app collect or share user data? | **Yes**, when the user enables optional Google Drive backup (and when the user starts URL/FTP/SFTP import or share — declare those transfers accurately). Purely on-device Room / encrypted prefs are **not** “collected” under Play’s definition. |
| Is all user data encrypted in transit? | **No / Partial.** Do **not** claim “yes” for all data. Drive and SFTP use TLS/SSH; HTTPS URL import uses TLS; **FTP and private LAN HTTP are cleartext**. |
| Way for users to request deletion? | **Yes** — delete a device, clear history, uninstall, or delete Drive files. Sign-out alone does **not** delete Drive files. No vendor-hosted account. |

---

## Data types

### Location

- **Collected (off device)?** **No.** Precise location permission is used only so unpaired classic Bluetooth devices appear in Scan. GPS coordinates are not persisted. Schema `latitude`/`longitude` exist but are unused by the current UI.
- **Shared?** No.
- **Ephemeral?** Permission / system location for discovery only.
- **Required / optional:** Required for unpaired discovery; bonded devices can still be listed without a successful unpaired scan.
- **Purpose (permission declaration):** App functionality — foreground Bluetooth discovery, not maps or tracking.
- **Do not** mark Precise location as collected solely because `ACCESS_FINE_LOCATION` is declared.

### Personal info — Email

- **Collected?** Yes, if the user signs in for Drive (account email used for backup). **Shared?** With Google as the Drive account holder (user’s own account), not with the developer as a separate backend.
- **Required / optional:** Optional (Drive backup only).
- **Purpose:** App functionality.

### Device or other IDs

- **Bluetooth MAC** of the inverter: stored in Room (local). **Collected off device?** Only if Drive database backup is enabled (user’s Drive).
- **Purpose:** App functionality.

### App activity / app info and performance

- No Firebase, Crashlytics, Sentry, advertising, or analytics SDKs.
- `domain/Analytics.kt` is local earnings math, not telemetry.
- Diagnostics text (connection log, may include MAC) stays on device in Room `lastDiagnostics` and can be in a Drive DB snapshot if backup is on.
- **Crash logs collected by Google Play:** only if the user opted into Play’s own sharing; the app does not add a crash SDK.

### Photos, video, audio, contacts, calendar, health, finance, messages

- **None.** Local file import uses the system document picker (SAF). Feed-in tariffs and earnings are local estimates, not a financial service.

### Files and docs

- Optional copies of imported SBFspot files in app-private storage; may be uploaded to Drive if that backup option is on.
- CSV/PDF reports in cache, shared only via the share sheet the user starts.

### App data (energy history)

- Room `solar-monitor.db`: devices (including owner/plant names), spot samples, day/month/hour aggregates, events, tariffs, import job metadata, diagnostics.
- **Collected off device?** Only to the user’s Google Drive when database backup is enabled.

### Authentication / credentials

- SMA user PIN and FTP/SFTP passwords: on-device **encrypted** storage. **Not** included in Drive backup. **Not** shared off device by the app.
- Opaque credential IDs may appear in Room / WorkManager input — not the secret values.
- Google OAuth access tokens are not persisted by the app; Play Services issues them. Scope: `https://www.googleapis.com/auth/drive.file` only.

---

## Data sharing

- **Sold?** No.
- **Shared with third parties?** No developer-operated backend. Optional upload is to **Google Drive owned by the user**. Declare per Play’s current Drive / user-initiated backup wording (service provider or user-initiated as applicable).
- **Transferred off the device?** Yes when Drive backup, URL/FTP/SFTP import, or user share is used.

---

## Security practices

| Question | Answer |
|---|---|
| Data encrypted in transit | **Partial.** Drive and SFTP use TLS/SSH. HTTPS URL import uses TLS. **FTP and HTTP on a private LAN are cleartext** (user-entered). |
| Users can request deletion | Yes (local delete / uninstall / delete Drive files). Sign-out ≠ Drive deletion. |
| Independent security review | No. |
| Designed for children / Families | No. |
| Ads | No. Do not declare Advertising ID. |

---

## Permissions that need extra Console declarations

| Declaration | What to write |
|---|---|
| Location | Foreground only; classic Bluetooth discovery of unpaired inverters; not used for maps or tracking; coordinates not stored |
| Foreground service `connectedDevice` | Live Bluetooth monitor during a configurable daily window (default 06:00–22:00 in each inverter timezone). The service stops outside the window. An inexact alarm posts a resume notification when the app is backgrounded; the user opens the app to start the service because the alarm is not a background-FGS exemption. |
| Foreground service `dataSync` | Long SBFspot import (file/URL/FTP/SFTP) and WorkManager backup/import |
| Battery unrestricted | Live Bluetooth polling and scheduled imports on OEMs that otherwise kill the service |
| Exact alarm | **Not used.** Do not declare `SCHEDULE_EXACT_ALARM`. |
| Photos / videos | Not used |
| Health / financial features | Not used |

---

## Suggested “collected” vs “shared” ticks (Play = off-device)

| Type | Collected (off device) | Shared |
|---|---|---|
| Precise location | **No** (permission only; no lat/lng stored) | No |
| Email | Yes (optional, Drive sign-in) | User’s Drive / Google account only |
| Device identifiers (BT MAC) | Only via optional Drive DB backup | Only via optional Drive DB backup |
| App data (yield history, profiles, diagnostics, import metadata) | Only via optional Drive DB backup | Only via optional Drive backup |
| Files (import copies) | Only if that Drive option is on | Only if that Drive option is on |
| Credentials (PIN / FTP) | **No** (stay on device encrypted) | **No** |
| Analytics / ads / crash SDK | No | No |

If the Console forces a “does the app collect data?” yes/no before optional Drive: answer **Yes** because optional Drive backup transmits app data and email, then complete the rows above accurately.
