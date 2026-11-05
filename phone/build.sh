#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17+)}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
tools="$sdk/build-tools/37.0.0"
platform="$sdk/platforms/android-37.0/android.jar"
mkdir -p build/phone-cache .local
jar=build/phone-cache/core-3.5.4.jar
sha256=71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13
if [ ! -f "$jar" ]; then
    trap 'rm -f "$jar.tmp"' EXIT HUP INT TERM
    curl --fail --location --silent --show-error --proto '=https' \
        https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.4/core-3.5.4.jar -o "$jar.tmp"
    printf '%s  %s\n' "$sha256" "$jar.tmp" | shasum -a 256 -c -
    mv "$jar.tmp" "$jar"
    trap - EXIT HUP INT TERM
fi
printf '%s  %s\n' "$sha256" "$jar" | shasum -a 256 -c -
# Recreate intermediates so deleted classes and old secondary dex files cannot survive a build.
rm -rf build/phone
mkdir -p build/phone/classes build/phone/dex build/phone/assets/licenses
cp phone/licenses/*.txt build/phone/assets/licenses/
cp NOTICE build/phone/assets/licenses/NOTICE.txt
chmod 700 .local
"$tools/aapt2" link -I "$platform" --manifest phone/AndroidManifest.xml -A build/phone/assets -o build/phone/unsigned.apk
"$JAVA_HOME/bin/javac" --release 17 -Xlint:all -classpath "$platform:$jar" -d build/phone/classes phone/src/local/pixeltrackpad/*.java
find build/phone/classes -name '*.class' | LC_ALL=C sort > build/phone/classes.list
"$tools/d8" --lib "$platform" --min-api 26 --output build/phone/dex @build/phone/classes.list "$jar"
cp build/phone/unsigned.apk build/phone/dex.apk
touch -t 198001010000 build/phone/dex/classes*.dex
(cd build/phone/dex && zip -X -q ../dex.apk classes*.dex)
"$tools/zipalign" -f 4 build/phone/dex.apk build/phone/aligned.apk
if [ ! -f .local/android-debug.p12 ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore .local/android-debug.p12 -storepass android -keypass android -alias prototype -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Pixel Trackpad Local Prototype' > /dev/null 2>&1
    chmod 600 .local/android-debug.p12
fi
"$tools/apksigner" sign --ks .local/android-debug.p12 --ks-key-alias prototype --ks-pass pass:android --key-pass pass:android --out build/phone/pixel-trackpad.apk build/phone/aligned.apk
"$tools/apksigner" verify --verbose build/phone/pixel-trackpad.apk
"$tools/aapt2" dump badging build/phone/pixel-trackpad.apk
