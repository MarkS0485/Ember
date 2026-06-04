# Windows client

Companion to the Android app — the same BLE protocol (see
`../docs/BLE_PROTOCOL.md`), re-implemented for a Windows host so a laptop or
desktop can talk to the heater without a phone in the loop.

## Stack

- **C# / .NET 10**, WPF (MVVM via CommunityToolkit.Mvvm), x64.
- BLE through the native `Windows.Devices.Bluetooth.*` stack — no contracts
  NuGet needed (targets the Windows 11 SDK).
- An optional **remote API server** (ASP.NET Core Kestrel hosted in-process):
  HMAC-authenticated requests, a self-signed certificate, QR-code pairing
  (QRCoder), and optional UPnP port-forwarding (Mono.Nat). The Android app
  pairs to this to control the heater from outside Bluetooth range.

## Build & run

Requires the .NET 10 SDK. From this folder:

```
dotnet build TsgbHeater.sln
dotnet run --project src/TsgbHeater      # launch the WPF app
```

- `src/TsgbHeater`  — the WPF application (scan, bind, control, schedule,
  auto start/stop, groups, fuel tracking, diagnostics, API server).
- `src/HcaloryTest` — a console harness for protocol bring-up and sniffing.

## What the host needs

- A Bluetooth 4.0+ radio with the Microsoft BLE stack active (the
  `BthLEEnum` service — file `Microsoft.Bluetooth.Legacy.LEEnumerator.sys` on
  Win11 24H2, renamed from `bthleenum.sys` in earlier builds).
- Bluetooth turned on.
- The heater within ~10 m and advertising (happens whenever it isn't already
  connected to another central).
