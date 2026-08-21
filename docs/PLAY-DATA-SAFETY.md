# Play Console Data Safety — answers from the code

Use this when filling **App content → Data safety**. It matches Legacy Solar Monitor as implemented (`com.alorbach.solarmonitor`). Privacy prose for users: [PRIVACY.md](PRIVACY.md).

**Does your app collect or share user data?** Yes (collected on device; optionally uploaded to the user’s own Google Drive).

**Is all user data encrypted in transit?** Partial / no (see encryption). Do **not** claim “yes” for all data.

**Do you provide a way for users to request that their data is deleted?** Yes — delete a device, clear history, sign out of Drive, delete Drive files, or uninstall. There is no vendor-hosted account.

---

## Data types

### Location

- **Collected?** Yes (permission). **Shared?** No.
- **Approximate / precise:** Precise location permission is requested.
- **Data:** The app does **not** store latitude/longitude. Precise location is required by Android so unpaired **classic Bluetooth** inverters appear in Scan. Location services must be on for that discovery.
- **Ephemeral?** Yes for GPS (not persisted). Bluetooth scan results (name, MAC) may be saved if the user creates a device profile.
- **Required / optional:** Required for unpaired discovery; bonded devices can still be listed without a successful unpaired scan.
- **Purpose:** App functionality.

### Personal info — Email

- **Collected?** Yes, if the user signs in for Drive. **Shared?** With Google as the Drive account holder (user’s own account), not with the developer.
- **Required / optional:** Optional (Drive backup only).
- **Purpose:** App functionality (account picker / backup).

### Device or other IDs

- **Bluetooth MAC** of the inverter: stored in Room. **Shared?** Only if Drive backup of the database is enabled (user’s Drive).
- **Purpose:** App functionality.

### App activity / app info and performance

- No Firebase, Crashlytics, Sentry, advertising, or analytics SDKs.
- `domain/Analytics.kt` is local earnings math, not telemetry.
- Diagnostics text (connection log, may include MAC) stays on device in Room `lastDiagnostics` and can be in a Drive DB snapshot if backup is on.
- **Crash logs / diagnostics collected by Google Play:** only if the user opted into Play’s own sharing; the app does not add a crash SDK.

### Photos, video, audio, contacts, calendar, health, finance, messages

- **None.** Local file import uses the system document picker (SAF), not photo/media permissions. Feed-in tariffs and earnings are local calculations, not a financial service.

### Files and docs

- Optional copies of imported SBFspot files in app-private storage; may be uploaded to Drive if that backup option is on.
- CSV/PDF reports in cache, shared only via the share sheet the user starts.

### App data (energy history)

- Room `solar-monitor.db`: devices, spot samples, day/month/hour aggregates, events, tariffs, import job metadata.
- **Shared?** Only to the user’s Google Drive when backup is enabled.

### Authentication / credentials

- SMA user PIN and FTP/SFTP passwords: on-device **encrypted** storage. **Not** included in Drive backup. **Not** shared.
- Google OAuth access tokens are not persisted; Play Services issues them. Scope: `https://www.googleapis.com/auth/drive.file` only.

---

## Data sharing

- **Sold?** No.
- **Shared with third parties?** No developer-operated backend. Optional upload is to **Google Drive owned by the user**. Declare Google as a service provider for that optional backup, or as “user-initiated sharing”, matching Play’s current Drive wording.
- **Transferred off the device?** Yes when Drive backup, URL/FTP/SFTP import, or user share is used.

---

## Security practices

| Question | Answer |
|---|---|
| Data encrypted in transit | **Partial.** Drive and SFTP use TLS/SSH. HTTPS URL import uses TLS. **FTP and HTTP on a private LAN are cleartext** (user-entered). |
| Users can request deletion | Yes (local delete / uninstall / delete Drive files). |
| Independent security review | No. |
| Committed to Play Families / designed for children | No. |
| Ads | No. Do not declare Advertising ID. |

---

## Permissions that need extra Console declarations

These are not Data Safety rows but often sit next to it:

| Declaration | What to write |
|---|---|
| Location | Foreground only; classic Bluetooth discovery of unpaired inverters; not used for maps or tracking |
| Foreground service `connectedDevice` | Live Bluetooth monitor during a configurable daily window (default 06:00–22:00 in each inverter timezone). The service stops outside that window and resumes at the next start. |
| Foreground service `dataSync` | Long SBFspot import (file/URL/FTP/SFTP) and WorkManager backup/import |
| Battery unrestricted | Live Bluetooth polling and scheduled imports on OEMs that otherwise kill the service |
| Photos / videos | Not used |
| Health / financial features | Not used |

---

## Suggested “collected” vs “shared” ticks

| Type | Collected | Shared |
|---|---|---|
| Precise location (permission; no lat/lng stored) | Yes | No |
| Email | Yes (optional) | User’s Drive / Google account only |
| Device identifiers (BT MAC) | Yes | Only via optional Drive DB backup |
| App data (yield history) | Yes | Only via optional Drive backup |
| Files (import copies) | Yes | Only if that Drive option is on |
| Credentials (PIN / FTP) | Yes, on device | **No** |
| Analytics / ads / crash SDK | No | No |
