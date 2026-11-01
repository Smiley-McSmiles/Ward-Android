#!/bin/sh
# Builds a release APK and signs it with the stable Ward release key, using the
# v3 rotation lineage from the old debug key so Android 9+ devices can update in
# place. The keystore, credentials and lineage live outside the repository.
set -eu
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
version="$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -n 1)"
[ -n "$version" ] || { echo "could not read versionName from app/build.gradle.kts" >&2; exit 1; }
out="app/build/outputs/apk/release/ward-${version}-android.apk"
creds="${WARD_KEYSTORE_CREDENTIALS:-$HOME/keystores/ward-android-release.txt}"
lineage="${WARD_LINEAGE:-$HOME/keystores/ward-android-lineage.bin}"
[ -f "$creds" ] || { echo "release keystore credentials not found: $creds" >&2; exit 1; }
[ -f "$lineage" ] || { echo "signing lineage not found: $lineage" >&2; exit 1; }
. "$creds"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
./gradlew :app:assembleRelease
"$APKSIGNER" sign \
  --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --next-signer \
  --ks "$keystore" --ks-key-alias "$alias" \
  --ks-pass "pass:$storepass" --key-pass "pass:$keypass" \
  --lineage "$lineage" --rotation-min-sdk-version 28 \
  --out "$out" \
  app/build/outputs/apk/release/app-release-unsigned.apk

expected="${WARD_CERT_SHA256:-782882473f316393e5dba6ea50a658cb4f486dc873836dea09be9e54d109a722}"
actual="$("$APKSIGNER" verify --min-sdk-version 33 --print-certs "$out" 2>/dev/null \
  | sed -n 's/.*Signer #1 certificate SHA-256 digest: //p' | head -n 1)"
if [ "$actual" != "$expected" ]; then
  echo "ERROR: $out is not signed with the stable Ward release key." >&2
  echo "       expected $expected" >&2
  echo "       got      $actual" >&2
  exit 1
fi
echo "$out"
