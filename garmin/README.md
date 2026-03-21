# WAGS Garmin Connect IQ Watch App

## Overview
Companion watch app for the WAGS Android app. Runs on Garmin fenix 6X Pro.
Records free-dive breath holds with HR/SpO2 telemetry and syncs data to the phone.

## Architecture
- **Watch → Phone**: `Communications.transmit()` pushes hold data to the Android companion app
- **Phone → Watch**: `ConnectIQ.sendMessage()` sends commands (SYNC_REQUEST, ACK_HOLD, PING)
- **Local Storage**: `Application.Storage` persists holds until synced
- **Message Queue**: Sequential transmit with retry logic (max 3 in BLE queue)

## ⚠️ CRITICAL: Two Requirements for Watch→Phone Sync

**`Communications.transmit()` requires TWO things to work:**

1. **The CIQ app must be installed from the Connect IQ Store** (not sideloaded)
2. **The Android companion app package name must be registered in the store listing**

Without BOTH of these, GCM immediately rejects `transmit()` payloads → `onError()`
fires instantly on the watch. Phone→Watch (`sendMessage()`) works either way.

### Step 1: Deploy as Beta App on Connect IQ Store

1. **Create a Garmin Developer Account** at https://developer.garmin.com/
2. **Log in to the Connect IQ Developer Dashboard**: https://apps.garmin.com/developer/
3. **Create a new app**:
   - Click "Create App"
   - App Type: "Watch App"
   - Name: "WAGS"
   - Upload the compiled `.iq` file from `garmin/bin/wags.iq`
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

### Step 2: Register the Android Companion App Package Name ⚠️

**This is the step we were missing.** Even with a Beta store deployment,
`Communications.transmit()` will fail if GCM doesn't know which Android app
should receive the data.

1. **Go to the Connect IQ Developer Dashboard** and edit your WAGS app listing
2. **Find the "Companion Application" configuration** (may be labeled as
   "Android Package Name", "Play Store URL", or similar)
3. **Enter the Android package name**: `com.example.wags`
4. **Save the changes**

### Step 3: Re-upload the CIQ App to the Store ⚠️ CRITICAL

**After registering the companion app, you MUST re-upload a new version of the
CIQ app.** The companion app metadata is embedded in the store listing when the
app is built/uploaded. Simply adding the package name in the dashboard doesn't
retroactively update already-installed watch apps.

1. **Build a new `.iq` file** (even if no code changes — just re-compile):
   ```bash
   cd garmin/
   /path/to/connectiq-sdk/bin/monkeyc \
     -e \
     -f monkey.jungle \
     -o bin/wags.iq \
     -y /path/to/developer_key
   ```
2. **Upload the new `.iq` to the Connect IQ Developer Dashboard** as a new beta version
3. **Wait for the beta to go live** (usually immediate for beta releases)
4. **On your watch**: Uninstall the old WAGS app, then re-install from the CIQ Store
   - This ensures the watch has the version with companion app metadata
5. **On your phone**: Force-close Garmin Connect Mobile, reopen it, and sync
   - This forces GCM to download the updated routing table

### Step 4: Verify

After completing ALL steps:
- The Android app's sync log should show "App status: INSTALLED" (not "timeout")
- The watch sync log should show "MQ TX OK" instead of "MQ FAIL"
- The Android app's Garmin Sync Log should show "RX msg" entries

### Troubleshooting

**"App info timeout — using fallback"** in the Android sync log:
- GCM doesn't recognize the companion app association yet
- Try: Force-close GCM → reopen → sync watch → wait 5 min → retry
- If persists: Re-upload the CIQ app to the store and re-install on watch

**"MQ FAIL" on the watch, retries then "MQ SKIP"**:
- `Communications.transmit()` is being rejected by GCM
- This means the companion app registration hasn't propagated yet
- Try the full cycle: re-upload CIQ app → re-install on watch → force-sync GCM
- Companion registration can take up to 24 hours to fully propagate

**Phone→Watch works but Watch→Phone doesn't**:
- `sendMessage()` (phone→watch) doesn't require companion registration
- `transmit()` (watch→phone) DOES require it
- This asymmetry is by design in the Garmin CIQ architecture

### Why This Matters
- Sideloaded apps: `transmit()` → immediate `onError()` (GCM drops the payload)
- Store app WITHOUT companion registration: `transmit()` → immediate `onError()`
- Store app WITH companion registration + re-upload: `transmit()` → `onComplete()` ✓
- Phone→Watch works in all cases (no cloud validation needed for incoming)

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
