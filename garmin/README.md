# WAGS Garmin Connect IQ App

## Overview

This is the Garmin watch companion app for WAGS (Wellness & Apnea Guidance System).
It runs on the **Garmin fēnix 6X Pro** and communicates with the Android app via
the Garmin Connect Mobile (GCM) bridge.

## Target Device

- **Device:** Garmin fēnix 6X Pro (`fenix6xpro`)
- **API Level:** 3.4.0 (maximum supported by fēnix 6X)
- **SDK Version:** Connect IQ SDK 9.1.0+

## App Structure

```
garmin/
├── manifest.xml              # App manifest (permissions, target device)
├── monkey.jungle             # Build configuration
├── resources/
│   ├── strings/strings.xml   # App name and string resources
│   ├── drawables/            # Launcher icon
│   └── menus/                # XML menu definitions
└── source/
    ├── WagsApp.mc            # Main app entry point
    ├── MainMenuView.mc       # Top-level menu (Apnea | Resonance)
    ├── ApneaMenuDelegate.mc  # Apnea sub-menu (Free Hold | Settings)
    ├── SettingsMenuDelegate.mc # Settings sub-menus (Lung Volume, Prep Type, Time of Day)
    ├── SettingsManager.mc    # Persistent settings storage
    ├── FreeHoldView.mc       # Free Hold breath-hold screen
    ├── DataTransmitter.mc    # BLE data transmission to phone
    └── ResonancePlaceholderView.mc # Placeholder for future Resonance section
```

## Navigation Flow

```
Main Menu
├── Apnea
│   ├── Free Hold → FreeHoldView (START → HOLDING → DONE)
│   └── Settings
│       ├── Lung Volume (Full / Empty / Partial) — persisted
│       ├── Prep Type (No Prep / Resonance / Hyper) — persisted
│       └── Time of Day (Morning / Day / Night) — auto-detected from clock
└── Resonance (Coming Soon)
```

## Free Hold Controls

| State    | SELECT Button        | BACK Button    |
|----------|---------------------|----------------|
| READY    | Start the hold      | Exit to menu   |
| HOLDING  | Record contraction  | Stop the hold  |
| DONE     | Return to menu      | Return to menu |

### Haptic Feedback
- **Start:** Single vibration (200ms)
- **Contraction:** Short vibration (100ms)
- **Stop:** Double vibration (200ms + 100ms gap + 200ms)

## Data Protocol

### Payload Format (Watch → Phone)

The watch sends a `Dictionary` to the phone via `Communications.transmit()`:

```
{
    "type": "FREE_HOLD_RESULT",
    "durationMs": Long,
    "lungVolume": String,        // "FULL" | "EMPTY" | "PARTIAL"
    "prepType": String,          // "NO_PREP" | "RESONANCE" | "HYPER"
    "timeOfDay": String,         // "MORNING" | "DAY" | "NIGHT"
    "firstContractionMs": Long?, // null if no contraction
    "contractions": Array<Long>, // elapsed ms values
    "packedSamples": Array<Int>, // bit-packed HR/SpO2
    "sampleCount": Int,
    "startEpochMs": Long,
    "endEpochMs": Long
}
```

### Bit-Packing Protocol

Each telemetry sample is packed into a 16-bit integer:
```
packed = (HR << 8) | SpO2
```
- HR = 0 means null (no reading available)
- SpO2 = 255 means null (no reading available)

### Batched Transmission

For holds longer than 60 seconds, samples are sent in batches of 60:
1. `TELEMETRY_BATCH` messages (batchIndex, offset, samples)
2. Final `FREE_HOLD_RESULT` summary (with batchCount instead of packedSamples)

## Settings Persistence

| Setting     | Storage                    | Behavior                          |
|-------------|---------------------------|-----------------------------------|
| Lung Volume | Application.Storage        | Remembered across sessions        |
| Prep Type   | Application.Storage        | Remembered across sessions        |
| Time of Day | System.getClockTime()      | Auto-detected, matches Android app|

Time of Day buckets (matching Android `TimeOfDay.fromCurrentTime()`):
- Morning: 03:00 – 10:59
- Day: 11:00 – 17:59
- Night: 18:00 – 02:59

## Building

### Prerequisites
1. Install the Connect IQ SDK Manager
2. Download CIQ SDK 9.1.0+
3. Install VS Code with the Monkey C extension
4. Configure the SDK path in VS Code settings

### Build Commands
```bash
# Build for simulator
monkeyc -d fenix6xpro -f monkey.jungle -o bin/wags.prg

# Build for device (release)
monkeyc -d fenix6xpro -f monkey.jungle -o bin/wags.prg -r
```

### Deploy to Simulator
1. Start the Connect IQ simulator
2. Load `bin/wags.prg`
3. For Android testing: `adb forward tcp:7381 tcp:7381`

### Deploy to Device
1. Connect the fēnix 6X via USB
2. Copy `bin/wags.prg` to `GARMIN/APPS/` on the watch
3. Eject and disconnect

## Android Integration

The Android app needs:
1. **Garmin Connect Mobile** installed on the phone
2. **Connect IQ Companion SDK** AAR in `app/libs/`
3. The `GarminManager` class handles SDK initialization and message reception
4. The `GarminApneaRepository` bridges watch data into the existing apnea database

See `app/src/main/java/com/example/wags/data/garmin/` for the Android-side code.
