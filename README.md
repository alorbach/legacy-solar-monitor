# Legacy Solar Monitor

<p align="center"><img src="docs/legacy-solar-monitor-icon.png" width="128" alt="Legacy Solar Monitor"></p>

Free Android app for **old Bluetooth solar inverters**: live power, archive sync, SBFspot-style imports, reports, widgets, and optional Google Drive backup.

This is an independent hobby project. It is **not affiliated with, endorsed by, or an official product of SMA Solar Technology AG**. SMA, Sunny Boy, Sunny Portal, and related names are trademarks of their owners and are used only to describe compatible hardware.

## Author

- Andre Lorbach
- alorbach@adiscon.com
- Source: [https://github.com/alorbach/legacy-solar-monitor](https://github.com/alorbach/legacy-solar-monitor)

License: [Apache License 2.0](LICENSE)

## What it talks to today

Classic Bluetooth SMA Sunny Boy–class inverters (SBFspot-compatible protocol). There is no vendor picker; every added device uses that stack. Same-generation units can usually be added in the UI with no code change — see [docs/DEV-add-device.md](docs/DEV-add-device.md).

Package name (do not change): `com.alorbach.solarmonitor`

## Docs

- [Add another old device](docs/DEV-add-device.md)
- [Google Drive backup setup](docs/DEV-google-drive.md) (publisher OAuth; users only sign in)

## Build

Android Studio / Gradle, JDK **21**. `minSdk` 28.

Optional Drive sign-in: set `google.web.client.id` in `local.properties` as described in the Drive doc. The file is not in Git.
