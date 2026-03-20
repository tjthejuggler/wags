# WAGS Garmin Connect IQ Watch App

## Overview
Companion watch app for the WAGS Android app. Runs on Garmin fenix 6X Pro.
Records free-dive breath holds with HR/SpO2 telemetry and syncs data to the phone.

## Architecture
- **Watch → Phone**: `Communications.transmit()` pushes hold data to the Android companion app
- **Phone → Watch**: `ConnectIQ.sendMessage()` sends commands (SYNC_REQUEST, ACK_HOLD, PING)
- **Local Storage**: `Application.Storage` persists holds until synced
- **Message Queue**: Sequential transmit with retry logic (max 3 in BLE queue)

## ⚠️ CRITICAL: Beta App Deployment Required

**`Communications.transmit()` does NOT work with sideloaded .prg files.**

When a CIQ app is sideloaded via USB, Garmin Connect Mobile (GCM) cannot route
`transmit()` messages because the app UUID is not registered in Garmin's cloud.
GCM immediately rejects the payload → `onError()` fires instantly on the watch.

Phone→Watch (`sendMessage()`) works fine for sideloaded apps because the watch OS
doesn't validate cloud identity for incoming messages. But Watch→Phone requires
cloud registration.

### How to Deploy as Beta App

1. **Create a Garmin Developer Account** at https://developer.garmin.com/
2. **Log in to the Connect IQ Developer Dashboard**: https://apps.garmin.com/developer/
3. **Create a new app**:
   - Click "Create App"
   - App Type: "Watch App"
   - Name: "WAGS"
   - Upload the compiled `.prg` file from `garmin/bin/wags.prg`
   - Set supported devices: fenix 6X Pro
   - **Important**: The UUID in `manifest.xml` must match what you upload
4. **Publish as Beta**:
   - In the release settings, choose "Beta" release type
   - Beta apps are hidden from public store search
   - Only your account (and whitelisted testers) can see/install it
   - Beta apps go live immediately — no review process
5. **Install via Garmin Connect Mobile**:
   - Open GCM on your phone
   - Go to Connect IQ Store → My Apps
   - Find WAGS (it will appear for your account)
   - Install it to your fenix 6X Pro
   - **This registers the UUID with GCM's routing tables**
6. **Verify**: After installing via GCM, `Communications.transmit()` will work.
   The watch sync log should show "MQ TX OK" instead of "MQ FAIL".

### Why This Matters
- Sideloaded apps: `transmit()` → immediate `onError()` (GCM drops the payload)
- Beta/Store apps: `transmit()` → `onComplete()` (GCM routes to companion app)
- Phone→Watch works either way (no cloud validation needed for incoming)

## Building

```bash
# From the garmin/ directory:
/path/to/connectiq-sdk/bin/monkeyc \
  -d fenix6xpro \
  -f monkey.jungle \
  -o bin/wags.prg \
  -y /path/to/developer_key
```

## Project Structure

```
garmin/
├── manifest.xml          # App manifest (UUID, permissions, target device)
├── monkey.jungle         # Build configuration
├── source/
│   ├── WagsApp.mc        # Main app + MessageQueue module + QueueListener
│   ├── FreeHoldView.mc   # Breath hold UI and session recording
│   ├── DataTransmitter.mc # Builds clean payloads and enqueues for transmit
│   ├── HoldStorage.mc    # Persistent hold storage (Application.Storage)
│   ├── MainMenuView.mc   # Main menu with Sync Log entry
│   ├── SyncLog.mc        # Persistent circular log buffer (40 entries)
│   ├── SyncLogView.mc    # Scrollable log display view
│   ├── SettingsManager.mc # User settings (lung volume, prep type, etc.)
│   ├── SettingsMenuDelegate.mc # Settings menu handler
│   ├── ApneaMenuDelegate.mc   # Apnea sub-menu handler
│   └── ResonancePlaceholderView.mc # Placeholder view
├── resources/
│   ├── drawables/        # Icons
│   ├── menus/            # XML menu definitions
│   └── strings/          # String resources
└── bin/
    └── wags.prg          # Compiled output
```

## Sync Protocol

1. Phone sends `{cmd: "SYNC_REQUEST"}` to watch
2. Watch reads unsynced holds from `Application.Storage`
3. For each hold:
   - If ≤30 samples: single `FREE_HOLD_RESULT` message with all data
   - If >30 samples: multiple `TELEMETRY_BATCH` messages + final `FREE_HOLD_RESULT` summary
4. Phone receives data via `registerForAppEvents()` callback
5. Phone sends `{cmd: "ACK_HOLD", holdId: N}` to confirm receipt
6. Watch marks hold as synced in storage

## Key Technical Notes

- **Options parameter**: `Communications.transmit(payload, null, listener)` — MUST be `null`, not `{}`. Empty dict crashes fenix 6 firmware.
- **BLE queue limit**: Max 3 messages in flight. MessageQueue enforces 1-at-a-time with retry.
- **Epoch format**: Stored as seconds (not milliseconds) to avoid Long overflow in CIQ.
- **No null values**: CIQ dictionaries cannot contain null values. Use -1 sentinel for missing firstContractionMs.
- **Module-level vars**: Used for MessageQueue state (persists across calls, no `static` keyword issues).
