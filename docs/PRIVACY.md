# Privacy policy — Legacy Solar Monitor

**Last updated:** 21 August 2026
**Contact:** Andre Lorbach, [alorbach@adiscon.com](mailto:alorbach@adiscon.com)  
**App:** Legacy Solar Monitor (`com.alorbach.solarmonitor`)

This is an independent hobby app. It is not affiliated with SMA Solar Technology AG.

---

## English

### What the app does

Legacy Solar Monitor talks to **classic Bluetooth** solar inverters (SBFspot-compatible), stores yield history on the phone, can import SBFspot files, and can optionally back up that data to **your** Google Drive.

The app does **not** include advertising, analytics, crash reporters, or third-party trackers.

### Data stored on the device

| Data | Why |
|---|---|
| Inverter profiles (name, Bluetooth MAC, serial, timezone, model, plant name, owner / plant operator) | Identify and connect to your inverter |
| Reserved profile fields `address`, `latitude`, `longitude` | Present in the database schema for future use; the current UI does **not** edit or fill GPS coordinates |
| Energy history (power in W, yield in Wh, events, tariffs) | Charts, reports, widgets |
| Connection diagnostics text (`lastDiagnostics`, may include MAC / RFCOMM details) | Troubleshooting Test / Live / Sync |
| Import job metadata (source labels, hosts, paths, usernames, status, opaque credential IDs, optional preserved-copy paths) | Re-run / schedule imports |
| Optional copies of imported files under app-private `imports/` | Optional Drive backup of originals |
| SMA user PIN and FTP/SFTP passwords | Login to the inverter and to import servers you configure |
| Google account email | Only if you sign in for Drive backup |
| SFTP host keys (trust-on-first-use) | Verify later SFTP connections |
| App settings (language, live interval/window, widget device, chart color, warning toggles) | Preferences |

PINs and import passwords are stored in **encrypted** app storage (`EncryptedSharedPreferences`), not as plaintext in the Room database. Room may keep opaque `cred_…` IDs that reference those secrets.

### Location

The app requests **precise location** so Android can discover **unpaired** classic Bluetooth inverters. Location (GPS) must also be turned on for that scan.

The app does **not** read or store GPS coordinates for tracking, does **not** track your position, and does **not** use background location. Schema fields `latitude` / `longitude` are unused by the current editor.

### Bluetooth

Nearby-devices permission is used to scan, pair/connect, read live values, and sync archives. Bluetooth MAC addresses are stored locally in the device profile.

### Optional Google Drive backup

If you tap **Sign in with Google**, the app uses the Drive scope `https://www.googleapis.com/auth/drive.file` (the app’s backup folder and files it creates, or that you open/share with it — not broad access to your entire Drive). It may upload:

- a snapshot of `solar-monitor.db` (devices, history, events, tariffs, import job metadata, diagnostics — **not** PINs or FTP/SFTP passwords)
- optional copies of imported files

into a Drive folder named **Legacy Solar Monitor**.

Restore replaces the local Room database. **SMA PINs and FTP/SFTP passwords stay on this phone** and are not in the backup. After restore on another device you must re-enter them. Restore does not restore or clear local `imports/` file copies.

**Sign out** in Settings disconnects the Google account in the app. It does **not** delete files already in your Google Drive. Google’s own privacy policy applies to your Google account.

### Network imports (URL / FTP / SFTP)

You may import SBFspot CSV, ZIP, or SQLite from a file, HTTPS URL, HTTP on a **private/LAN** address, FTP, or SFTP. FTP and LAN HTTP send data (including passwords for FTP) **without TLS**. Prefer SFTP or HTTPS when the server is not on your LAN.

The app does not upload import passwords to Google Drive.

### Sharing you start

CSV/PDF reports are written to app cache and shared only when you use the system share sheet.

### Children

The app is not directed at children.

### Retention and deletion

| Action | What is removed |
|---|---|
| Clear history (device) | Spot samples, day/month/hour aggregates, and events for that device |
| Delete device | Profile, Room history, events, tariffs, import jobs for that device, encrypted PIN for that device, and local `imports/device-<id>/` copies |
| Remove import job / clear import list | Job history rows only (solar data stays); orphaned encrypted import passwords are reclaimed when unused |
| Restore from Drive | Replaces `solar-monitor.db`; keeps local credentials; does not sync `imports/` copies |
| Sign out of Drive | Local Google account link only — Drive files remain |
| Uninstall | Removes local app data (Room, encrypted prefs, imports, cache) |
| Delete in Google Drive | Required to remove uploaded backup files |

### Android system backup

Cloud Auto Backup is disabled (`allowBackup=false`). Explicit data-extraction / full-backup rules exclude app databases, files, and preferences from cloud backup and, where the OEM honors them, from device-to-device transfer. Some manufacturers may still migrate app data during phone setup; treat Google Drive backup as the supported path for moving data.

### Changes

Updates to this policy will be posted in this file in the public source repository.

---

## Deutsch

### Was die App macht

Legacy Solar Monitor spricht mit **klassischen Bluetooth**-Solarwechselrichtern (SBFspot-kompatibel), speichert Ertragshistorie auf dem Telefon, kann SBFspot-Dateien importieren und optional in **Ihr** Google Drive sichern.

Die App enthält **keine** Werbung, keine Analyse-SDKs, keine Crash-Reporter und keine Tracker Dritter.

