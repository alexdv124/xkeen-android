#!/bin/bash
# Setup JDK 17 + Android SDK for building without Android Studio
# Usage: bash setup-build.sh

set -e

BUILD_DIR="$(dirname "$0")/build-tools"
mkdir -p "$BUILD_DIR"

echo "=== Downloading JDK 17 ==="
if [ ! -d "$BUILD_DIR/jdk-17" ]; then
    if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
        curl -L -o "$BUILD_DIR/jdk17.zip" "https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip"
        unzip -q "$BUILD_DIR/jdk17.zip" -d "$BUILD_DIR"
        mv "$BUILD_DIR"/jdk-17.* "$BUILD_DIR/jdk-17"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        curl -L -o "$BUILD_DIR/jdk17.tar.gz" "https://aka.ms/download-jdk/microsoft-jdk-17.0.13-macOS-aarch64.tar.gz"
        tar xzf "$BUILD_DIR/jdk17.tar.gz" -C "$BUILD_DIR"
        mv "$BUILD_DIR"/jdk-17.*/Contents/Home "$BUILD_DIR/jdk-17"
    else
        curl -L -o "$BUILD_DIR/jdk17.tar.gz" "https://aka.ms/download-jdk/microsoft-jdk-17.0.13-linux-x64.tar.gz"
        tar xzf "$BUILD_DIR/jdk17.tar.gz" -C "$BUILD_DIR"
        mv "$BUILD_DIR"/jdk-17.* "$BUILD_DIR/jdk-17"
    fi
    echo "JDK 17 installed"
else
    echo "JDK 17 already exists"
fi

echo "=== Downloading Android SDK ==="
if [ ! -d "$BUILD_DIR/android-sdk/cmdline-tools" ]; then
    if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
        curl -L -o "$BUILD_DIR/cmdline-tools.zip" "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        curl -L -o "$BUILD_DIR/cmdline-tools.zip" "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
    else
        curl -L -o "$BUILD_DIR/cmdline-tools.zip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    fi
    mkdir -p "$BUILD_DIR/android-sdk/cmdline-tools"
    unzip -q "$BUILD_DIR/cmdline-tools.zip" -d "$BUILD_DIR/android-sdk/cmdline-tools"
    mv "$BUILD_DIR/android-sdk/cmdline-tools/cmdline-tools" "$BUILD_DIR/android-sdk/cmdline-tools/latest"
    echo "Android SDK cmdline-tools installed"
else
    echo "Android SDK already exists"
fi

export JAVA_HOME="$BUILD_DIR/jdk-17"
export ANDROID_HOME="$BUILD_DIR/android-sdk"

echo "=== Accepting licenses ==="
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true

echo "=== Installing platform & build-tools ==="
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0"

# Write local.properties
echo "sdk.dir=$(echo $ANDROID_HOME | sed 's|/|\\\\|g')" > "$(dirname "$0")/local.properties"

echo ""
echo "=== Setup complete ==="
echo "Build with:"
echo "  export JAVA_HOME=\"$BUILD_DIR/jdk-17\""
echo "  ./gradlew assembleDebug"
echo ""
echo "APK will be at: app/build/outputs/apk/debug/app-debug.apk"
