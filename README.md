# Ember

Independent, clean-room replacement clients for **HeatGenie / HCalory**-class
Bluetooth diesel air heaters. They speak the same on-the-wire BLE protocol as
the vendor app, with a cleaner implementation and **no account, cloud, or
internet dependency** — everything runs locally over Bluetooth LE.

> **Status:** active development · Android `0.2.99-RC` · Windows (WPF) in active build-out.
> Not affiliated with, or endorsed by, HeatGenie, HCalory, or any heater vendor.
> Reverse-engineered for interoperability and personal use.

## What it does

- **Direct BLE control** — scan, bind, start / stop / purge-vent, set target
  temperature or power level, and read live telemetry (temperature, supply
  voltage, running state, fault codes).
- **Two heater protocols, one abstraction** — `HCalory` and `HeatGenie` wire
  formats (both Tuya-BLE-framed) sit behind a common protocol interface; the
  right codec is selected per device.
- **Scheduling** — time-of-day on/off programs per heater.
- **Auto start/stop** — rule-based automation (hold a temperature, or run
  between set times).
- **Groups** — drive several heaters together as one logical zone.
- **Fuel-tank tracking** — estimates fuel remaining from run time and power
  level, with **low-fuel auto-stop** so the pump never runs dry.
- **Altitude compensation** — tune the fuel/air mix for local elevation
  (diesel heaters run rich at altitude).
- **Remote access** — the desktop client hosts a small authenticated API
  server (HMAC-signed requests, self-signed TLS, optional UPnP port-forward,
  QR-code pairing) that the Android app pairs to, so you can reach the heater
  from outside Bluetooth range.
- **Diagnostics** — raw frame log, flag / switch inspector, and a manual
  command box for protocol work.

## Repository layout

```
android/   Android client — Kotlin + Jetpack Compose, min SDK 31 (Android 12).
           A foreground service owns the BLE link and survives backgrounding.
           Package com.emberheat.
windows/   Windows client — C# / .NET 10, WPF (MVVM). Direct BLE via
           Windows.Devices.Bluetooth, plus the optional remote API server
           (ASP.NET Core Kestrel hosted inside the desktop app).
docs/      Protocol documentation — the shared source of truth.
OLDSRC/    Decompiled vendor app + reference APKs. Third-party material;
           git-ignored, never pushed.
```

`docs/BLE_PROTOCOL.md` is the canonical reference for frame formats, service /
characteristic discovery, opcodes, sensor encodings, and firmware quirks. Both
clients follow it; if the wire ever disagrees with the doc, the doc is
corrected first.

## Architecture

Both clients share the same layering:

- **Transport** — Tuya-style BLE framing (`TuyaBleFrame`) over the heater's
  `0x181A` GATT service; characteristics are discovered by property bits, not
  hardcoded UUIDs.
- **Protocol** — `HCalory` and `HeatGenie` implementations behind a single
  interface, exposing common telemetry and per-model capabilities.
- **Control logic** — scheduling, auto start/stop rules, group fan-out, and
  fuel tracking, all driven off the live telemetry stream.
- **Remote** — the Windows app can expose its bound heater(s) over an
  HMAC-authenticated HTTPS API; the Android app pairs to it (QR or manual) and
  drives the heater remotely when out of BLE range.

## Building

### Android

Requires JDK 17 and the Android SDK (or Android Studio Iguana+). From `android/`:

```
./gradlew :app:installDebug      # build + install the debug APK to a connected device
```

Release builds (`./gradlew :app:assembleRelease`) are produced unsigned.

### Windows

Requires the **.NET 10 SDK** (x64) on Windows 10/11. From `windows/`:

```
dotnet build Ember.sln
dotnet run --project src/Ember      # launch the WPF app
```

`src/HcaloryTest` is a small console harness for protocol bring-up and sniffing.

## Hardware requirements

- A HeatGenie / HCalory-class BLE diesel heater within ~10 m and advertising
  (it advertises whenever it isn't already connected to another central).
- A Bluetooth 4.0+ radio. On Windows the Microsoft BLE stack must be active
  (the `BthLEEnum` / `Microsoft.Bluetooth.Legacy.LEEnumerator` service running).

## Secrets

Nothing sensitive is committed. `local.properties`, `google-services.json`,
TLS material (`*.pfx` / `*.p12`), and paired-client state are all git-ignored.

## Safety

Diesel air heaters burn fuel and produce exhaust, including carbon monoxide.
This is unofficial software controlling combustion hardware: always use a
working CO alarm, never run an unvented heater in an occupied enclosed space,
and treat remote or automated start-up with appropriate caution.

## License

Licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE)
for the full text.
