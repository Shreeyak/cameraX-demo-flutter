#!/usr/bin/env bash
# Build, install, launch the CameraX demo and stream logcat output.
# Usage: ./run.sh
set -e

echo "▸ Building debug APK…"
flutter build apk --debug

echo "▸ Installing on device…"
adb install -r build/app/outputs/flutter-apk/app-debug.apk

echo "▸ Launching CameraActivity…"
adb shell am start -n com.example.camera_x_demo/.CameraActivity

echo "▸ Streaming logs (CameraXDemo tag)… Press Ctrl+C to stop."
adb logcat -s CameraXDemo:I -v time
