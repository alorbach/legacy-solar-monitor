# Benutzerhandbuch

Legacy Solar Monitor ist eine kostenlose Android-App für **klassische Bluetooth-SMA-Sunny-Boy-Wechselrichter** (SBFspot-kompatibel). Die Tab-Namen unten entsprechen der deutschen Oberfläche (`Start`, `Statistik`, `Geräte`, `Import`, `Einstellungen`). Die App-Sprache kann System, Deutsch oder English sein.

Englische Fassung: [USER-GUIDE.md](USER-GUIDE.md).

Dieses Projekt ist nicht mit SMA Solar Technology AG verbunden. SMA- und Sunny-Boy-Namen beschreiben nur kompatible Hardware.

## Was Sie brauchen

- Einen Wechselrichter mit dem **SBFspot-kompatiblen SMA-Bluetooth**-Protokoll. Es gibt keinen Hersteller- oder Protokollwähler; jedes Gerät nutzt diesen Stack.
- Viele dieser Wechselrichter bleiben **ungepaart**. Das ist normal. Android braucht trotzdem Geräte in der Nähe, **genauen Standort** und eingeschalteten Standort (GPS), damit sie in der Suche erscheinen.
- Ein Profil pro Bluetooth-MAC.

Geräte derselben Generation lassen sich meist ohne Codeänderung in der UI hinzufügen. Siehe [DEV-add-device.md](DEV-add-device.md).

## Erster Start

1. App installieren und **Geräte** öffnen.
2. Bluetooth und Standort einschalten und **Geräte in der Nähe** sowie
   **genauen Standort** erlauben. Der genaue Standort ist bei diesen meist
   ungepaarten Wechselrichtern für die Bluetooth-Suche nötig; GPS-Daten werden
   von der App nicht aufgezeichnet.
3. Suchen, für jeden Wechselrichter ein Profil anlegen, die Benutzer-PIN
   eintragen und zuerst **Test** ausführen. Danach können Live und Sync
   gestartet werden.
4. Einen Einspeisetarif nur für lokale Erlösschätzungen anlegen. Überwachung
   und Import funktionieren auch ohne Tarif.

Die App unterstützt ausschließlich den klassischen SMA-Bluetooth-/SBFspot-Stack.
Bluetooth Low Energy, Speedwire und fremde Wechselrichter-Protokolle werden
nicht gefunden. Der Import von SBFspot-Dateien ist unabhängig von der
Bluetooth-Unterstützung.

## Berechtigungen

