#!/bin/sh
# Builds a release APK and signs it with the stable Ward release key.
# The keystore and its credentials live outside the repository.
set -eu
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
version="$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -n 1)"
[ -n "$version" ] || { echo "could not read versionName from app/build.gradle.kts" >&2; exit 1; }
out="app/build/outputs/apk/release/ward-${version}-android.apk"
creds="${WARD_KEYSTORE_CREDENTIALS:-$HOME/keystores/ward-android-release.txt}"
[ -f "$creds" ] || { echo "release keystore credentials not found: $creds" >&2; exit 1; }
. "$creds"
./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
  --ks "$keystore" --ks-key-alias "$alias" \
  --ks-pass "pass:$storepass" --key-pass "pass:$keypass" \
  --out "$out" \
  app/build/outputs/apk/release/app-release-unsigned.apk
echo "$out"
