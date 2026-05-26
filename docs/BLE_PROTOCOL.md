# HeatGenie BLE protocol

Reverse engineered from `HeatGenie/assets/apps/__UNI__C97AE27/www/app-service.js`
(uni-app v3, package `uni.UNIC97AE27`). Line numbers below refer to a copy
that was reformatted by inserting newlines after `;`/`{`/`}` (the original is
~30 minified mega-lines); the embedded `at <path>:<line>` strings inside
`console.log` calls give the original-source reference.

## Discovery

Two near-identical scan/connect code paths exist: `pages/scan/scan.vue`
(beautified lines 3075-3253) and `common/bleDevice.js` (lines 3756-3975, used
on auto-relink).

- **Service UUID filter:** the app keeps only services whose UUID string
  contains `181a` (case-insensitive). Line 3202 / 3899:

  ```js
  -1 == t.services[a].uuid.indexOf("181a") &&
  -1 == t.services[a].uuid.indexOf("181A") ||
    this.getBLEDeviceCharacteristics(e, t.services[a].uuid)
  ```

  The heater advertises a primary service with 16-bit UUID `0x181A`
  (officially "Environmental Sensing", repurposed).

- **Characteristic discovery:** characteristic UUIDs are **not** hardcoded;
  the app walks the characteristic list and picks by property bits (lines
  3214-3229, 3915-3931):
  - Char with `properties.notify` or `properties.indicate` → telemetry
    subscribe channel.
  - Char with `properties.write` → command write channel
    (`Qe.write3p.{deviceId,serviceId,characteristicId}`, referenced at
    line 1417).

- **MTU:** `uni.setBLEMTU(deviceId, Qe.bleMTU)` 400 ms after connect (line
  3176-3180). The constant is set elsewhere; not pinned in this dump.

- **Scan name filter:** in `onBluetoothDeviceFound` (lines 3103-3142,
  mirrored 3812-3860) an advert is accepted if **any** of:
  1. `device.name === "boygu"` (legacy firmware advertising the literal
     name).
  2. `device.name` matches a colon-hex tuple `XX:XX:XX:XX:XX` where
     `parseInt(parts[0],16) == 0xC0 + customerID` **and**
     `parseInt(parts[4],16) == 0xFF - vendorsID`. Defaults are
     `customerID = vendorsID = 1` (line 716), so the default match is
     first byte `0xC1`, fifth byte `0xFE`.
  3. Name equals a previously bonded `onceConneted.deviceId` (relink).
  4. The name equals a QR-scanned device ID (line 3108).

- **Bonding handshake (first connect only):** after the notify subscribe
  succeeds, the controller sends a `0xAA …` frame with opcode `0x20`
  ("request feature code", line 3277). The app replies with a 12-byte frame
  (opcode `0x91`, reused as "send bandMac"):

  ```
  AA 00 91 <mac5> <mac4> <mac3> <mac2> <mac1> <mac0> <magic> <crcH> <crcL>
  ```

  The 6 MAC bytes come from `uni.getStorageSync("deviceId")` bytes 0-5
  reversed; `magic` is byte 6. If storage is empty, all zeros (line
  3290-3295). Controller answers opcode `0x21` (allow) or `0x22` (deny).
  Once bonded, the version-check ACK triggers a one-shot reply
  `AA 00 92 00 … <crc>` (opcode `0x92 = BLE_CMD_DN_BLINK`, "sendBandMacCmd",
  line 3344-3345).

## Frame format

Two distinct frames are multiplexed on the same notify channel. The header
byte selects the parser.

### Heater frame (normal operation)

8 bytes for commands; `0xAA` sync header. Used both directions.

```
offset   0       1       2       3       4       5       6       7
       +-------+-------+-------+-------+-------+-------+-------+-------+
       | 0xAA  |  len  | opcode|  d0   |  d1   |  d2   | crcH  | crcL  |
       +-------+-------+-------+-------+-------+-------+-------+-------+
                 ^                                       ^
                 |                                       +-- CRC-16/XMODEM
                 |                                           over bytes 0..5
                 +-- 0 for short cmds; equals the count of trailing
                     32-bit data words for telemetry response frames
                     (see "telemetry frame variant" below).
```