| Berechtigung / Einstellung | Zweck |
|---|---|
| Geräte in der Nähe (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` ab Android 12) | Suchen, Verbinden, Live und Archiv-Sync |
| Genauer Standort | Klassische Erkennung **ungepaarter** Geräte (die App speichert **keine** GPS-Koordinaten) |
| Standort (GPS) an | Dieselbe ungepaarte Erkennung |
| Bluetooth an | Jede Bluetooth-Aktion |
| Benachrichtigungen (Android 13+) | Live-Monitor, Importfortschritt, Wechselrichter-Warnungen. Abgefragt beim Start des Live-Monitors oder beim Einschalten der Warnungen, nicht beim App-Start. Warnungen sind standardmäßig aus. |
| Uneingeschränkter Akku | Geplante Importe, lange FTP-Transfers und stabile Live-Überwachung |
| Internet | URL-/FTP-/SFTP-Import und Drive-Backup |
| Speicher über den System-Dateiauswähler | Lokaler Dateiimport (keine breite Speicherberechtigung) |

Das gelbe Banner oben öffnet App-, Standort- oder Bluetooth-Einstellungen, wenn etwas fehlt.

**Hinweis:** Das Live-Abfragefenster nutzt einen *ungenauen* Alarm. Wenn die App beim Fensterbeginn im Hintergrund ist, darf Android den Vordergrunddienst für verbundene Geräte durch diesen Alarm nicht starten; stattdessen zeigt die App die Benachrichtigung **Live-Monitor bereit**. Zum Fortsetzen darauf tippen (oder die App öffnen). Android / OEM-Akkustrategien können die Benachrichtigung um einige Minuten verzögern. Eine Sonderberechtigung für genaue Alarme wird absichtlich nicht angefordert.

## Gerät hinzufügen

1. **Geräte** öffnen und **Suchen** tippen.
2. Bei Aufforderung Geräte in der Nähe und genauen Standort erlauben sowie Standort einschalten. Nach der Freigabe startet die Suche automatisch — ein zweites Tippen auf Suchen ist nur nötig, wenn die Liste leer ist oder die vorherige Suche beendet wurde.
3. Ungepaarte Geräte erscheinen während der Suche; bereits gebundene ebenfalls. Namen mit `SMA` stehen oben.
4. Auf ein Ergebnis tippen, um ein Profil anzulegen (Bluetooth-Name, MAC, Modell `Legacy SMA`, PIN-Startwert `0000`). Neue Profile erhalten auch einen Standard-EUR-Tarif.
5. Oder **+** tippen. Das füllt vom besten Gerät in der Nähe/gebunden, sonst ein leeres Profil. Die MAC kann später eingetragen werden.
6. Name, Anlagenbetreiber, PIN, MAC, Seriennummer und Modell bearbeiten. PIN: Ziffern, max. 12. Nur **Benutzer**-Login (oft `0000`). Eine Installateur-PIN funktioniert nicht.
7. **Speichern**, dann **Test**. Danach **Live** (Einzellese) und **Sync** (Archiv).

Ein Profil pro MAC. Doppelte MACs werden abgelehnt.

### Erweitert (pro Gerät)

Unter **Erweitert**: Zeitzone (z. B. `Europe/Berlin`) sowie CSV-Dezimalzeichen / Trennzeichen / Datumsformat für SBFspot-Importe.

## Live-Überwachung

**Live** auf Geräte ist ein **einzelner** Bluetooth-Abruf, nicht der Dauer-Monitor.

Auf **Start**:

- **Auswahl starten** — Vordergrunddienst für das auf dem Tagesertrags-Chip gewählte Gerät (oder **Auswahl zum Live hinzufügen**, wenn der Monitor schon läuft).
- **Alle starten** — Live für alle Geräte.
- **Live-Monitor stoppen** — Dienst stoppen und gespeicherte Geräteliste löschen. Nach Stopp startet der Monitor nach dem Neustart nicht neu.

Während Live läuft gibt es eine dauerhafte **Live-Monitor**-Benachrichtigung. Intervall: **Einstellungen → Live-Abfrageintervall** (15–3600 s, Standard 60). **Live-Abfragefenster** (Standard **06:00–22:00**) gilt in der Zeitzone jedes Wechselrichters; gleicher Beginn und Ende bedeuten 24 Stunden. Außerhalb stoppt der Dienst. Zum nächsten Beginn zeigt ein ungenauer Alarm bei Hintergrundbetrieb eine Fortsetzungsbenachrichtigung; darauf tippen, um Live zu starten. Bei sichtbarer App kann direkt fortgesetzt werden. Nach Neustart startet Live automatisch, sofern nicht **Stopp** gedrückt wurde und das Fenster offen ist; der Boot-Empfang ist dafür eine Android-Ausnahme.

**Uneingeschränkten Akku** erlauben, wenn das Telefon den Dienst nachts beendet.

## Archiv, Tarife, Ereignisse

- **Sync** holt etwa die letzten **30 Tage** Tagesarchiv und **12 Monate** Monatsarchiv. Ereignisse werden per Bluetooth **nicht** geladen.
- **Historie löschen** entfernt Spot-Samples, Aggregate und Events. Das Profil bleibt. Lokale Import-*Dateikopien* bleiben erhalten.
- **Löschen** entfernt Profil, Room-Historie, Events, PIN, geplante Importe und lokale Importkopien unter `imports/device-<id>/`. Bereits in Google Drive liegende Dateien bleiben, bis Sie sie dort löschen.
- **Einspeisetarife** — datierte €/kWh-Zeiträume. Erlöse sind **Schätzungen** (Ertrag × Tarif), keine Abrechnung.
- **Ereignisse** — bis zu 50 aus Import (Events-CSV oder SQLite `EventData`).

## Start, Statistik und Berichte

**Start** zeigt Leistung, Heute/Monat/Jahr und Erlös aus den letzten ~32 gespeicherten Produktionstagen. **Statistik**: Stunde/Tag/Monat/Jahr, Gerät oder Alle, Diagramm und Ereignisliste.

**Berichte:** Zeitraum-**CSV** ist vollständig. Zeitraum-**PDF** kürzt Serie und Ereignisse auf die ersten **40** Einträge.

## SBFspot-Daten importieren

| Quelle | Hinweise |
|---|---|
| **Datei importieren** | CSV, ZIP oder SQLite über den Systemwähler |
| **Von URL importieren** | HTTPS überall; HTTP nur private/LAN-Adresse |
| **Per FTP/SFTP** | Assistent: Protokoll → Gerät → verbinden → Datei/Ordner → bestätigen |

**Grenzen (ca.):** einzelne Datei 50 MiB; ZIP 5.000 Einträge / 200 MiB; Ordner 25.000 CSVs / 2 GiB; SQLite 250.000 Zeilen pro Tabelle.

FTP ist Klartext — SFTP bevorzugen. Geplante Importe laufen ungefähr alle N Stunden (WorkManager, Netz nötig, Doze kann verzögern).

Importe führen vorhandene Historie standardmäßig zusammen. Nur die Option
**Vorhandene Gerätedaten vor dem Import löschen** entfernt die bisherige
Historie. Auf einem Tag haben Bluetooth-Archiv und SQLite Vorrang vor
Monats-CSV und anderen CSV-Dateien. Unterstützt werden SBFspot-CSV
(Tages-/Monatsdaten und Events), ZIP-Archive und SQLite mit den Tabellen
`SpotData`, `DayData`, `MonthData` und `EventData`.

Es läuft immer nur ein Import. Ein Importjob kann erneut ausgeführt oder nach
einem erfolgreichen Lauf ungefähr alle N Stunden geplant werden; der Zeitpunkt
ist wegen Netz, Doze und Hersteller-Akkuregeln nicht exakt. Für geplante
FTP-/SFTP-Importe müssen die Zugangsdaten gespeichert sein.

## Widgets

| Widget | Anzeige |
|---|---|
| Solar Compact Stats | Leistung und Heute |
| Solar Device Summary | Leistung, Heute, Monat und Erlös |
| Solar Top Devices | Die drei Geräte mit der aktuellen Leistung |

Kompakt- und mittlere Widgets verwenden **Einstellungen → Widget-Gerät**
(oder das erste Gerät, wenn nichts gewählt ist). Die Aktualisierung erfolgt
ungefähr alle 30 Minuten. Tippen öffnet die App.

## Google-Drive-Backup

1. **Einstellungen → Cloud-Backup → Mit Google anmelden** (`drive.file`: Ordner und Dateien der App bzw. von Ihnen geöffnete/geteilte Dateien — kein Vollzugriff auf Drive).
2. Optional: Datenbank und/oder Original-Importdateien.
3. **Jetzt sichern** oder Auto-Backup nach Sync/Import (ca. 15 Min. Drosselung).
4. Ordner **Legacy Solar Monitor**. Ältere Backups im Ordner **SMA Solar Monitor**
   werden weiterhin gefunden und möglichst umbenannt. PINs und FTP/SFTP-Passwörter
   sind **nicht** im Backup.
5. **Wiederherstellen** stoppt zuerst Live-Monitor, Importe und geplante Jobs,
   ersetzt die lokale Room-Datenbank und startet die App neu. Eine Kopie der
   vorherigen Datenbank bleibt vorübergehend im Cache. Importkopien unter
   `imports/` werden weder wiederhergestellt noch gelöscht. PIN erneut eingeben.

**Abmelden** trennt das Konto in der App; Drive-Dateien bleiben, bis Sie sie in Drive löschen.

Bei der Wiederherstellung auf einem zweiten Telefon zuerst die Datenbank
wiederherstellen und danach jede Wechselrichter-PIN sowie FTP/SFTP-Passwörter
erneut eingeben. Diese Geheimnisse bleiben absichtlich verschlüsselt auf dem
Gerät und sind nicht Teil des Drive-Datenbank-Backups.

Android-Cloud-Auto-Backup ist aus (`allowBackup=false`). Explizite Data-Extraction-Regeln schließen App-Daten aus Cloud-Backup und — sofern der Hersteller sie beachtet — aus Geräte-zu-Gerät-Übertragung aus. Drive bleibt der unterstützte Backup-Weg.

## Sprache und Info

**Einstellungen → Sprache**: System, Deutsch oder English. Auf älteren Android-Versionen App nach Sprachwechsel neu starten.

**Einstellungen → Diagramm-Balkenfarbe**: Gold (Standard) oder Cyan für
Ertragsbalken in Start, Statistik und Homescreen-Widgets wählen.

**Wechselrichter-Warnungen**: Benachrichtigung bei neuen WARNING-Ereignissen der letzten 24 Stunden.

Die erste Prüfung nach dem Einschalten setzt nur einen Merker, damit alte
Ereignisse keine Benachrichtigungsflut auslösen. Für Warnungen muss die
Benachrichtigungsberechtigung erlaubt sein.

## Fehlersuche

| Symptom | Versuch |
|---|---|
| Suche leer / nur gebundene Geräte | Geräte in der Nähe + genauer Standort; GPS an; Bluetooth an; erneut Suchen |
| Test/Login fehlgeschlagen | Benutzer-PIN (oft `0000`); Installateur-PIN ungeeignet; Diagnose prüfen |
| Live setzt am Fensterbeginn nicht fort | **Live-Monitor bereit** antippen oder App öffnen; ungenaue Alarme dürfen den Vordergrunddienst im Hintergrund nicht starten. Uneingeschränkten Akku erlauben, um Verzögerungen zu verringern. |
| Drive-Anmeldung grau | Build mit `google.web.client.id` — [DEV-google-drive.md](DEV-google-drive.md) |
| Drive-Fehler 10 / `DEVELOPER_ERROR` | Android-OAuth-Client, Paketname oder SHA-1 des installierten Builds stimmt nicht |
| Zugriff blockiert / App nicht bestätigt | Google-Konto bei einem OAuth-Testlauf als Testnutzer eintragen |
| Diagramme leer | Archiv-Sync oder SBFspot-Import |
| Erlös `--` / 0 | Einspeisetarif am Gerät anlegen |
