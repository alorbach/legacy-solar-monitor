# Supported devices

## Short answer

Legacy Solar Monitor currently supports **SMA devices that speak the classic
SMA Bluetooth protocol used by SBFspot**, provided the device completes the
app's connection test. The app can then read live values and synchronize day
and month archives.

This does **not** mean that every device ever recognized by SBFspot is
supported by this Android app. SBFspot was a desktop tool with a broader
transport and device scope, and its historical type catalog contains devices
that use Ethernet/Speedwire, devices other than inverters, and models that
were not tested with this app.

## Device families

The following families occur in the historical SBFspot type catalog. The
names are included as a compatibility reference, not as a promise that every
variant works with the Android app.

### SMA solar inverters

Examples from the catalog include:

- **Sunny Boy / SB**: SB 700, SB 1100, SB 1200, SB 1700, SB 2500,
  SB 3000, SB 3300, SB 3800, SB 4000US, SB 5000TL, SB 6000US,
  SB 7000US, SB 8000TLUS, SB 9000TLUS, SB 10000TLUS, SB 1300TL-10,
  SB 2000HF-30, SB 2500HF-30, SB 3000HF-30, SB 3000TL-21,
  SB 4000TL-21, SB 5000TL-21, SB 6000TL-21, SB 3600TL-20,
  SB 5000SE-10, and regional US/JP variants.
- **SWR**: SWR 700, SWR 850, SWR 1100, SWR 1500, SWR 1700E,
  SWR 2000, SWR 2500, and SWR 3000.
- **Sunny Mini Central / SMC**: SMC 4600A, SMC 5000, SMC 6000,
  SMC 6500A, SMC 7000A, SMC 7000HV, SMC 7000TL, SMC 8000TL,
  SMC 9000TL, SMC 10000TL, SMC 11000TL, and the RP/HV variants.
- **Windy Boy / WB**: WB 2000HF-30, WB 2500HF-30, WB 3000HF-30,
  WB 3600TL-20, WB 5000TL-20, and corresponding US/32/21/22 variants.
- **Sunny Tripower / STP**: STP 5000TL-20, STP 6000TL-20,
  STP 7000TL-20, STP 8000TL-20, STP 9000TL-20, STP 10000TL-10,
  STP 12000TL-10, STP 15000TL-10, STP 17000TL-10,
  STP 20000TL-10, and regional variants.
- **Sunny Central / SC**: SC 250, SC 500CP, SC 630CP, SC 720CP,
  SC 760CP, SC 800CP, SC 850CP, SC 900CP, SC 910CP, and selected
  HE/MV/US/JP variants.
- **Other catalogued solar products**: Convert 2700, SB 240, Multigate,
  and related historical SMA PV products.

### SMA battery and backup devices

SBFspot also contains type labels for **Sunny Island**, including SI 2012,
SI 2224, SI 3324, SI 3.0M, SI 4.0M, SI 4.4M, SI 4248, SI 4248U, SI 4500,
SI 4548U, SI 5.4M, SI 5048, SI 5048U, SI 6048U, SI 6.0H, and SI 8.0H.
It also contains Sunny Backup labels such as SBU 2200 and SBU 5000.

These labels show that the SBFspot reference understood battery-class
devices. They are **not a guarantee of current app support**: this app's
live UI and repository are presently designed around the legacy Bluetooth
solar-inverter data path. Battery-specific behavior must be verified on the
physical device.

### Non-inverter and plant equipment in the SBFspot catalog

The catalog also contains equipment such as Sunny Boy Control, Sunny Boy
Control Plus, Sunny Boy Control Light, Sunny Beam, Sunny Home Manager,
SensorBox, SMA Meteo Station, SMA CT Meter, Cluster Controller, WebBox,
Power Reducer Box, and related communication, sensor, and meter products.
These are not standalone live-inverter targets for this app.

## What “supported” means in this app

For live Bluetooth support, a device must:

1. Be discoverable or manually configurable as a classic Bluetooth SMA
   device.
2. Complete **Test** with the user-level inverter PIN.
3. Return live spot values.
4. Complete day and month archive synchronization when archive data is
   available.

The exact model name is stored as profile information; it is not a protocol
selector. Same-generation devices using the same legacy Bluetooth exchange
can often be added without an app code change. See
[DEV-add-device.md](DEV-add-device.md) for the test procedure and reporting
details.

SBFspot file import is separate from live device support. The app can import
SBFspot CSV, ZIP, and SQLite data even when the source device cannot be
connected over Bluetooth.

## Not supported for live connection

The current Android live stack does not support:

- Bluetooth Low Energy inverter protocols.
- SMA Speedwire/Ethernet as a live transport.
- Unrelated vendor protocols.
- A model that is only present in the SBFspot type-label catalog but fails
  the app's Bluetooth Test.

SBFspot itself supported both classic Bluetooth and Ethernet connection modes
in the legacy reference code. That broader SBFspot capability must not be
read as a capability of this Android app.

## Reporting a device

If a device appears compatible but fails, record the exact typeplate model,
Android version, whether the device was bonded, and which of Test, Live, day
archive, and month archive failed. Do not share the inverter PIN or the
Bluetooth MAC address. Redacted diagnostics can be reported through the
project repository.

The project is independent and is not affiliated with SMA Solar Technology
AG. SMA, Sunny Boy, Sunny Island, Sunny Central, and related names are
trademarks of their respective owners and are used only to describe
compatibility.

## Source of the catalog

The historical family and model names above are based on the English
[`TagListEN-US.txt`](https://github.com/SBFspot/SBFspot/blob/master/SBFspot/TagListEN-US.txt)
type-label data in the original
[SBFspot project](https://github.com/SBFspot/SBFspot). SBFspot is not vendored
in this repository and is not an Android build dependency.