- Builder `heaterSendCmd(opcode, data[3])` at `common/bleSet.js:3606`
  (line 2388-2390): seeds `[0xAA,0,0,0,0,0,0,0]`, fills `[2]=opcode`,
  `[3..5]=data[0..2]`, CRC into `[6..7]`. Frame writer `send8BytesCmd`
  (line 2381-2387) wraps it in an 8-byte `ArrayBuffer` and calls `bleWrite`
  → `uni.writeBLECharacteristicValue` (line 1413-1417).

- **CRC:** `getCrc16Val(buf, len)` (function `Ee`, line 1423-1427) is
  standard **CRC-16/CCITT (XMODEM)**: poly `0x1021`, init `0x0000`, no
  reflection, no final XOR. Lookup table:
  `[0, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
    0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF]`. CRC
  is computed over the first `len` bytes; high byte placed at index `len`,
  low at `len+1`. For the 8-byte heater frame `len = 6` (covers bytes 0-5).

- **RX verification:** `getCrc16Val(buf, total_len) == 0` for a valid frame
  (line 3276).

### Heater frame: telemetry response variant

When the controller replies to `DB0_DN_GET_REG_VAL` / `DB0_DN_AUTO_UPDATA`,
byte 1 (`len` slot) is **non-zero** and the payload is appended after the
8-byte header:

```
offset 0      1      2      3      4      5      6      7      8 .. 8+4*(len+1)-1   ...   ...
     +-----+------+------+------+------+------+------+------+----------------------+------+------+
     |0xAA | len  | opc  | d0   | d1   | d2   | d3   | tag  | <4*(len+1) data B>   | crcH | crcL |
     +-----+------+------+------+------+------+------+------+----------------------+------+------+
```

- `len` (byte 1) = `(payload_bytes / 4) - 1`; `m = 4*(len+1)` payload bytes.
- `tag` (byte 7) selects the "info area": `0xF1`=versionInfoArea,
  `0xF2`=regInfoArea, `0xF3`=paraInfoArea, `0xF5`=timerInfoArea (lines
  2336-2362).
- CRC over the full frame minus the trailing two bytes; verified as
  `getCrc16Val(buf, total_len) == 0` (line 3320).

### bdCmd frame (firmware-update / boot-loader protocol)

A separate framing exists with sync `0xBC` (`BLE_CMD_SYNC = 188`, line 1428)
and `0xBD` (`BLE_CMD_DATA_SYNC = 189`). Used for flash erase / firmware
download / BLE-MCU firmware update only — not needed for normal heater
control. See the `ke` enum at line 1428 to drive it.

## Commands

All heater commands use the 8-byte heater frame above. `opcode` is one of the
`DB0_DN_*` values from the `heaterCmd` enum (`Ke`, beautified line 1540-1541):

| Opcode | Hex  | Name                | Purpose                                |
|-------:|-----:|---------------------|----------------------------------------|
| 97     | 0x61 | DB0_DN_CMD          | One-shot button (on/off/up/down/etc.)  |
| 98     | 0x62 | DB0_DN_MANU_PUMP    | Timed manual pump (seconds in d0)      |
| 99     | 0x63 | DB0_DN_GET_REG_ADDR | Ask controller for its register table  |
| 100    | 0x64 | DB0_DN_GET_REG_VAL  | Read register block by short-addr      |
| 101    | 0x65 | DB0_DN_AUTO_UPDATA  | Start/stop periodic telemetry          |
| 102    | 0x66 | DB0_DN_SHORT_PARA   | Write a single setting (temp, gear...) |

Controller-to-app opcodes (`DB0_UP_*`):

| Opcode | Hex  | Name                | Meaning                                |
|-------:|-----:|---------------------|----------------------------------------|
| 65     | 0x41 | DB0_UP_CMD_ACK      | ACK to DB0_DN_CMD (echoes random+sent) |
| 66     | 0x42 | DB0_UP_MANU_PUMP_ACK| ACK to DB0_DN_MANU_PUMP                |
| 67     | 0x43 | DB0_UP_REG_ADDR     | Reply with register-block address      |
| 70     | 0x46 | DB0_UP_WRITE_ACK    | ACK to DB0_DN_SHORT_PARA               |

(Decompiled JS lists this as `0x44`, but on-the-wire sniffing of a real
controller shows `0x46`. The JS constant table appears to be stale; the
firmware emits 0x46 for SHORT_PARA acks. Success frames echo the
`<idx> <d1> <d2>` triplet; an unknown index returns `FF FF 02`.)

