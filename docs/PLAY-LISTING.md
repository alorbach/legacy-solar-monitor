# Play Store listing copy — Legacy Solar Monitor

Paste into Google Play Console. **App title stays Legacy Solar Monitor** (do not put SMA in the title).

Privacy policy URL (public repo must stay public):

```text
https://github.com/alorbach/legacy-solar-monitor/blob/main/docs/PRIVACY.md
```

High-res icon: `app/src/main/ic_launcher-playstore.png`  
Feature graphic (1024×500): `docs/play/feature-graphic.png`  
Phone screenshots: `docs/screenshots/{start,devices,stats,import,settings}.png`

---

## English

### Short description (max 80 characters)

```text
Live data, archive sync, and SBFspot imports for old Bluetooth solar inverters.
```

### Full description

```text
Legacy Solar Monitor is a free app for old classic-Bluetooth solar inverters (SBFspot-compatible Sunny Boy–class hardware). Read live power, sync day and month archives, import SBFspot CSV/ZIP/SQLite, chart yield, export CSV/PDF reports, and use home-screen widgets.

Optional Google Drive backup uses files this app creates or that you open/share with it (`drive.file`). Google Play services are required for Drive, not for Bluetooth monitoring.

This is an independent hobby project. It is not affiliated with, endorsed by, or an official product of SMA Solar Technology AG. SMA, Sunny Boy, and related names are trademarks of their owners and are used only to describe compatible hardware.

Needs Android 9+, Nearby devices, and precise location (with Location turned on) to discover unpaired inverters. Unrestricted battery helps live monitoring and scheduled imports stay reliable. When the app is backgrounded, the live poll window posts a notification to resume monitoring because inexact alarms cannot start its foreground service directly. URL import allows HTTPS anywhere and HTTP only on private/LAN addresses. FTP is cleartext; prefer SFTP on untrusted networks.
```

### What’s new (1.0.0)

```text
First Play Store release: live Bluetooth monitor, archive sync, SBFspot import, stats, widgets, and optional Drive backup.
```

### Screenshot captions

| File | Caption |
|---|---|
| `docs/screenshots/start.png` | Start: live power, today/month/year yield, and the daily chart |
| `docs/screenshots/devices.png` | Devices: classic Bluetooth scan and inverter profile |
| `docs/screenshots/stats.png` | Stats: hourly, daily, monthly, and yearly yield |
| `docs/screenshots/import.png` | Import: SBFspot CSV, ZIP, SQLite from file, URL, FTP, or SFTP |
| `docs/screenshots/settings.png` | Settings: live interval, poll window, Drive backup, language |

---

## Deutsch

### Kurzbeschreibung (max. 80 Zeichen)

```text
Live-Daten, Archiv-Sync und SBFspot-Import für alte Bluetooth-Wechselrichter.
```

### Vollständige Beschreibung

```text
Legacy Solar Monitor ist eine kostenlose App für alte klassische Bluetooth-Solarwechselrichter (SBFspot-kompatible Sunny-Boy-Klasse). Live-Leistung lesen, Tages- und Monatsarchive synchronisieren, SBFspot-CSV/ZIP/SQLite importieren, Ertrag darstellen, CSV/PDF exportieren und Homescreen-Widgets nutzen.

Optionales Google-Drive-Backup gilt für Dateien, die diese App anlegt oder die Sie mit der App öffnen/teilen (`drive.file`). Google Play-Dienste braucht nur Drive, nicht die Bluetooth-Überwachung.

Unabhängiges Hobbyprojekt. Nicht verbunden mit, nicht unterstützt von und kein offizielles Produkt von SMA Solar Technology AG. SMA, Sunny Boy und verwandte Namen sind Marken ihrer Inhaber und beschreiben nur kompatible Hardware.

Benötigt Android 9+, Geräte in der Nähe und genauen Standort (Standort eingeschaltet), um ungepaarte Wechselrichter zu finden. Uneingeschränkte Batterie hilft Live-Monitor und geplanten Imports. Das Live-Abfragefenster kann einige Minuten verspätet starten (ungenaue Planung). URL-Import: HTTPS überall, HTTP nur im privaten LAN. FTP ist Klartext; im unsicheren Netz SFTP bevorzugen.
```

### Neuigkeiten (1.0.0)

```text
Erste Play-Store-Veröffentlichung: Live-Bluetooth-Monitor, Archiv-Sync, SBFspot-Import, Statistik, Widgets und optionales Drive-Backup.
```

---

## Publisher checklist (not in the APK)

Do these in Play Console / Cloud Console. Do **not** commit keystores or OAuth client IDs.

1. Create an upload key; enable Play App Signing. Store the keystore as GitHub Actions secrets — [DEV-github-release.md](DEV-github-release.md).
2. Register Android OAuth clients with package `com.alorbach.solarmonitor`: debug SHA-1, **upload-key SHA-1**, and later the **Play App Signing SHA-1** (App integrity). See [DEV-google-drive.md](DEV-google-drive.md).
3. Set secret `GOOGLE_WEB_CLIENT_ID` so the tagged AAB has Drive sign-in. Cut a release with `git tag v1.0.0 && git push origin v1.0.0`.
4. Publish the OAuth consent screen to **Production** (or start sensitive-scope verification for `drive.file`). Until then only listed test users can sign in.
5. Console declarations:
   - Data Safety — copy [PLAY-DATA-SAFETY.md](PLAY-DATA-SAFETY.md)
   - Location: foreground only, to discover unpaired classic Bluetooth devices (not GPS tracking; coordinates not stored)
   - Foreground services: `connectedDevice` (live monitor during the poll window; background resume requires a user tap), `dataSync` (import / WorkManager)
   - Battery optimization exemption: live Bluetooth polling and scheduled FTP/SFTP imports
   - Do **not** declare exact alarms
   - Content rating (IARC), target audience, countries
   - Privacy policy URL above
   - Feature graphic + at least two phone screenshots (Settings must not show a missing Web client ID error)
6. Leave `android:allowBackup="false"` and wired data-extraction / backup rules. Do not turn on Auto Backup.
7. Drive backup needs Google Play services on the device; Bluetooth monitoring does not.
8. Full gate list: [PLAY-SUBMISSION.md](PLAY-SUBMISSION.md).

### Console field map (copy from this repo)

| Play Console place | What to paste / upload |
|---|---|
| Store listing → App name | `Legacy Solar Monitor` (never put SMA in the title) |
| Short / full description, What’s new | English and German sections above |
| Graphic assets | Icon, `docs/play/feature-graphic.png`, `docs/screenshots/` |
| App content → Privacy policy | `https://github.com/alorbach/legacy-solar-monitor/blob/main/docs/PRIVACY.md` |
| App content → Data safety | [PLAY-DATA-SAFETY.md](PLAY-DATA-SAFETY.md) |
| App content → Location | Foreground only; unpaired classic Bluetooth discovery; not maps or tracking |
| App content → Foreground services | `connectedDevice` (live monitor in the poll window), `dataSync` (import / WorkManager) |
| App content → Battery | Unrestricted: live Bluetooth polling and scheduled FTP/SFTP imports |
| App content → Target audience | Not designed for children; no Families program |
| App content → News / COVID / Health | None of these |
| IARC questionnaire | Utility / tools; no user-generated public content; no violence, gambling, or ads |
| Production / testing | Upload the **AAB** from the GitHub Release, not the APK |
