#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17+)}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
tools="$sdk/build-tools/37.0.0"
platform="$sdk/platforms/android-37.0/android.jar"
mkdir -p build/phone/classes build/phone/dex .local
chmod 700 .local
"$tools/aapt2" link -I "$platform" --manifest phone/AndroidManifest.xml -o build/phone/unsigned.apk
"$JAVA_HOME/bin/javac" --release 17 -Xlint:all -classpath "$platform" -d build/phone/classes phone/src/local/pixeltrackpad/*.java
find build/phone/classes -name '*.class' > build/phone/classes.list
"$tools/d8" --lib "$platform" --min-api 26 --output build/phone/dex @build/phone/classes.list
cp build/phone/unsigned.apk build/phone/dex.apk
(cd build/phone/dex && zip -q ../dex.apk classes.dex)
"$tools/zipalign" -f 4 build/phone/dex.apk build/phone/aligned.apk
if [ ! -f .local/android-debug.p12 ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore .local/android-debug.p12 -storepass android -keypass android -alias prototype -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Pixel Trackpad Local Prototype' > /dev/null 2>&1
    chmod 600 .local/android-debug.p12
fi
"$tools/apksigner" sign --ks .local/android-debug.p12 --ks-key-alias prototype --ks-pass pass:android --key-pass pass:android --out build/phone/pixel-trackpad.apk build/phone/aligned.apk
"$tools/apksigner" verify --verbose build/phone/pixel-trackpad.apk
"$tools/aapt2" dump badging build/phone/pixel-trackpad.apk