Probed SHORT_PARA indices 5..12 on a TSGB controller (2026-05-25): every
one returned `AA 00 46 FF FF 02 …` — "unknown index, error 2". There is
no hidden altitude setter exposed via SHORT_PARA on this firmware. The
`altitudeValue` field at regInfo offset 4 is the heater's barometric
sensor reading only, and is not writeable over BLE.

### Start heater / stop heater / button events

Source: `pages/device/device.vue:2688/2723/2761` (lines 4310, 4312, 4320):

```js
a = []; a.push(Qe.heaterCmd.CMD_ON); a.push(ft(255)); a.push(0);
Qe.heaterSendCmd(Qe.heaterCmd.DB0_DN_CMD, a);
```

`ft(255)` is `Math.floor(Math.random()*255)` (line 3994) — a per-press
nonce that the controller echoes in `d1` of the `DB0_UP_CMD_ACK` reply
(saved as `Ye.buttonRandom`, line 2390; compared at 2276).

```
Start:  AA 00 61 01 <rand> 00 <crcH> <crcL>     # CMD_ON
Stop:   AA 00 61 02 <rand> 00 <crcH> <crcL>     # CMD_OFF
Up:     AA 00 61 03 <rand> 00 <crcH> <crcL>     # CMD_UP   (enum, unbound)
Down:   AA 00 61 04 <rand> 00 <crcH> <crcL>     # CMD_DOWN (enum, unbound)
```

### Other DB0_DN_CMD button codes

Enum values from `Ke` (line 1541):

| `d0` | Name              | UI trigger                                            |
|-----:|-------------------|-------------------------------------------------------|
| 0    | CMD_EMPUTY        | (empty/no-op)                                         |
| 1    | CMD_ON            | Power button "Turn on"                                |
| 2    | CMD_OFF           | Power button "Shut down"                              |
| 3    | CMD_UP            | (Not directly bound to a UI button in this build.)    |
| 4    | CMD_DOWN          | (Not directly bound.)                                 |
| 5    | CMD_CLEAR         | (Not directly bound.)                                 |
| 6    | CMD_RF_PAIR       | (Not directly bound.)                                 |
| 7    | CMD_OK            | (Not directly bound.)                                 |
| 8    | CMD_SWITCH_TEMP_FC| Toggle units °F → °C (line 6546)                      |
| 9    | CMD_BLOW_ON       | "Ventilation settings" on (line 4288)                 |
| 10   | CMD_SWITCH_TEMP_CF| Toggle units °C → °F (line 6544)                      |
| 11   | CMD_OIL_PUMP_ON   | "Start pumping" (manual prime) (line 4330)            |
| 12   | CMD_OIL_PUMP_OFF  | "Stop pumping" / stop blow / stop oil pump (4277,4287)|
| 15   | CMD_FW_TRANS_SUCC | (Firmware path.)                                      |

Note: Stop-blow-mode and stop-oil-pump both go through `CMD_OFF` (12 here is
`CMD_OIL_PUMP_OFF`, but the UI for "stop blow" uses `CMD_OFF` per line 4287).

### Manual pump run (line 2484 — `iolPump()`)

The vendor app's *real* manual-prime path doesn't use `CMD_OIL_PUMP_ON` —
that button just wakes the toggle in the device-page UI. Instead, when the
user opens the manual-pump control the app pushes a `DB0_DN_MANU_PUMP`
frame with the desired run-time in seconds as `d0`:

```js
this.set_heaterData({name:"oilPumpTimeoutCount",value:20});
e = []; e.push(this.heaterParaArray[2].offsetArray[7].oilPumpTimeoutCount);
e.push(ft(255)); e.push(0);
Qe.heaterSendCmd(Qe.heaterCmd.DB0_DN_MANU_PUMP, e);
```

```
Pump run: AA 00 62 <secs> <rand> 00 <crcH> <crcL>
```

`<secs>` is clamped to 20..120 in the vendor app (`offsetArray[7].min/max`,
default 20). The pump is only accepted in `runningMode == 5 (Standby)` or
`7 (Manual-pump)`; outside those modes the controller silently drops it.
Off goes back through `CMD_OFF` (per `iolPump()` line 2507) — not
`CMD_OIL_PUMP_OFF`, despite the name.

### Set target temperature (line 4147)

```
AA 00 66 01 <unit> <temp> <crcH> <crcL>
```

