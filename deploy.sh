#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"

export JAVA_HOME=/home/gnutt/Downloads/android-studio-panda3-linux/android-studio/jbr
export ANDROID_HOME=~/Android/Sdk
export PATH=$JAVA_HOME/bin:$PATH

ADB="$ANDROID_HOME/platform-tools/adb"

echo "==> Building APK..."
cd "$SCRIPT_DIR"
./gradlew assembleDebug --quiet

devices=$("$ADB" devices | awk '/\tdevice$/ { print $1 }')
if [[ -z "$devices" ]]; then
    echo "No devices connected via USB."
    exit 1
fi

while IFS= read -r serial; do
    echo "==> Installing on $serial..."
    "$ADB" -s "$serial" install -r "$APK"
done <<< "$devices"
