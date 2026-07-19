## Wake Lock Timeout Fix for Background Sessions

### Date
2025-07-18

### Context
Meditation sessions were being canceled and data was lost when the phone screen was off for more than 10 minutes. The user reported that after ~10 minutes of a meditation session with the screen off, the audio stopped and the session was canceled with no record of the data.

### Decision
Fixed two critical issues that caused session cancellation when the screen is off:

1. **Wake Lock Timeout in MeditationService**: Removed the 10-minute timeout from the wake lock acquisition. The wake lock is now held indefinitely until explicitly released in `stopSession()`. This prevents the CPU from sleeping during long meditation sessions.

2. **ViewModel onCleared() Stopping Session**: Modified `MeditationViewModel.onCleared()` to NOT stop the meditation session. The `MeditationService` runs independently as a foreground service and should continue recording even when the ViewModel is cleared (e.g., when user navigates away or screen is off).

3. **Wake Lock Timeout in BleService**: Also removed the 10-minute timeout from `BleService.acquireWakeLock()` to prevent BLE connections from dropping during long sessions.

### Rationale
- The foreground service pattern is designed to keep operations running independently of the UI lifecycle
- Wake locks with timeouts are appropriate for short operations, but meditation sessions can be arbitrarily long
- The system will still properly release wake locks when the service is destroyed
- The notification provides user control to stop the session explicitly

### Impact
- Meditation sessions will now continue indefinitely until:
  1. User explicitly stops it (via UI or notification)
  2. The timer completes (if set)
  3. The system kills the service (unlikely with foreground service + wake lock)
- BLE connections will remain stable during long sessions
- No data loss when screen is off for extended periods

### Files Modified
- `app/src/main/java/com/example/wags/data/meditation/MeditationService.kt` - Removed wake lock timeout
- `app/src/main/java/com/example/wags/ui/meditation/MeditationViewModel.kt` - Removed session stop in onCleared()
- `app/src/main/java/com/example/wags/data/ble/BleService.kt` - Removed wake lock timeout