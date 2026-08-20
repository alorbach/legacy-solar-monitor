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

Optional Google Drive backup uses only files this app creates (drive.file). Google Play services are required for Drive, not for Bluetooth monitoring.

This is an independent hobby project. It is not affiliated with, endorsed by, or an official product of SMA Solar Technology AG. SMA, Sunny Boy, and related names are trademarks of their owners and are used only to describe compatible hardware.

Needs Android 9+, Nearby devices, and precise location (with Location turned on) to discover unpaired inverters. Unrestricted battery helps live monitoring and scheduled imports stay reliable. URL import allows HTTPS anywhere and HTTP only on private/LAN addresses. FTP is cleartext; prefer SFTP on untrusted networks.
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
| `docs/screenshots/settings.png` | Settings: live interval, Drive backup, language |

---

## Deutsch

### Kurzbeschreibung (max. 80 Zeichen)

```text
Live-Daten, Archiv-Sync und SBFspot-Import für alte Bluetooth-Wechselrichter.
```

### Vollständige Beschreibung

```text
Legacy Solar Monitor ist eine kostenlose App für alte klassische Bluetooth-Solarwechselrichter (SBFspot-kompatible Sunny-Boy-Klasse). Live-Leistung lesen, Tages- und Monatsarchive synchronisieren, SBFspot-CSV/ZIP/SQLite importieren, Ertrag darstellen, CSV/PDF exportieren und Homescreen-Widgets nutzen.

Optionales Google-Drive-Backup gilt nur für Dateien, die diese App anlegt (drive.file). Google Play-Dienste braucht nur Drive, nicht die Bluetooth-Überwachung.

Unabhängiges Hobbyprojekt. Nicht verbunden mit, nicht unterstützt von und kein offizielles Produkt von SMA Solar Technology AG. SMA, Sunny Boy und verwandte Namen sind Marken ihrer Inhaber und beschreiben nur kompatible Hardware.

Benötigt Android 9+, Geräte in der Nähe und genauen Standort (Standort eingeschaltet), um ungepaarte Wechselrichter zu finden. Uneingeschränkte Batterie hilft Live-Monitor und geplanten Imports. URL-Import: HTTPS überall, HTTP nur im privaten LAN. FTP ist Klartext; im unsicheren Netz SFTP bevorzugen.
```

### Neuigkeiten (1.0.0)

```text
Erste Play-Store-Veröffentlichung: Live-Bluetooth-Monitor, Archiv-Sync, SBFspot-Import, Statistik, Widgets und optionales Drive-Backup.
```

---

## Publisher checklist (not in the APK)

Do these in Play Console / Cloud Console. Do **not** commit keystores or OAuth client IDs.

1. Create an upload key; enable Play App Signing.
2. Register a second **Android** OAuth client with package `com.alorbach.solarmonitor` and the **Play App Signing SHA-1** (App integrity). Keep the debug SHA-1 client for local builds. See [DEV-google-drive.md](DEV-google-drive.md).
3. Build the release AAB with `google.web.client.id` / `GOOGLE_WEB_CLIENT_ID` set so Drive sign-in works.
4. Publish the OAuth consent screen to **Production** (or start sensitive-scope verification for `drive.file`). Until then only listed test users can sign in.
5. Console declarations:
   - Data Safety — copy [PLAY-DATA-SAFETY.md](PLAY-DATA-SAFETY.md)
   - Location: foreground only, to discover unpaired classic Bluetooth devices (not GPS tracking)
   - Foreground services: `connectedDevice` (live monitor), `dataSync` (import / WorkManager)
   - Battery optimization exemption: live Bluetooth polling and scheduled FTP/SFTP imports
   - Content rating (IARC), target audience, countries
   - Privacy policy URL above
   - Feature graphic + at least two phone screenshots
6. Leave `android:allowBackup="false"` in the manifest. Do not turn on Auto Backup.
7. Drive backup needs Google Play services on the device; Bluetooth monitoring does not.
