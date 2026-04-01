Unless specifically intructed otherwise, do not do anything at all with the garmin wear app.

## Auto Build & Deploy After Changes

This is an Android project. You MUST automatically build and install the debug APK to a connected device/emulator after completing your code changes. Do this as your final step before calling `attempt_completion`.

### Build & Deploy Command
After all code changes are complete and saved, run:
```
./gradlew installDebug
```

### Important Notes
- Always run `./gradlew installDebug` from the project root directory (where `gradlew` lives).
- If the build fails, report the error to the user — do NOT silently ignore build failures.
- If no device/emulator is connected (adb shows no devices), inform the user that they need to connect a device or start an emulator, then attempt the install again.
- Use a timeout of 120 seconds for the gradle command since Android builds can be slow.
- Do NOT launch the app after install — just install it. The user will launch it manually for testing.
- If the user explicitly says "don't build" or "skip deploy", respect that and skip this step.
