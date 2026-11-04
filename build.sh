#!/bin/sh
set -eu
cd "$(dirname "$0")"
case "${1:-}" in
    '') app="build/Pixel Trackpad.app" ;;
    --portable) app="build/release/Pixel Trackpad.app" ;;
    *) echo "Usage: $0 [--portable]" >&2; exit 2 ;;
esac
[ "$#" -le 1 ] || exit 2
if [ "${1:-}" = --portable ]; then
    runtime=build/runtime/cpython-3.13.13-macos-aarch64-none
    [ "$(uname -m)" = arm64 ] && [ -x "$runtime/bin/python3.13" ] &&
        [ "$(cat "$runtime/BUILD")" = 20260510 ] || {
        echo "Portable build requires the Apple Silicon CPython 3.13.13 runtime from 20260510." >&2
        exit 1
    }
fi
# Recreate generated bundle so development files cannot leak into the preview.
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" build/tmp
TMPDIR="$PWD/build/tmp"
export TMPDIR
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>GestureDiagnostic</string>
    <key>CFBundleIdentifier</key><string>local.pixeltrackpad.receiver</string>
    <key>CFBundleName</key><string>Pixel Trackpad</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>13.3</string>
    <key>NSLocalNetworkUsageDescription</key><string>Connect to your paired phone over the local network.</string>
    <key>NSBonjourServices</key><array><string>_pixeltrackpad._tcp</string></array>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST
xcrun --sdk macosx clang -fobjc-arc -Wall -Wextra -Werror -mmacosx-version-min=13.3 \
    -framework CoreImage -framework AppKit -framework ApplicationServices -framework Foundation -framework CoreFoundation -framework CoreGraphics \
    GestureDiagnostic.m -o "$app/Contents/MacOS/GestureDiagnostic"
resources="$app/Contents/Resources"
if [ "${1:-}" = --portable ]; then
    embedded="$resources/runtime"
    mkdir -p "$embedded/bin" "$embedded/lib"
    cp "$runtime/bin/python3.13" "$embedded/bin/"
    /usr/bin/rsync -a --exclude=__pycache__ --exclude=site-packages --exclude=ensurepip \
        --exclude=config-3.13-darwin --exclude='_sysconfigdata_*.py' --exclude='_tkinter.*' \
        "$runtime/lib/python3.13" "$embedded/lib/"
    cp licenses/python-build-standalone-20260510.rst "$embedded/"
    "$embedded/bin/python3.13" -I -B -c 'import ssl, fcntl, hashlib'
else
    python="${PYTHON3:-$(command -v python3)}"
    "$python" -c 'import sys; assert sys.version_info >= (3, 9), "Python 3.9+ required"'
    printf '%s' "$("$python" -c 'import sys; print(sys.executable)')" > "$resources/python-path"
fi
cp LICENSE NOTICE receiver.py onboarding.py "$resources/"
plutil -lint "$app/Contents/Info.plist"
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
printf 'Built %s\n' "$app"
