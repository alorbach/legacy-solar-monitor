# Agent notes — Legacy Solar Monitor

Independent Android hobby app for **classic Bluetooth SMA inverters** (SBFspot-compatible). It is **not affiliated with SMA**. Do **not** put SMA in the app title.

## Immutable

- Package / `applicationId`: `com.alorbach.solarmonitor` — **do not change** (Play listing and OAuth SHA-1 clients).

## Stack

- Single Gradle module `:app` (`legacy-solar-monitor`)
- Jetpack Compose Material3, system light/dark
- Manual DI in `AppContainer` (no Hilt/Koin, no ViewModel layer)
- Room `solar-monitor.db` **v5** (`exportSchema = true`)
- Gradle **9.7**, JDK **21**, `jvmTarget` 17, `minSdk` 28, `compileSdk`/`targetSdk` 35

## Commands

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

Drive OAuth: set `google.web.client.id` in `local.properties` or `GOOGLE_WEB_CLIENT_ID` in the environment. **Never commit** `local.properties` or secrets.

## Data

- Energy is stored as **Wh**; UI shows kWh (`YieldFormatting`).
- Power is **W**.
- “Today” is always the **device timezone** (`DeviceProfileEntity.timezone`).
- Hourly series: `hour_aggregates` via `SolarRepository.getHourlySeries(deviceIds, date)`.
- Daily chart: `getDailyChart(deviceId)` (~30 days).

## UI

Tabs: `DashboardTab` (Start), `StatisticsScreen`, `DevicesTab`, `ImportTab`, `SettingsTab`.

Strings: always update **EN** (`values/`) and **DE** (`values-de/`) together.

Compact Start/Statistik actions use `CompactButtonHeight` / `CompactButtonContentPadding` in `ui/CommonUi.kt`.

## Widgets

Glance widgets in `widget/SolarWidgets.kt`: compact, medium, top-devices.

- Compact/medium: widget device (Settings) first.
- Hourly backdrop: `getHourlySeries` for today; muted gold/olive bars so text stays readable in light and dark.
- Refresh after live/import via `SolarWidgets.refreshAll`.

## Versioning

`versionCode` in `app/build.gradle.kts` is the monotonic Play/install integer (currently **1010+**).

- Every **new** git commit that ships app changes: increment `versionCode` by 1.
- Do **not** increment again when amending the same unpushed commit.
- Docs-only commits may skip the bump.
- Leave `versionName` (`1.0.0`) unless this is a named release.

## Do not

- Change the applicationId/package.
- Treat `_legacy/sbfspot.3` as an Android dependency (C++ reference only).
- Commit `local.properties`, credentials, or `google.web.client.id`.
- Add a vendor/protocol picker unless explicitly asked; every device uses the SMA legacy Bluetooth stack.

## Docs

- [README.md](README.md)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/USER-GUIDE.md](docs/USER-GUIDE.md)
- [docs/DEV-add-device.md](docs/DEV-add-device.md)
- [docs/DEV-google-drive.md](docs/DEV-google-drive.md)
- [docs/DEV-github-release.md](docs/DEV-github-release.md)
