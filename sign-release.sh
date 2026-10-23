#!/bin/sh
# Builds the release APK and signs it with the local Android debug key so it can
# be installed directly. For a store build, use your own signing config.
set -eu
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
  --ks "${HOME}/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out app/build/outputs/apk/release/ward-1.0.1-android.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
echo "app/build/outputs/apk/release/ward-1.0.1-android.apk"
