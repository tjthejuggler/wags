# Meditation Background Recording Fix

## Context
Users reported that guided meditation sessions were not being recorded when the screen was off and the app was closed. The root cause was that session recording logic was tied to the UI lifecycle, meaning when the app was killed or the screen turned off, the recording process would stop before saving the session data.

## Decision
Implemented a foreground service architecture for meditation session recording to ensure sessions are saved even when the app is closed or the screen is off.

## Implementation Details

### 1. Created MeditationService
- **File**: [`app/src/main/java/com/example/wags/data/meditation/MeditationService.kt`](app/src/main/java/com/example/wags/data/meditation/MeditationService.kt)
- **Type**: Foreground service with `mediaPlayback` service type
- **Key Features**:
  - Runs independently of UI lifecycle
  - Acquires PARTIAL_WAKE_LOCK to keep CPU running
  - Displays persistent notification showing meditation status
  - Handles audio playback via MediaPlayer
  - Manages timer countdown if configured
  - Automatically stops and saves session when timer completes or user stops

### 2. Created MeditationSessionRecorder
- **File**: [`app/src/main/java/com/example/wags/data/meditation/MeditationSessionRecorder.kt`](app/src/main/java/com/example/wags/data/meditation/MeditationSessionRecorder.kt)
- **Purpose**: Handles session data recording and persistence
- **Key Functions**:
  - `startSession()`: Initialize new session with timestamp and audio info
  - `addTelemetrySample()`: Accumulate HR/RMSSD data during session
  - `stopSession()`: Save session to database with analytics calculations

### 3. Updated MeditationViewModel
- **File**: [`app/src/main/java/com/example/wags/ui/meditation/MeditationViewModel.kt`](app/src/main/java/com/example/wags/ui/meditation/MeditationViewModel.kt)
- **Changes**:
  - Added `startMeditationService()` to launch foreground service when session starts
  - Added `stopMeditationService()` to stop service when session ends
  - Service receives audio file name, directory URI, and timer duration via Intent extras

### 4. Updated AndroidManifest
- **File**: [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml)
- **Added**: MeditationService declaration with `mediaPlayback` foreground service type

## Technical Considerations

### Wake Lock Management
- Service acquires PARTIAL_WAKE_LOCK for 10 minutes max
- Prevents CPU from sleeping during meditation session
- Released when session stops

### Notification Requirements
- Foreground service requires persistent notification
- Shows current meditation status and remaining time
- Includes "Stop" action button for manual termination
- Notification updates to show "session saved" when complete

### Audio Playback
- Service handles audio playback independently of UI
- Uses MediaPlayer with looping enabled
- Supports SAF (Storage Access Framework) file URIs
- Gracefully handles audio file errors without crashing session

### Data Persistence
- Session data saved to database via MeditationSessionRecorder
- Telemetry samples accumulated during session
- Analytics calculated on session completion (HR, RMSSD, slopes)
- Audio ID resolved from file name for session association

## Testing
- Build successful with `./gradlew assembleDebug`
- APK installed successfully on device (SM-S918U1 - 16)
- Service properly registered in manifest
- Dependency injection configured with proper dispatcher qualifiers

## Future Considerations
- Could add telemetry data streaming from service to UI for real-time updates
- Consider adding session recovery if service is unexpectedly killed
- May need to handle device sleep modes more aggressively for very long sessions