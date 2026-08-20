# Privacy policy — Legacy Solar Monitor

**Last updated:** 20 August 2026  
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
| Inverter profiles (name, Bluetooth MAC, serial, timezone, model) | Identify and connect to your inverter |
| Energy history (power in W, yield in Wh, events, tariffs) | Charts, reports, widgets |
| SMA user PIN and FTP/SFTP passwords | Login to the inverter and to import servers you configure |
| Google account email | Only if you sign in for Drive backup |
| SFTP host keys (trust-on-first-use) | Verify later SFTP connections |

PINs and import passwords are stored in **encrypted** app storage (`EncryptedSharedPreferences`), not in the Room database. System cloud backup of the app is **disabled**.

### Location

The app requests **precise location** so Android can discover **unpaired** classic Bluetooth inverters. Location (GPS) must also be turned on for that scan.

The app does **not** read or store GPS coordinates, does **not** track your position, and does **not** use background location.

### Bluetooth

Nearby-devices permission is used to scan, pair/connect, read live values, and sync archives. Bluetooth MAC addresses are stored locally in the device profile.

### Optional Google Drive backup

If you tap **Sign in with Google**, the app uses the Drive scope `https://www.googleapis.com/auth/drive.file` (only files this app creates). It may upload:

- a snapshot of `solar-monitor.db` (devices, history, events, tariffs — **not** PINs or FTP/SFTP passwords)
- optional copies of imported files

into a Drive folder named **Legacy Solar Monitor**.

Restore replaces the local database. **SMA PINs and FTP/SFTP passwords stay on this phone** and are not in the backup. After restore on another device you must re-enter them.

You can sign out in Settings. Google’s own privacy policy applies to your Google account.

### Network imports (URL / FTP / SFTP)

You may import SBFspot CSV, ZIP, or SQLite from a file, HTTPS URL, HTTP on a **private/LAN** address, FTP, or SFTP. FTP and LAN HTTP send data (including passwords for FTP) **without TLS**. Prefer SFTP or HTTPS when the server is not on your LAN.

The app does not upload import passwords to Google Drive.

### Sharing you start

CSV/PDF reports are written to app cache and shared only when you use the system share sheet.

### Children

The app is not directed at children.

### Retention and deletion

Data stays on the device until you delete a device, clear history, restore a backup, or uninstall the app. Uninstalling removes local data. Drive files remain in your Google Drive until you delete them there.

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
| Wechselrichter-Profile (Name, Bluetooth-MAC, Seriennummer, Zeitzone, Modell) | Gerät erkennen und verbinden |
| Energiehistorie (Leistung in W, Ertrag in Wh, Ereignisse, Tarife) | Diagramme, Berichte, Widgets |
| SMA-Benutzer-PIN und FTP/SFTP-Passwörter | Anmeldung am Wechselrichter und an von Ihnen konfigurierten Import-Servern |
| Google-Konto-E-Mail | Nur bei Anmeldung für Drive-Backup |
| SFTP-Hostschlüssel (Trust-on-first-use) | Spätere SFTP-Verbindungen prüfen |

PINs und Import-Passwörter liegen in **verschlüsseltem** App-Speicher (`EncryptedSharedPreferences`), nicht in der Room-Datenbank. Die System-Cloud-Sicherung der App ist **aus**.

### Standort

Die App fordert **genauen Standort** an, damit Android **ungepaarte** klassische Bluetooth-Wechselrichter finden kann. Der Standort (GPS) muss für diesen Scan ebenfalls eingeschaltet sein.

Die App liest und speichert **keine** GPS-Koordinaten, verfolgt Ihren Standort nicht und nutzt keinen Hintergrund-Standort.

### Bluetooth

Die Berechtigung „Geräte in der Nähe“ dient zum Scannen, Verbinden, Live-Lesen und Archiv-Sync. Bluetooth-MAC-Adressen werden lokal im Geräteprofil gespeichert.

### Optionales Google-Drive-Backup

Wenn Sie **Mit Google anmelden** tippen, nutzt die App den Drive-Scope `https://www.googleapis.com/auth/drive.file` (nur Dateien, die diese App anlegt). Hochgeladen werden können:

- ein Abbild von `solar-monitor.db` (Geräte, Historie, Ereignisse, Tarife — **keine** PINs oder FTP/SFTP-Passwörter)
- optional Kopien importierter Dateien

in einen Drive-Ordner **Legacy Solar Monitor**.

Wiederherstellen ersetzt die lokale Datenbank. **SMA-PINs und FTP/SFTP-Passwörter bleiben auf diesem Telefon** und stecken nicht im Backup. Nach dem Wiederherstellen auf einem anderen Gerät müssen Sie sie erneut eingeben.

Abmelden geht in den Einstellungen. Für das Google-Konto gilt die Datenschutzerklärung von Google.

### Netzwerk-Import (URL / FTP / SFTP)

Sie können SBFspot-CSV, ZIP oder SQLite aus einer Datei, einer HTTPS-URL, HTTP auf einer **privaten/LAN**-Adresse, per FTP oder SFTP importieren. FTP und LAN-HTTP übertragen Daten (einschließlich FTP-Passwörter) **ohne TLS**. Nutzen Sie SFTP oder HTTPS, wenn der Server nicht im LAN liegt.

Import-Passwörter werden nicht zu Google Drive hochgeladen.

### Teilen, das Sie selbst starten

CSV/PDF-Berichte liegen im App-Cache und werden nur über das System-Teilen-Menü weitergegeben.

### Kinder

Die App richtet sich nicht an Kinder.

### Aufbewahrung und Löschen

Daten bleiben auf dem Gerät, bis Sie ein Gerät löschen, die Historie leeren, ein Backup wiederherstellen oder die App deinstallieren. Deinstallieren entfernt lokale Daten. Drive-Dateien bleiben in Ihrem Google Drive, bis Sie sie dort löschen.

### Änderungen

Aktualisierungen dieser Erklärung stehen in dieser Datei im öffentlichen Quellcode-Repository.
