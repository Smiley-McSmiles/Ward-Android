#!/bin/sh
set -eu
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
exec ./gradlew :app:assembleDebug "$@"