### Daten auf dem Gerät

| Daten | Zweck |
|---|---|
| Wechselrichter-Profile (Name, Bluetooth-MAC, Seriennummer, Zeitzone, Modell, Anlagenname, Anlagenbetreiber) | Gerät erkennen und verbinden |
| Reservierte Felder `address`, `latitude`, `longitude` | Im Schema vorhanden; die aktuelle Oberfläche pflegt **keine** GPS-Koordinaten |
| Energiehistorie (Leistung in W, Ertrag in Wh, Ereignisse, Tarife) | Diagramme, Berichte, Widgets |
| Verbindungsdiagnose (`lastDiagnostics`, ggf. mit MAC) | Fehlersuche |
| Import-Metadaten (Quellen, Hosts, Pfade, Benutzername, Status, Credential-IDs, optionale Kopienpfade) | Erneuter / geplanter Import |
| Optionale Kopien importierter Dateien unter `imports/` | Optionales Drive-Backup der Originale |
| SMA-Benutzer-PIN und FTP/SFTP-Passwörter | Anmeldung am Wechselrichter und an Import-Servern |
| Google-Konto-E-Mail | Nur bei Anmeldung für Drive-Backup |
| SFTP-Hostschlüssel (TOFU) | Spätere SFTP-Verbindungen prüfen |
| App-Einstellungen | Präferenzen |

PINs und Import-Passwörter liegen in **verschlüsseltem** App-Speicher (`EncryptedSharedPreferences`). Room speichert ggf. undurchsichtige `cred_…`-IDs.

### Standort

Die App fordert **genauen Standort** an, damit Android **ungepaarte** klassische Bluetooth-Wechselrichter finden kann. Der Standort (GPS) muss für diesen Scan ebenfalls eingeschaltet sein.

Die App speichert **keine** GPS-Koordinaten zur Verfolgung, verfolgt Ihren Standort nicht und nutzt keinen Hintergrund-Standort.

### Bluetooth

Die Berechtigung „Geräte in der Nähe“ dient zum Scannen, Verbinden, Live-Lesen und Archiv-Sync. Bluetooth-MAC-Adressen werden lokal im Geräteprofil gespeichert.

### Optionales Google-Drive-Backup

Wenn Sie **Mit Google anmelden** tippen, nutzt die App den Drive-Scope `https://www.googleapis.com/auth/drive.file` (Backup-Ordner und Dateien, die diese App anlegt oder die Sie mit der App öffnen/teilen — kein Vollzugriff auf Drive). Hochgeladen werden können:

- ein Abbild von `solar-monitor.db` (Geräte, Historie, Ereignisse, Tarife, Import-Metadaten, Diagnose — **keine** PINs oder FTP/SFTP-Passwörter)
- optional Kopien importierter Dateien

in einen Drive-Ordner **Legacy Solar Monitor**.

Wiederherstellen ersetzt die lokale Room-Datenbank. **SMA-PINs und FTP/SFTP-Passwörter bleiben auf diesem Telefon**. Nach dem Wiederherstellen auf einem anderen Gerät müssen Sie sie erneut eingeben. Lokale Importkopien unter `imports/` werden weder wiederhergestellt noch gelöscht.

**Abmelden** trennt das Google-Konto in der App und löscht **nicht** Dateien in Google Drive. Für das Google-Konto gilt die Datenschutzerklärung von Google.

### Netzwerk-Import (URL / FTP / SFTP)

Sie können SBFspot-CSV, ZIP oder SQLite aus einer Datei, einer HTTPS-URL, HTTP auf einer **privaten/LAN**-Adresse, per FTP oder SFTP importieren. FTP und LAN-HTTP übertragen Daten (einschließlich FTP-Passwörter) **ohne TLS**. Nutzen Sie SFTP oder HTTPS, wenn der Server nicht im LAN liegt.

Import-Passwörter werden nicht zu Google Drive hochgeladen.

### Teilen, das Sie selbst starten

CSV/PDF-Berichte liegen im App-Cache und werden nur über das System-Teilen-Menü weitergegeben.

### Kinder

Die App richtet sich nicht an Kinder.

### Aufbewahrung und Löschen

| Aktion | Was entfernt wird |
|---|---|
| Historie löschen | Spot-Samples, Aggregate und Events des Geräts |
| Gerät löschen | Profil, Room-Historie, Events, Tarife, Import-Jobs, PIN und lokale `imports/device-<id>/`-Kopien |
| Importversuch entfernen | Nur Verlaufseinträge; Solardaten bleiben |
| Drive-Wiederherstellung | Ersetzt `solar-monitor.db`; lokale Geheimnisse bleiben; `imports/` wird nicht synchronisiert |
| Drive abmelden | Nur lokale Kontoverknüpfung — Drive-Dateien bleiben |
| Deinstallieren | Entfernt lokale App-Daten |
| In Google Drive löschen | Erforderlich, um hochgeladene Backup-Dateien zu entfernen |

### Android-Systemsicherung

Cloud-Auto-Backup ist aus (`allowBackup=false`). Explizite Data-Extraction-/Backup-Regeln schließen Datenbanken, Dateien und Einstellungen aus. Manche Hersteller können bei Gerätewechsel trotzdem migrieren; Google Drive ist der unterstützte Weg.

### Änderungen

Aktualisierungen dieser Erklärung stehen in dieser Datei im öffentlichen Quellcode-Repository.