- opcode `0x66 = DB0_DN_SHORT_PARA`, d0 `0x01 = SHORT_TARGET_TEMP`.
- `unit`: `0` = °C, `1` = °F.
- `temp`: unsigned byte. Ranges (line 620): 10-40 °C, 50-104 °F.
- "Fire and forget" — `heaterSendCmd` does **not** set `buttonMutex` for
  this case (line 2390), so no random-nonce ACK matching.

### Set power level / heat level ("gear")  (line 4149)

```
AA 00 66 02 00 <gear> <crcH> <crcL>
```

- d0 `0x02 = SHORT_TARGET_GEAR`. `aimGear` 1-10, `blowModeGear` 2-10.

### Set run mode  (lines 4299, 4304)

```
AA 00 66 00 00 <mode> <crcH> <crcL>
```

- d0 `0x00 = SHORT_RUN_MODE`. `mode`: 0=auto, 1=manual, 2=start/stop.

### Set start/stop hysteresis ("temp diff")  (line 4355)

```
AA 00 66 04 <unit> <diff> <crcH> <crcL>
```

- d0 `0x04 = SHORT_TEMP_DIFF_ADJUST`. `unit`: 0=°C(3-15), 1=°F(5-27).

### Set start/stop shut-off temperature

Same as "set target temperature" (`SHORT_TARGET_TEMP = 1`) but value comes
from `runStopModeShutDownTempFlag_C/F` (range 15-40 °C / 59-104 °F, line
628-631).

### Start / stop periodic telemetry

Opcode `0x65 = DB0_DN_AUTO_UPDATA`. d0 is `ADDR_TYPE_REG = 0x02`. Three
observed call-sites:

```
On-connect (line 3884):   AA 00 65 02 14 1E <crc>   # ~2 s interval
Page-enter (line 4019):   AA 00 65 02 0A 00 <crc>   # ~1 s interval
Page-leave (line 4028):   AA 00 65 02 00 1E <crc>   # STOP
```

Embedded logs label these as "2 s" and "1 s" respectively. The exact
meaning of the two trailing bytes is not commented anywhere; best guess is
period (100 ms ticks) + duration/max-count. The stop form is `(0, 30)`.

### Read a register block once  (line 4018)

```
AA 00 64 F2 00 05 <crcH> <crcL>     # read regInfoArea (tag 0xF2)
```

- opcode `0x64 = DB0_DN_GET_REG_VAL`.
- d0-d2: `[area_tag, short_addr_hi, short_addr_lo]`.
- Area tags: `0xF1`=version, `0xF2`=reg, `0xF3`=para, `0xF4`=mainBoard,
  `0xF5`=timer.
- Reply: telemetry response variant, byte 7 = area tag.

## Telemetry

### regInfoArea (main heater status) — `tag = 0xF2`, payload 40 bytes

When `DB0_DN_AUTO_UPDATA` is active the controller streams these frames.
Payload is 40 bytes (`len = 9`). Offsets are **within the payload** (frame
byte 8 onward), from `heaterParaArray[2].offsetArray[]` (lines 619-671). All
multi-byte integers are **little-endian** (`(payload[off+1]<<8) |
payload[off]`, line 822).

| Off | Size | Field            | Decoding                                                                                                                       |
|----:|-----:|------------------|--------------------------------------------------------------------------------------------------------------------------------|
| 0   | 1    | machineStatus    | Bit-packed status byte (see below).                                                                                            |
| 1   | 1    | deviceRunStatus  | Bit-packed (run-mode flags).                                                                                                   |
| 2   | 2    | voltage (V)      | `((hi<<8) | lo) / 10.0` → volts.                                                                                               |
| 4   | 2    | altitude (m)     | `(hi<<8) | lo`. Values ≥ 8000 indicate sensor fault (line 822).                                                                |
| 6   | 2    | ambient temp     | Signed (`>32767` → `65536-v`, negative). Scale ÷10. °F = `(320 + 1.8*raw)/10`. (line 822-823.)                                  |
| 8   | 2    | housing temp     | Same encoding as ambient.                                                                                                      |
| 10  | 2    | oil-pump Hz      | `((hi<<8) | lo) / 10.0` Hz.                                                                                                    |
| 12  | 2    | ignition power W | `((hi<<8) | lo) / 10.0` watts. Also reused as "oil-pump timeout countdown" (seconds) when machineStatus.runningMode == 7.      |
| 14  | 2    | fan rpm          | `(hi<<8) | lo` rpm.                                                                                                            |
| 16  | 2    | intake temp      | Same as ambient. Magic value `32760` = "not supported on this device" (line 823).                                              |
| 18  | 2    | outlet temp      | Same as ambient. Magic value `32760` = "not supported".                                                                        |
| 20  | 2    | errorCode        | Bitmask, **little-endian**. See "Fault codes".                                                                                 |
| 22  | 1    | TypeID           | Machine type ID; mirrored into `state.ble.machineTypeID`.                                                                      |
| 23  | 1    | config           | Bit 7 = altitude-sensor enabled, bit 6 = timer-feature enabled.                                                                |
| 24  | 4    | guid             | 4-byte chip GUID (saved raw into `offsetArray[14].rawData`).                                                                   |
| 28  | 4    | errorPlus        | Extra fault field: `displayValue = rawData[28] & 3`. Bytes 28-31 stored raw. Byte 29 is `runStopModeTempDiff` echo (line 823). |
| 32  | 4    | aimTarget        | Byte 0 = target gear (1-10). Byte 1 = target temp in current unit (`tempUnitFlag` decides °C vs °F).                          |
| 36  | 4    | reserved         | (Saved raw, unused.)                                                                                                           |

