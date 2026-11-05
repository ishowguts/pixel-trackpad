#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17+)}"
jar=build/phone-cache/core-3.5.4.jar
# The build fetches and verifies the single dependency before this offline check.
printf '%s  %s\n' 71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13 "$jar" | shasum -a 256 -c -
rm -rf build/scanner-check
mkdir -p build/scanner-check
"$JAVA_HOME/bin/javac" --release 17 -Xlint:all -classpath "$jar" -d build/scanner-check \
    phone/src/local/pixeltrackpad/Invitation.java phone/src/local/pixeltrackpad/QrDecoder.java phone/check/ScannerCheck.java
"$JAVA_HOME/bin/java" -classpath "build/scanner-check:$jar" local.pixeltrackpad.ScannerCheck
