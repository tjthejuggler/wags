#!/bin/bash

# Android Auto-Install Script
# Waits for device connection and installs the debug APK

set -e

echo "🔌 Waiting for Android device connection..."

# Wait for device to be available
MAX_WAIT=60
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    if adb devices | grep -q "device$"; then
        echo "✅ Device connected!"
        break
    fi
    echo "⏳ Waiting for device... ($((MAX_WAIT - WAITED))s remaining"
    sleep 5
    WAITED=$((WAITED + 5))
done

if [ $WAITED -ge $MAX_WAIT ]; then
    echo "❌ Timeout: No device found after $MAX_WAIT seconds"
    echo "💡 Try:"
    echo "   - Check USB connection"
    echo "   - Enable USB debugging on device"
    echo "   - Run: adb devices"
    exit 1
fi

echo "🏗️  Building debug APK..."
./gradlew assembleDebug

echo "📦 Installing APK to device..."
./gradlew installDebug

echo "✅ Installation complete!"
echo "🚀 App should be available on your device"