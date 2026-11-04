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
- **Android** — Android 8.0 or later. Install JDK 17 or later and Android SDK Build Tools 37.0.0 and platform `android-37.0`. Run `./phone/build.sh` to produce `build/phone/pixel-trackpad.apk`. The script uses `JAVA_HOME` and `ANDROID_HOME` when set, or macOS `java_home` and `~/Library/Android/sdk` by default.

The development APK uses a local signing key.

## Pair and connect

1. Put both devices on the same LAN. Open Pixel Trackpad on the Mac and select **Pair Phone**. If the Mac lists several addresses, choose the interface on the phone’s network.
2. Scan the QR with a phone camera or QR reader that can open custom links. Open the result in Pixel Trackpad and select **Continue**.
3. Compare the six-digit codes on the phone and Mac. If they match, select **Approve** on the Mac. The QR expires after two minutes and accepts one request.
4. Select **Connect to Mac** on the phone. The phone remembers one Mac and tries the saved endpoint first. The receiver reuses its previous listener port when available. If the endpoint stops working, the phone tries fresh Bonjour discovery. If discovery is unavailable and the address or port changed, use **Forget Phone** on the Mac and pair again. **Disconnect / Cancel** on the Mac closes a session or cancels pairing. **Forget Phone** revokes reconnect access.

The Mac may ask for local network access. The QR contains a private IPv4 or IPv6 ULA address and can pair without Bonjour. Guest Wi-Fi or client isolation can block direct connections as well as discovery. If your camera only displays the QR contents, use a local reader that can open the link in the app. Keep the invitation private.

The phone's **Settings** retain calibration and gesture preferences when you pair. To calibrate, measure the gray active surface in landscape and enter its width and height in millimeters. A previously configured private endpoint can still connect to the legacy receiver until you pair through the new window.

To use live input, grant macOS Accessibility permission to Pixel Trackpad and explicitly select **Enable live input for this launch** on the Mac. Each launch starts in dry run. Continuous gesture support depends on macOS and the target app. Vertical system gestures remain experimental. Notarization, Android release signing and configuration, and wider phone, Mac, and network testing remain release gates. The receiver supports one remembered phone at a time.

## Checks

Run `python3 check_phone.py` and `python3 check_onboarding.py` after building the Mac app. These checks use loopback TLS, disposable pairing state, and the dry native event bridge. They need permission to open a local socket and launch the app helper.

For a portable copy, pass its path to `python3 check_onboarding.py --bundle "/path/to/Pixel Trackpad.app"`.

## License

[MIT](LICENSE). Retain the third-party attribution in [NOTICE](NOTICE) and the bundled runtime license notices.
