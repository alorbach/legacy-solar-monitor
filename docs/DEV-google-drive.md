# DEV: Google-Drive-Backup einrichten

Einmaliges Publisher-Setup. Danach brauchen Nutzer (und du auf dem Handy) nur **Mit Google anmelden**.  
OAuth-Clients kann die App nicht selbst anlegen — das macht JuiceSSH intern genauso, nur unsichtbar.

Paketname: `com.alorbach.solarmonitor`  
Drive-Ordner: `Legacy Solar Monitor` (ältere Backups lagen unter `SMA Solar Monitor`; die App findet und benennt den Ordner um)  
Scope: `https://www.googleapis.com/auth/drive.file` (nur Dateien, die die App selbst anlegt)

## 1. Google-Cloud-Projekt

1. [Google Cloud Console](https://console.cloud.google.com/) öffnen und mit dem Konto anmelden, das die App besitzen soll.
2. **Neues Projekt**, z. B. `legacy-solar-monitor`.
3. Oben das neue Projekt auswählen.

## 2. Drive API

1. **APIs & Services → Library**.
2. **Google Drive API** suchen und **Enable**.

## 3. OAuth-Zustimmungsbildschirm

1. **APIs & Services → OAuth consent screen**.
2. User type **External** (für ein privates Google-Konto).
3. App-Name z. B. `Legacy Solar Monitor`, deine E-Mail als Support.
4. Scopes: **Add or remove scopes** → `https://www.googleapis.com/auth/drive.file` hinzufügen.
5. **Test users**: dein Google-Konto eintragen, mit dem du dich in der App anmelden willst.  
   Ohne Testuser schlägt Login fehl, solange die App im Status **Testing** ist.
6. Speichern. Veröffentlichung ist erst nötig, wenn fremde Konten ohne Testuser-Liste zugreifen sollen.

## 4. Zwei OAuth-Clients

Unter **APIs & Services → Credentials → Create credentials → OAuth client ID**.

### 4a. Android-Client

Typ: **Android**

| Feld | Wert |
|---|---|
| Name | `Legacy Solar Monitor Android Debug` |
| Package name | `com.alorbach.solarmonitor` |
| SHA-1 | Debug-SHA-1 (siehe unten) |

Ohne passenden SHA-1 kommt nach dem Login oft `10:` / `DEVELOPER_ERROR`.

**Debug-SHA-1 (Windows):**

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

Aktueller Debug-SHA-1 auf diesem Rechner (neu erzeugen, wenn du die Debug-Keystore löschst):

```text
FF:DC:EC:61:44:7F:51:60:B3:5D:05:3C:71:B1:38:9D:92:2B:36:06
```

Später für Play-Store-Builds einen **zweiten** Android-Client mit dem Release-SHA-1 anlegen (Upload-Key oder Play App Signing, je nachdem was Play Console unter App-Integrität zeigt).

### 4b. Web-Client

Typ: **Web application**

| Feld | Wert |
|---|---|
| Name | `Legacy Solar Monitor Web` |
| Authorized redirect URIs | leer lassen |

Die **Client-ID** kopieren, Form:

```text
123456789-abc....apps.googleusercontent.com
```

Das ist **kein** Secret. Client-Secret des Web-Clients wird in dieser App **nicht** verwendet.  
Play Services braucht die Web-Client-ID für `requestOfflineAccess` (stilles Token für WorkManager-Auto-Backup).

## 5. ID in den Build legen

In `local.properties` (liegt nicht in Git):

```properties
sdk.dir=C\:\\Users\\al\\AppData\\Local\\Android\\Sdk
google.web.client.id=HIER_DIE_WEB_CLIENT_ID.apps.googleusercontent.com
```

Alternativ Umgebungsvariable `GOOGLE_WEB_CLIENT_ID`.

Danach **Rebuild** der App. Die ID landet zur Compile-Zeit in `BuildConfig.GOOGLE_WEB_CLIENT_ID`. Nur Install der alten APK reicht nicht.

Wenn die rote Meldung *google.web.client.id in local.properties setzen* bleibt, ist der laufende Build noch ohne ID.

Gradle-JDK: **21** (`C:\Program Files\Android\openjdk\jdk-21.0.8`), nicht Android-Studio-JBR 25.

## 6. Am Gerät prüfen

1. App neu installieren/starten.
2. **Einstellungen → Cloud-Backup**: Button **Mit Google anmelden** ist aktiv, keine rote Config-Meldung.
3. Google-Konto wählen, Drive-Zugriff erlauben.
4. Backup auslösen.
5. In [drive.google.com](https://drive.google.com) Ordner **Legacy Solar Monitor** mit `solar-monitor.db` (und optional Import-Kopien) prüfen.

Fehlerbilder:

| Symptom | Typische Ursache |
|---|---|
| Grauer Button, rote Config-Meldung | `google.web.client.id` fehlt oder kein Rebuild |
| Login-Dialog, dann Fehler 10 / DEVELOPER_ERROR | Android-Client: Paket oder SHA-1 falsch |
| Access blocked / App not verified | Konto nicht unter Test users |
| Play Services fehlen | Gerät/Image ohne Google Play |

## 7. Was du nicht brauchst

- Kein Service Account, kein JSON-Key in der App.
- Kein Signed-URL-/GCS-Bucket.
- Kein `google-services.json` (Firebase ist nicht nötig).
- Nutzer legen **kein** eigenes Cloud-Projekt an.
