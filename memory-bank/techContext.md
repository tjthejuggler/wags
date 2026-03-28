# WAGS — Tech Context

*Last updated: 2026-03-28*

## Build System

- **Gradle** with Kotlin DSL (`.gradle.kts`)
- **Version catalog** at `gradle/libs.versions.toml`
- **AGP** 8.13.2
- **Kotlin** 2.0.21
- **KSP** 2.0.21-1.0.28 (for Room + Hilt annotation processing)
- Single `:app` module

## Android Configuration

- `minSdk = 26`
- `compileSdk = 36`
- `targetSdk = 36`
- `namespace = "com.example.wags"`
- `jvmTarget = "11"`
- Compose enabled

## Key Dependencies

| Category | Library | Version |
|---|---|---|
| DI | Hilt | 2.56.2 |
| DB | Room | 2.6.1 |
| BLE | Polar BLE SDK | 5.11.0 |
| Math | Apache Commons Math 3 | 3.6.1 |
| Charts | Vico Compose M3 | 2.0.0 |
| Navigation | Navigation Compose | 2.8.5 |
| Lifecycle | ViewModel Compose | 2.8.7 |
| Coroutines | kotlinx-coroutines-android | 1.8.1 |
| Rx Bridge | kotlinx-coroutines-rx3 | 1.8.1 |
| RxJava | RxJava3 | 3.1.8 |
| HTTP | OkHttp | 4.12.0 |
| Images | Coil Compose | 2.6.0 |
| Garmin | Connect IQ Companion SDK | 2.3.0 |
| Compose BOM | Compose BOM | 2024.09.00 |

## Permissions Required

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` — BLE for Polar sensors
- `ACCESS_FINE_LOCATION` — BLE scanning on API < 31
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` — BLE foreground service
- `WAKE_LOCK` — Keep CPU alive during measurements
- `VIBRATE` — Haptic feedback for apnea countdowns
- `INTERNET` — YouTube oEmbed, Spotify API
- `BIND_NOTIFICATION_LISTENER_SERVICE` — Spotify MediaSession access
- `com.example.tail.permission.TAIL_INTEGRATION` — Tail app IPC

## Services

- `BleService` — Foreground service (`connectedDevice` type) for BLE streaming
- `MediaNotificationListener` — NotificationListenerService for Spotify track detection

## Development Environment

- **OS**: Linux 6.17
- **IDE**: Android Studio (VSCode for editing)
- **Shell**: /bin/bash
- **Project path**: `/home/twain/AndroidStudioProjects/wags`

## Garmin Watch App (Monkey C)

- Located in `garmin/` directory
- Built with Connect IQ SDK
- **Rule: Do NOT modify unless specifically instructed**
- Handles: Apnea menu, free hold view, data transmission, settings, sync log
