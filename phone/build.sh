#!/bin/sh
set +x
set -eu
umask 077
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17+)}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
tools="$sdk/build-tools/37.0.0"
platform="$sdk/platforms/android-37.0/android.jar"
profile=debug
case "${1:-}" in
    '') ;;
    --release) profile=release ;;
    --setup-release-key) profile=setup ;;
    *) echo "Usage: $0 [--release | --setup-release-key]" >&2; exit 2 ;;
esac
[ "$#" -le 1 ] || exit 2
signing=.local/android-release
if [ "$profile" = setup ]; then
    mkdir -p .local
    # Refuse even a partial identity: restore the original backup rather than replacing it.
    mkdir "$signing" || { echo "Release signing directory already exists. Restore missing files from backup." >&2; exit 1; }
    chmod 700 "$signing"
    python3 -I - "$signing/password" <<'PASSWORD'
import os, secrets, sys
with os.fdopen(os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
    output.write(secrets.token_urlsafe(48) + '\n')
PASSWORD
    "$JAVA_HOME/bin/keytool" -genkeypair -storetype PKCS12 -keystore "$signing/signing.p12" \
        -storepass:file "$signing/password" -keypass:file "$signing/password" \
        -alias pixel-trackpad -keyalg RSA -keysize 3072 -validity 10000 \
        -dname 'CN=Pixel Trackpad Android Release' > /dev/null 2>&1
    chmod 600 "$signing/signing.p12" "$signing/password"
    echo "Permanent release key created. Make a protected offline backup before wider distribution."
    exit
fi
if [ "$profile" = release ]; then
    # Inspect metadata only; passwords and private key bytes never enter the shell.
    python3 -I - "$signing" <<'IDENTITY'
import os, pathlib, stat, sys
root = pathlib.Path(sys.argv[1])
for path, mode in ((root, 0o700), (root/'signing.p12', 0o600), (root/'password', 0o600)):
    try:
        info = path.lstat()
    except FileNotFoundError:
        raise SystemExit('Release identity missing. Run --setup-release-key once, or restore the original backup.')
    if stat.S_ISLNK(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != mode:
        raise SystemExit('Release identity must be owned by this user with directory 0700 and files 0600.')
    if path != root and (not stat.S_ISREG(info.st_mode) or info.st_size == 0):
        raise SystemExit('Release identity incomplete. Restore the original backup.')
IDENTITY
fi
. ./phone/version.conf
version_code="${ANDROID_VERSION_CODE:-$VERSION_CODE}"
version_name="${ANDROID_VERSION_NAME:-$VERSION_NAME}"
python3 -I - "$version_code" "$version_name" <<'VERSION'
import re, sys
assert re.fullmatch(r'[1-9][0-9]*', sys.argv[1]) and int(sys.argv[1]) <= 2100000000, 'Invalid version code'
assert re.fullmatch(r'[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[A-Za-z0-9.-]+)?', sys.argv[2]), 'Invalid version name'
VERSION
out=build/phone
[ "$profile" != release ] || out=build/phone-release
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
rm -rf "$out"
mkdir -p "$out/classes" "$out/dex" "$out/assets/licenses"
cp phone/licenses/*.txt "$out/assets/licenses/"
cp NOTICE "$out/assets/licenses/NOTICE.txt"
python3 -I phone/apk.py manifest "$profile" "$out/AndroidManifest.xml"
"$tools/aapt2" link -I "$platform" --manifest "$out/AndroidManifest.xml" \
    --version-code "$version_code" --version-name "$version_name" -A "$out/assets" -o "$out/unsigned.apk"
find phone/src -name '*.java' | LC_ALL=C sort > "$out/sources.list"
if [ "$profile" = release ]; then
    sed '/MotionCheck.java$/d' "$out/sources.list" > "$out/release-sources.list"
    mv "$out/release-sources.list" "$out/sources.list"
fi
"$JAVA_HOME/bin/javac" --release 17 -Xlint:all -classpath "$platform:$jar" -d "$out/classes" @"$out/sources.list"
find "$out/classes" -name '*.class' | LC_ALL=C sort > "$out/classes.list"
"$tools/d8" --lib "$platform" --min-api 26 --output "$out/dex" @"$out/classes.list" "$jar"
cp "$out/unsigned.apk" "$out/dex.apk"
touch -t 198001010000 "$out"/dex/classes*.dex
(cd "$out/dex" && zip -X -q ../dex.apk classes*.dex)
"$tools/zipalign" -f 4 "$out/dex.apk" "$out/aligned.apk"
if [ "$profile" = release ]; then
    "$tools/apksigner" sign --ks "$signing/signing.p12" --ks-key-alias pixel-trackpad \
        --ks-pass "file:$signing/password" \
        --out "$out/pixel-trackpad.apk" "$out/aligned.apk"
else
    if [ ! -f .local/android-debug.p12 ]; then
        "$JAVA_HOME/bin/keytool" -genkeypair -keystore .local/android-debug.p12 -storepass android -keypass android -alias prototype -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Pixel Trackpad Local Prototype' > /dev/null 2>&1
        chmod 600 .local/android-debug.p12
    fi
    "$tools/apksigner" sign --ks .local/android-debug.p12 --ks-key-alias prototype --ks-pass pass:android --key-pass pass:android --out build/phone/pixel-trackpad.apk build/phone/aligned.apk
fi
"$tools/apksigner" verify --verbose --print-certs "$out/pixel-trackpad.apk"
python3 -I phone/apk.py verify "$tools/aapt2" "$out/pixel-trackpad.apk" "$profile" "$version_code" "$version_name"
printf 'Built %s\n' "$out/pixel-trackpad.apk"
