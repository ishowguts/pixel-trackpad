# Pixel Trackpad

Use an Android touchscreen as a wireless trackpad for a Mac on the same local network. The receiver starts in dry-run mode so you can pair and test the connection before allowing it to post input.

## Unsigned Apple Silicon preview

The portable Mac app is ad hoc signed for execution and has no Developer ID signature or notarization. It targets Apple Silicon and macOS 13.3 or later. It bundles CPython 3.13.13 with its license notices and uses macOS system tools and libraries. The Android APK is a debuggable development build.

## Controls and current limits

The touch surface provides relative pointer movement, primary/secondary taps, two-axis scrolling with momentum, and configurable three-finger dragging. Four-finger desktop swipes remain available when three-finger drag is enabled. Pointer gain, scroll gain, acceleration, physical surface size, and gesture sensitivity can be adjusted in Settings. The original Pixel 5 setup has been tested over Wi-Fi for pointer motion, scrolling, selection/dragging, desktop switching, and five simultaneous contacts.

Pinch, rotation, and smart-zoom event paths are included; application compatibility still needs wider testing. Mission Control is unresolved and vertical gestures are experimental. Show Desktop/Apps pinch-spread, Notification Centre edge gestures, Look Up, simultaneous pinch/rotation, one-finger tap-and-hold drag, and pressure/Force Touch equivalence are not complete. This is a development preview, not a feature-complete trackpad replacement.

## Build from source

- **Mac development build** — macOS 13.3 or later, Xcode Command Line Tools, and Python 3.9 or later. Run `./build.sh`, then open `build/Pixel Trackpad.app`. Set `PYTHON3` to choose the interpreter. The app bundles the receiver scripts and uses the interpreter selected at build time.
- **Mac portable build** — With the 20260510 Apple Silicon runtime at `build/runtime/cpython-3.13.13-macos-aarch64-none`, run `./build.sh --portable`. The output is `build/release/Pixel Trackpad.app`. An existing uv can fetch that runtime with `UV_CACHE_DIR="$PWD/build/runtime-cache" UV_PYTHON_INSTALL_DIR="$PWD/build/runtime" uv python install 3.13.13 --no-bin`. The build checks the runtime release before packaging.
- **Android** — Android 8.0 or later. Install JDK 17 or later and Android SDK Build Tools 37.0.0 and platform `android-37.0`. Run `./phone/build.sh` to produce `build/phone/pixel-trackpad.apk`. The script uses `JAVA_HOME` and `ANDROID_HOME` when set, or macOS `java_home` and `~/Library/Android/sdk` by default. The first build downloads the single ZXing core 3.5.4 jar from Maven Central into `build/phone-cache`; every build verifies its pinned SHA-256. Subsequent builds work offline with that cache. No Gradle or CameraX installation is required. The APK includes ZXing's license and notices.

The development APK uses a local signing key.

## Pair and connect

1. Put both devices on the same LAN. Open Pixel Trackpad on the Mac and select **Pair Phone**. If the Mac lists several addresses, choose the interface on the phone’s network.
2. Open Pixel Trackpad on the phone and tap **Scan QR**. Allow camera access when prompted, point the rear camera at the Mac's QR, then select **Continue** in the pairing confirmation.
3. Compare the six-digit codes on the phone and Mac. If they match, select **Approve** on the Mac. The QR expires after two minutes and accepts one request.
4. Select **Connect to Mac** on the phone. The phone remembers one Mac and tries a saved endpoint first when available. Scoped link-local endpoints are rediscovered with Bonjour on every connection. The receiver reuses its previous listener port when available. If a saved endpoint stops working, the phone tries fresh Bonjour discovery. If discovery is unavailable and the address or port changed, use **Forget Phone** on the Mac and pair again on a reachable network. **Disconnect / Cancel** on the Mac closes a session or cancels pairing. **Forget Phone** revokes reconnect access.

The Mac may ask for local network access. When a private IPv4 or IPv6 ULA endpoint is available, the QR includes it and can pair without Bonjour. Otherwise, Pair Phone shows a hostless invitation that requires Bonjour discovery. This supports IPv6-only LANs, including an iPhone hotspot, when Bonjour resolves a reachable scoped IPv6 link-local address. The CLAT address `192.0.0.2` is not a peer LAN endpoint and is excluded. Public IPv6 endpoints remain unsupported. Multicast filtering or client isolation can block discovery or connections; a QR alone does not prove reachability. Keep the invitation private.

**Scan QR** reads QR codes offline inside Pixel Trackpad, bypassing external Camera/Lens apps and their link defaults. It does not change Google's or Android's defaults. Camera permission is requested only when you tap **Scan QR**. Frames are processed in memory, never saved, logged, or transmitted. Invalid QR codes report an error locally and scanning continues. Cancel, leaving the app, camera interruption, or a valid scan closes the camera. Scanning disconnects any active trackpad session. Rear-camera and autofocus capabilities are optional; manually opening a pairing link in Pixel Trackpad remains supported without camera permission. Camera preview, focus, and permission behavior still need testing on physical phones.

The phone's **Settings** retain calibration and gesture preferences when you pair. To calibrate, measure the gray active surface in landscape and enter its width and height in millimeters. A previously configured private endpoint can still connect to the legacy receiver until you pair through the new window.

Connection attempts and sessions request low-latency Wi-Fi on Android 10+ (high-performance Wi-Fi on Android 8–9), trading extra energy use for potentially lower latency until disconnect or leaving the app.

To use live input, grant macOS Accessibility permission to Pixel Trackpad and explicitly select **Enable live input for this launch** on the Mac. Each launch starts in dry run. Continuous gesture support depends on macOS and the target app. Vertical system gestures remain experimental. Notarization, Android release signing and configuration, and wider phone, Mac, and network testing remain release gates. The receiver supports one remembered phone at a time.

## Checks

Run `python3 check_phone.py` and `python3 check_onboarding.py` after building the Mac app. These checks use loopback TLS, disposable pairing state, and the dry native event bridge. They need permission to open a local socket and launch the app helper.

For a portable copy, pass its path to `python3 check_onboarding.py --bundle "/path/to/Pixel Trackpad.app"`.

After the Android build, run `./phone/check.sh` for an offline JVM check of generated invitation QR decoding, rotated and padded camera planes, and rejection through the shared invitation parser. Run the existing `adb shell am instrument -w local.pixeltrackpad/.MotionCheck` on a connected phone to check motion queuing and actual Wi-Fi lock acquisition, release, and repeated cleanup without opening a network connection or posting input; a held lock does not prove OS low-latency activation or smoother scrolling.

On a phone, check camera permission grant, denial, and retry; Scan QR cancellation and Home/lock while opening or scanning; repeated scans; another app taking the camera; upright preview with correct proportions and focus; and invalid QR codes remaining in the scanner. Verify the camera privacy indicator clears after exit. A valid Mac QR must still show confirmation, compare the six-digit codes, and require Mac approval before saving pairing. Confirm manual links, remembered reconnect, settings, and touch input still work. JVM checks do not validate camera hardware or visible behavior.

## License

[MIT](LICENSE). Retain the third-party attribution in [NOTICE](NOTICE), the [ZXing license](phone/licenses/ZXing-LICENSE.txt) and [notices](phone/licenses/ZXing-NOTICE.txt), and the bundled runtime license notices. ZXing core 3.5.4 is Apache-2.0 licensed.
