# DEV: Set up Google Drive backup

One-time **publisher** setup. After this, users (and you on the phone) only tap **Sign in with Google**.

The app cannot create OAuth clients by itself.

In-app backup, auto backup, and restore: [USER-GUIDE.md](USER-GUIDE.md). Runtime classes: [ARCHITECTURE.md](ARCHITECTURE.md).

Package name: `com.alorbach.solarmonitor`  
Drive folder: `Legacy Solar Monitor` (older backups lived under `SMA Solar Monitor`; the app finds that folder and renames it when possible)  
Scope: `https://www.googleapis.com/auth/drive.file` (only files this app creates)

## 1. Google Cloud project

1. Open the [Google Cloud Console](https://console.cloud.google.com/) with the account that should own the app.
2. **New project**, for example `legacy-solar-monitor`.
3. Select the new project at the top.

## 2. Drive API

1. **APIs & Services → Library**.
2. Search **Google Drive API** and **Enable**.

## 3. OAuth consent screen

1. **APIs & Services → OAuth consent screen**.
2. User type **External** (for a private Google account).
3. App name e.g. `Legacy Solar Monitor`, your email as support.
4. Scopes: **Add or remove scopes** → add `https://www.googleapis.com/auth/drive.file`.
5. **Test users**: add the Google account you will use in the app.  
   Without a test user, login fails while the app status is **Testing**.
6. Save. Publishing is only needed when strangers must sign in without the test-user list.

## 4. Two OAuth clients

**APIs & Services → Credentials → Create credentials → OAuth client ID**.

### 4a. Android client

Type: **Android**

| Field | Value |
|---|---|
| Name | `Legacy Solar Monitor Android Debug` |
| Package name | `com.alorbach.solarmonitor` |
| SHA-1 | Debug SHA-1 (see below) |

A wrong SHA-1 often yields error `10:` / `DEVELOPER_ERROR` after the login dialog.

**Debug SHA-1 (Windows):**

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

Current debug SHA-1 on this machine (regenerate if you delete the debug keystore):

```text
FF:DC:EC:61:44:7F:51:60:B3:5D:05:3C:71:B1:38:9D:92:2B:36:06
```

Add **one Android client per signing certificate** (same package `com.alorbach.solarmonitor`):

| Client name | SHA-1 source |
|---|---|
| `Legacy Solar Monitor Android Debug` | Debug keystore (above) |
| `Legacy Solar Monitor Android Upload` | Upload key (`keytool -list -v -keystore upload.jks`) — GitHub Release APK/AAB |
| `Legacy Solar Monitor Android Play` | Play Console → **App integrity** → App signing key certificate (after first AAB upload) |

CI injects the Web client ID via GitHub secret `GOOGLE_WEB_CLIENT_ID`. Keystore secrets and tag-push steps: [DEV-github-release.md](DEV-github-release.md).

### 4b. Web client

Type: **Web application**

| Field | Value |
|---|---|
| Name | `Legacy Solar Monitor Web` |
| Authorized redirect URIs | leave empty |

Copy the **Client ID**, form:

```text
123456789-abc....apps.googleusercontent.com
```

This is **not** a secret. The Web client secret is **not** used in this app.  
Play Services needs the Web client ID for `requestOfflineAccess` (silent token for WorkManager auto-backup).

## 5. Put the ID in the build

In `local.properties` (not in Git):

```properties
sdk.dir=C\:\\Users\\al\\AppData\\Local\\Android\\Sdk
google.web.client.id=PASTE_THE_WEB_CLIENT_ID.apps.googleusercontent.com
```

Or environment variable `GOOGLE_WEB_CLIENT_ID`.

### Local installs must use the upload key

Studio **debug** installs otherwise use the debug keystore → Drive OAuth fails against the Upload Android client. Point Gradle at the same `upload.jks` CI uses (also via env vars in [DEV-github-release.md](DEV-github-release.md)):

```properties
release.store.file=C\:\\path\\to\\upload.jks
release.store.password=...
release.key.alias=upload
release.key.password=...
```

With those set, both `debug` and `release` build types sign with the upload key (same SHA-1 as the GitHub APK).

Then **Rebuild** the app. The ID is compiled into `BuildConfig.GOOGLE_WEB_CLIENT_ID`. Reinstalling an old APK is not enough.

If the red message *Set google.web.client.id in local.properties* remains, the running build still has no ID.

Gradle JDK: **21** (`C:\Program Files\Android\openjdk\jdk-21.0.8`), not Android Studio JBR 25.

## 6. Check on the device

1. Reinstall / start the app.
2. **Settings → Cloud backup**: **Sign in with Google** is enabled, no red config message.
3. Pick a Google account and allow Drive access.
4. Run a backup.
5. On [drive.google.com](https://drive.google.com) confirm folder **Legacy Solar Monitor** with `solar-monitor.db` (and optional import copies).

Failure modes:

| Symptom | Typical cause |
|---|---|
| Grey button, red config message | `google.web.client.id` missing or no rebuild |
| Login dialog, then error 10 / `DEVELOPER_ERROR` | Android client: package or SHA-1 wrong |
| Access blocked / App not verified | Account not in Test users |
| Play Services missing | Device/image without Google Play |

## 7. What you do not need

- No service account, no JSON key in the app.
- No signed-URL / GCS bucket.
- No `google-services.json` (Firebase is not required).
- Users do **not** create their own Cloud project.
