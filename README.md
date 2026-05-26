# TSGB Heater

Open-source replacement clients for the TSGB (HeatGenie-derived) diesel
heater controller. Same on-the-wire BLE protocol as the vendor app,
cleaner implementation, no account / cloud requirement.

## Layout

```
android/   Android app (Kotlin + Jetpack Compose). Targets API 31+.
windows/   Windows app (in progress). See windows/README.md.
docs/      Protocol documentation — shared by every client.
OLDSRC/    Decompiled vendor app, used as protocol-of-record reference.
           Git-ignored; not pushed.
```

`docs/BLE_PROTOCOL.md` is the source of truth for frame formats, area
tags, opcodes, sensor encodings and known firmware quirks. Both clients
should follow it; if the wire ever disagrees with the doc, the doc gets
patched first.

## Android

Open the `android/` folder in Android Studio (Iguana or newer). The
existing `.idea/` settings travel with the project. Connected-device
debugging uses ADB over USB or wireless; the `HeaterService` foreground
service owns the BLE link and survives backgrounding.

Run from the project root:

```
cd android
./gradlew :app:installDebug
```

## Windows

Hardware is fine on any Win10/11 with a BT 4.0+ radio — the vendor
heater advertises as a standard BLE peripheral. See `windows/README.md`
for the current state of the Windows client.