#### machineStatus byte (offset 0) — line 794-823

| Bits | Field                | Meaning                                           |
|------|----------------------|---------------------------------------------------|
| 0-3  | runningMode          | 0=boot, 1=ignition, 2=auto-run, 3=manual-run, 4=cooldown, 5=standby, 6=fault, 7=manual-pump, 8=ventilation, 9=start/stop-active, 10=start/stop-configure |
| 0-1  | iolPumpRunStopStatus | (alias) non-zero = manual pump in progress        |
| 2    | fan running          | 1 = fan motor on                                  |
| 3    | glow plug on         | 1 = ignition plug energized                       |
| 4    | tempUnitFlag         | 0 = °C, 1 = °F                                    |
| 5-6  | runStopModeBits      | start/stop sub-state (2 = above shutdown setpoint)|
| 7    | runStopModePowerFlag | "device considers itself on" in start/stop mode   |

Side-channel: `rawData[errorPlus_offset + 2] == 0xA5` → firmware supports
start/stop mode (`hideRunStopModeFlag = 0`).

### versionInfoArea — `tag = 0xF1`

Payload is the controller's version string (ASCII, null-terminated), stored
into `state.ble.kzqVersionStr` (lines 2339-2346). The BLE-MCU's own version
arrives separately via opcode `0x02` (`r[3].r[4].r[5]`, line 3334-3335).

### paraInfoArea — `tag = 0xF3` (advanced parameters; lines 830-833)

Payload byte 0 is `advancedPara`:
- bits 0-1: `runMode`.
- bits 2-3: `wattSelectIndex` (0=2 kW, 1=3 kW, 2=5 kW, 3=8 kW; +1 if non-zero).
- bits 4-5: `oilPumpTypeSelectIndex` (0=16 mL, 1=22 mL, 2=28 mL).
- bits 6-7: `voltSelectIndex` (0=12 V, 1=24 V, 2=auto; clamped to ≤2).

Byte 7 = `tempUnitFlag`. Byte 16 = `aimGear`, byte 17 = `aimTemp`.

### timerInfoArea — `tag = 0xF5` (lines 834-839)

Seven 5-byte records, one per weekday: `[timerMode, onH, onM, offH, offM]`.
`timerMode` raw 1/2 folds to 3; raw ≥ 4 folds to 4.

## Fault codes

`errorCode` (regInfoArea offset 20, 2 bytes little-endian) is a 16-bit
**bitmask**. The display layer in `pages/device/device.vue` (beautified lines
4807-4870) tests bits in order; the first set bit picks the label. The
labels are i18n keys; English strings extracted from the English locale
table (line 8671).

