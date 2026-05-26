# Windows client

Companion to the Android app. Same BLE protocol (see `../docs/BLE_PROTOCOL.md`),
re-implemented for a Windows host so the laptop / desktop can talk to
the heater without a phone in the loop.

## Status

Empty placeholder. Stack and scope to be decided.

## Likely shape

Two candidate stacks, both work fine against the heater:

- **Python (`bleak`)** — fastest iteration, ~1 file, async API. Good for
  protocol exploration, sniffing, and one-off scripts.
- **C# / .NET 8 with `Windows.Devices.Bluetooth.*`** — first-class
  Windows BLE API, native UI (WinUI 3 / WPF / Avalonia). The right home
  for an always-on Windows controller.

The protocol layer in `../android/app/.../ble/FrameCodec.kt` has zero
Android dependencies; the CRC + builders + parsers translate near-line-
for-line to either target.

## What the host needs

- Bluetooth 4.0+ radio with the Microsoft BLE stack active (BthLEEnum
  service running). On Win11 24H2 the driver file is
  `Microsoft.Bluetooth.Legacy.LEEnumerator.sys` (renamed from
  `bthleenum.sys` in earlier builds).
- Bluetooth turned on (obvious in hindsight).
- The heater within ~10 m, advertising — happens whenever it isn't
  already connected to another central.
