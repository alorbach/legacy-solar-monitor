# DEV: Add another old device

This app is a **free** monitor for old Bluetooth inverters. There is **no protocol or vendor picker**: every device you add uses the SMA legacy Bluetooth stack (SBFspot-compatible).

SMA / Sunny Boy names below describe hardware and protocol only. Do not put them in the app title. See the [README](../README.md) trademark note.

Related:

- [USER-GUIDE.md](USER-GUIDE.md) — scan, PIN, Test, Live, Sync
- [ARCHITECTURE.md](ARCHITECTURE.md) — `AppContainer`, Room, live service
- [DEV-google-drive.md](DEV-google-drive.md) — publisher OAuth for Drive backup, not device onboarding

## What “like mine” means

Reference hardware: **SMA Sunny Boy with classic Bluetooth** (no exact typeplate in this repo). Same-generation units that speak this protocol can usually be added in the UI with **no code change** (Path A).

## Architecture

```text
DevicesTab / MainActivity
        │
        ▼
LiveMonitoringRepository
        │
        ▼
SmaLegacyBluetoothGatewayImpl  (SmaBluetoothSession, RfcommStrategy)
        │
        ▼
DeviceProfileEntity  (Room)
```

Imports go through `LegacySbfspotImporters` → `SbfspotCsvParser` / `LegacySqliteImporter`, not through Bluetooth.

| Piece | Where | Notes |
|---|---|---|
| Profile | `app/.../data/model/Models.kt` (`DeviceProfileEntity`) | `model` is free text (create default `Legacy SMA`). Do **not** treat `model` as a protocol key. |
| Transport | `DeviceTransport.BLUETOOTH_LEGACY` | `SPEEDWIRE_FUTURE` is a stub and unused. |
| Compatibility | `LegacyBluetoothCompatibilityMode.SINGLE_INVERTER_LEGACY` | Stored on the profile; no UI branching today. |
| Gateway | `app/.../device/SmaLegacyBluetoothGateway.kt` | RFCOMM, SMA NetID, PIN login, live spot, day/month archive. |
| DI | `app/.../data/AppContainer.kt` | Always constructs `SmaLegacyBluetoothGatewayImpl`. |
| PIN | `passwordRef` via `CredentialStore` | Default seed `0000`. |
| MAC | unique in `SolarRepository.saveDeviceForMac` | One profile per MAC. |
| Events | `app/.../domain/EventCatalog.kt` | Small hardcoded severity/labels. |

### Hardcoded vs per-device

Hardcoded:

- SMA RFCOMM / L1 / L2 packets
- SBFspot CSV and SQLite parsers
- Event catalog
- Scan ranking: Bluetooth name containing `SMA` first (`bluetoothDiscoveryUiComparator`)

Per device:

- MAC, PIN, timezone
- CSV decimal / delimiter / date format
- Remembered `lastSuccessfulSocketStrategy`
- `lastDiagnostics` and connection timestamps

## Path A — same protocol (no code)

1. Grant **Nearby devices** and **precise location**. Unpaired classic discovery needs both; location (GPS) must be on.
2. Devices tab → Scan, or the FAB in `MainActivity` (`preferredBluetoothSeed()` / bonded list). If nothing is in range, a profile with a null MAC can still be created and the MAC typed later.
3. Tap a result → `createDeviceFromBluetooth()`:
   - name = Bluetooth name, or `SMA Device N`
   - model = `Legacy SMA`
   - transport = `BLUETOOTH_LEGACY`
   - PIN seed = `0000` (user-level login only; installer PIN / group `0x0A` is not implemented)
   - seeds a default EUR tariff on a **new** profile
4. Edit PIN / MAC / serial / model. **Test connection**, then **Live** and **Sync** archive.
5. Optional: import SBFspot CSV, ZIP, or SQLite onto that device.
6. If it works, add a row to [Tested devices](#tested-devices).

Legacy inverters often stay **unbonded**. The gateway prefers insecure RFCOMM (`hidden_insecure_channel_1`, `insecure_uuid`); secure strategies run only when the phone already bonded the device.

## Path B — similar SMA Bluetooth that fails

Tune the existing stack. Prefer a new `LegacyBluetoothCompatibilityMode` over breaking the working single-inverter path.

Touch:

- `SmaLegacyBluetoothGateway.kt` — RFCOMM strategies, login/PIN encoding, NetID handshake, archive packets
- `Models.kt` — new compatibility enum value if behavior must fork
- `DevicesTab.kt` — defaults only if the add flow needs extra fields
- `LiveMonitoringRepository.kt` — persists strategy / status after ops

Capture `SmaLegacyBt` logcat and the profile’s `lastDiagnostics`. Tests: save/MAC (`MigrationAndSaveDeviceTest`), connection/parser tests under `app/src/test`.

## Path C — different vendor or Speedwire

Out of scope for a “like mine” Bluetooth box. Expect a **parallel** stack, not a string on `model`:

- New `app/.../device/<Vendor>Gateway.kt` — do not bolt onto `SmaBluetoothSession`
- New `DeviceTransport` in `Models.kt`
- UI picker in `DevicesTab` / `MainActivity`
- Dispatch in `AppContainer` and `LiveMonitoringRepository`
- Importer if the files are not SBFspot (`ParsedImportBundle` in `ImportModels.kt`)
- `EventCatalog` entries if event codes differ

## Import formats (SBFspot family)

Wired by `LegacySbfspotImporters` → `ImportManager`. File transport (URI, URL, FTP, SFTP) is independent of the inverter protocol.

- **CSV** (`SbfspotCsvParser`): day (datetime header), month (date-only header), events (`DeviceType;DeviceLocation;SusyId;SerNo…`)
- **ZIP**: flatten entries and parse each
- **SQLite** (`LegacySqliteImporter`): tables `SpotData`, `DayData`, `MonthData`, `EventData`

Per-device CSV locale (decimal, delimiter, date format, timezone) is applied; the schema is not vendor-generic.

## Permissions and pairing

- Android 12+: Nearby devices (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`)
- Precise location for unpaired classic discovery
- Bluetooth adapter enabled
- Many of these inverters never complete Android bonding; that is expected

## Tested devices

| Model | Android | Notes |
|---|---|---|
| SMA Sunny Boy (classic Bluetooth) | — | Reference family; no typeplate recorded |

Add a row when another unit works (model, Android version, PIN used, RFCOMM strategy from diagnostics).