| Bit (mask)        | Key             | Short                                    | Detail (`-info`)                                                                                                                       |
|------------------:|-----------------|------------------------------------------|------|
| 0x0001 (bit 0)    | error-E-01      | Undervoltage fault                       | Low voltage: 24V below 18V, 12V below 10V.                                                                                             |
| 0x0002 (bit 1)    | error-E-02      | Overvoltage fault                        | Over voltage: 24V above 32V, 12V above 17V.                                                                                             |
| 0x0004 (bit 2)    | error-E-03      | Ignition plug fault                      | E-03-1: glow plug short circuit. E-03-2: glow plug open circuit.                                                                       |
| 0x0008 (bit 3)    | error-E-04      | Oil pump failure                         | E-04-1: oil pump short. E-04-2: oil pump open circuit.                                                                                  |
| 0x0010 (bit 4)    | error-E-05      | Machine overheating fault                | Housing temp > 260 °C; check inlet/outlet blockage.                                                                                    |
| 0x0020 (bit 5)    | error-E-06      | Motor fault                              | E-06-1: fan short. E-06-2: fan open. E-06-3: hall sensor failed to read fan speed.                                                     |
| 0x0040 (bit 6)    | error-E-07      | Short line fault                         | Comms cable / plug from control panel to ECU open or loose.                                                                            |
| 0x0080 (bit 7)    | error-E-08      | Flame extinction fault                   | Oil circuit blocked by air or wax.                                                                                                     |
| 0x0100 (bit 8)    | error-E-09      | Sensor fault                             | E-09-1: housing temp sensor short. E-09-2: housing temp sensor open.                                                                   |
| 0x0200 (bit 9)    | error-E-10      | Ignition fault                           | Two ignition failures; clogged volatile screen / blocked oil / pump jammed / bad fuel.                                                |
| 0x0400 (bit 10)   | error-E-11      | Temperature sensor fault                 | Ambient temp sensor short or open.                                                                                                     |
| 0x0800 (bit 11)   | error-E-12      | Overtemperature fault                    | Controller temp > 100 °C; check inlet/outlet blockage or ECU damage.                                                                  |
| 0x1000 (bit 12)   | error-E-13      | Air inlet high temperature protection    | Self-clears after a short period.                                                                                                      |
| 0x2000 (bit 13)   | error-E-14      | Air outlet high temperature protection   | Self-clears after a short period.                                                                                                      |
| 0x4000 (bit 14)   | error-E-15      | Air inlet temperature sensor fault       | Inlet temp sensor failure.                                                                                                             |
| 0x8000 (bit 15)   | error-E-16      | Air outlet temperature sensor fault      | Outlet temp sensor failure.                                                                                                            |

`errorPlus` (regInfoArea offset 28, low 2 bits) is an extra "附加故障码"
("additional fault code"); no English string is provided for the
sub-values — they are shown as raw `0..3` (line 823).

## Open questions

1. **Auto-update interval encoding.** `[2, 20, 30]` is labeled "2 s" and
   `[2, 10, 0]` as "1 s" in embedded logs, but the third byte (`30`/`0`) is
   uncommented across all three call-sites (3884, 4019, 4028). Best guess:
   byte 1 = period in 100 ms ticks, byte 2 = duration / max-count.
   Stop encoding is `(0, 30)`. Worth a capture to confirm.

2. **`heaterSendRawData` (line 2398-2409).** A separate variable-length
   builder used only by debug screens (`subPages/me/debugBox.vue` etc.):
   starts `AA <len/4-1> FF <rand>` and embeds *two* CRCs (one over the inner
   payload, one over the outer frame). Not part of the normal protocol; the
   semantics of bytes 4-7 (`addr`) are not explored here.

3. **`bleMTU` exact value.** `uni.setBLEMTU(deviceId, Qe.bleMTU)` is called
   (line 3177) but the constant is defined elsewhere. Default unset = ATT
   23. Worth grepping `bleMTU =` if the firmware requires a specific value.

4. **`bandMac` magic byte derivation.** Controller sends a magic byte at
   `r[5]` of the `0x21` allow-reply (line 3302); app saves it as byte 6 of
   `deviceId` storage and replays on subsequent connects. Not visible from
   the JS how the controller computes it.

5. **Two RX paths for area-tag.** The "default" branch in `heaterProcess`
   (line 2336-2362) switches on byte 7 (`0xF1/F2/F3/F5`) for auto-update
   streams. The `DB0_UP_REG_ADDR` path (opcode `0x43`, line 2285) uses
   bytes 4,5 as a "short address". Both coexist; auto-update telemetry uses
   the byte-7 form.

6. **`errorPlus` sub-codes.** 2-bit field at regInfoArea offset 28 (values
   0-3). Rendered raw, no i18n key; meaning unknown without firmware src.

7. **Skipping the bonding prompt.** For a re-implementation that already
   knows the MAC, the unconditional reply
   `AA 00 92 00 00 00 00 00 00 00 <crcH> <crcL>` (line 3344, opcode
   `BLE_CMD_DN_BLINK = 0x92`) is the bonded-path version of the bandMac
   exchange and may be sufficient to avoid re-prompting.
