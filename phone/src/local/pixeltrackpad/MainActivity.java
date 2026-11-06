package local.pixeltrackpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.security.cert.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Native touch capture only. All recognition and calibration happens on the Mac. */
public class MainActivity extends Activity {
    private TextView status;
    private LinearLayout layout, controls;
    private FrameLayout touchArea;
    Button controlsButton, fullScreenButton;
    boolean fullScreen;
    private Object exitFullScreen;
    final Reconnect reconnect = new Reconnect();
    private final Runnable retryConnection = () -> { if (reconnect.retry()) start(); };
    private Button connect;
    private AlertDialog invitationDialog;
    private QrScanner scanner;
    private boolean manualNext;
    private boolean resumed, scanPermissionPending, scanWhenResumed;
    private static final int CAMERA_PERMISSION = 7;
    Surface surface;
    volatile Link link;
    private int maximum;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final String SERVICE_TYPE = "_pixeltrackpad._tcp.";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AtomicFile storage() {
        return new AtomicFile(new File(getFilesDir(), "pairing.json"));
    }

    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        reconnect.wanted = getPreferences(MODE_PRIVATE).getBoolean("connectWanted", true);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 12, 24, 24);
        controls = new LinearLayout(this);
        connect = new Button(this); connect.setText("Connect");
        connect.setOnClickListener(v -> {
            if (link != null || reconnect.pending) stop();
            else { manualNext = true; reconnect.connect(); rememberIntent(); start(); }
        });
        Button settings = new Button(this); settings.setText("Settings");
        settings.setOnClickListener(v -> edit());
        Button scan = new Button(this); scan.setText("Scan QR");
        scan.setOnClickListener(v -> scan());
        status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("Disconnected. Maximum contacts: 0");
        fullScreenButton = new Button(this); fullScreenButton.setText("Full screen");
        fullScreenButton.setEnabled(false);
        fullScreenButton.setOnClickListener(v -> setFullScreen(true));
        controls.addView(connect); controls.addView(settings); controls.addView(scan);
        controls.addView(fullScreenButton);
        controls.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(controls);
        touchArea = new FrameLayout(this);
        surface = new Surface();
        touchArea.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        controlsButton = new Button(this); controlsButton.setText("Controls");
        controlsButton.setContentDescription("Show trackpad controls");
        controlsButton.setMinHeight(dp(48)); controlsButton.setMinWidth(dp(80));
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        corner.setMargins(dp(8), dp(8), dp(8), dp(8));
        touchArea.addView(controlsButton, corner);
        controlsButton.setVisibility(View.GONE);
        controlsButton.setOnClickListener(v -> setFullScreen(false));
        layout.addView(touchArea, new LinearLayout.LayoutParams(-1, 0, 1));
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            if (fullScreen) {
                int left = 0, top = 0, right = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets safe = insets.getInsetsIgnoringVisibility(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    // Keep navigation gestures and display cutouts outside the active surface.
                    left = safe.left; right = safe.right; bottom = safe.bottom;
                    if (insets.getDisplayCutout() != null) top = insets.getDisplayCutout().getSafeInsetTop();
                } else {
                    left = insets.getStableInsetLeft(); right = insets.getStableInsetRight(); bottom = insets.getStableInsetBottom();
                    if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null)
                        top = insets.getDisplayCutout().getSafeInsetTop();
                }
                layout.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        setContentView(layout);
        updateConnect();
        if (state == null) acceptInvitation(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        acceptInvitation(intent);
    }

    private void acceptInvitation(Intent intent) {
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;
        stop();
        String uri = intent.getDataString();
        setIntent(new Intent(this, MainActivity.class));
        acceptInvitation(uri);
    }

    private boolean acceptInvitation(String uri) {
        final JSONObject pairing;
        final String invitation;
        try {
            Invitation parsed = Invitation.parse(uri);
            pairing = new JSONObject().put("fingerprint", parsed.fingerprint).put("bonjour", true);
            invitation = parsed.token;
            if (parsed.host != null) {
                pairing.put("host", parsed.host).put("port", Integer.parseInt(parsed.port));
            }
        } catch (Exception error) {
            status.setText("Invalid pairing link.");
            return false;
        }
        closeScanner();
        String pin = pairing.optString("fingerprint");
        stop();
        if (invitationDialog != null) invitationDialog.dismiss();
        invitationDialog = new AlertDialog.Builder(this).setTitle("Pair with Mac " + pin.substring(18, 24) + "?")
                .setMessage("Continue only if you scanned the invitation on your Mac. Compare the code shown on both screens before approving.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, button) -> {
                    try {
                        begin(new Link(pairing, invitation));
                    } catch (Exception error) {
                        status.setText("Could not start pairing.");
                    }
                }).show();
        return true;
    }

    private void scan() {
        if (scanner != null || scanPermissionPending) return;
        stop();
        if (invitationDialog != null) invitationDialog.dismiss();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            scanPermissionPending = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else openScanner();
    }

    private void openScanner() {
        if (resumed && scanner == null) scanner = new QrScanner(this, this::acceptInvitation, () -> scanner = null);
    }

    private void closeScanner() {
        if (scanner != null) scanner.close();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != CAMERA_PERMISSION) return;
        if (!scanPermissionPending) return;
        scanPermissionPending = false;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            scanWhenResumed = !resumed;
            if (resumed) openScanner();
        }
        else status.setText("Camera permission denied. Tap Scan QR to retry, or open a pairing link manually.");
    }

    private JSONObject read() throws Exception {
        try {
            byte[] bytes;
            try (FileInputStream input = storage().openRead()) {
                if (input.getChannel().size() > 8192) throw new IOException("Pairing file too large");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (data.size() + count > 8192) throw new IOException("Pairing file too large");
                    data.write(buffer, 0, count);
                }
                bytes = data.toByteArray();
            }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (FileNotFoundException missing) {
            if (storage().getBaseFile().exists()) throw missing;
            return new JSONObject();
        }
    }

    private void save(JSONObject value) throws IOException {
        AtomicFile file = storage();
        FileOutputStream output = file.startWrite();
        try {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }

    private void updateConnect() {
        try {
            JSONObject pairing = read();
            if (pairing.has("bonjour")) {
                String pin = pairing.getString("fingerprint");
                connect.setText(pin.matches("[0-9a-f]{64}") ? "Connect to Mac " + pin.substring(18, 24) : "Connect");
            } else {
                connect.setText("Connect");
            }
        } catch (Exception error) {
            connect.setText("Connect");
        }
    }

    private void edit() {
        stop();
        try {
            JSONObject original = read();
            JSONObject config = original.optJSONObject("settings");
            if (config == null) config = new JSONObject();
            LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
            String[] keys = {"width", "height", "gain", "scroll", "pinch", "rotation"};
            String[] labels = {"Active surface width (mm)", "Active surface height (mm)",
                "Pointer gain (points/mm, presets 3 / 6 / 12)", "Scroll gain (points/mm)", "Pinch sensitivity", "Rotation sensitivity"};
            String[] defaults = {"130", "60", "6", "3", "1", "1"};
            EditText[] inputs = new EditText[keys.length];
            for (int i = 0; i < keys.length; i++) {
                TextView label = new TextView(this); label.setText(labels[i]); fields.addView(label);
                EditText input = new EditText(this); inputs[i] = input;
                input.setSingleLine(true); input.setContentDescription(labels[i]);
                input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
                input.setText(config.optString(keys[i], defaults[i])); fields.addView(input);
            }
            String[] flags = {"acceleration", "natural", "drag", "threeDrag", "vertical"};
            String[] names = {"Receiver acceleration", "Natural scroll", "Explicit one-finger drag mode", "Three-finger drag (disables three-finger swipes)",
                "Experimental vertical gestures (Mission Control unresolved; App Exposé unverified)"};
            CheckBox[] switches = new CheckBox[flags.length];
            for (int i = 0; i < flags.length; i++) {
                switches[i] = new CheckBox(this); switches[i].setText(names[i]);
                switches[i].setChecked(config.optBoolean(flags[i], i == 1)); fields.addView(switches[i]);
            }
            TextView note = new TextView(this);
            note.setText("With the toolbar visible, measure the grey active area in landscape and enter its width and height in mm. Full screen scales this calibration to the larger surface. Gains stay the same. Save while disconnected.");
            fields.addView(note);
            ScrollView scroll = new ScrollView(this); scroll.addView(fields);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Trackpad settings").setView(scroll)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    JSONObject options = original.optJSONObject("settings");
                    options = options == null ? new JSONObject() : new JSONObject(options.toString());
                    for (int i = 0; i < keys.length; i++) {
                        String text = inputs[i].getText().toString().trim();
                        options.put(keys[i], Double.parseDouble(text));
                    }
                    for (int i = 0; i < flags.length; i++) options.put(flags[i], switches[i].isChecked());
                    validateSettings(options);
                    JSONObject latest = read();
                    latest.put("settings", options);
                    save(latest);
                    dialog.dismiss(); status.setText("Saved privately. Disconnected.");
                } catch (Exception error) { Toast.makeText(this, "Could not save settings. Check numeric ranges.", Toast.LENGTH_LONG).show(); }
            }));
            dialog.show();
        } catch (Exception error) { status.setText("Could not read saved settings."); }
    }

    private static InetSocketAddress savedEndpoint(JSONObject pairing, boolean legacy) throws Exception {
        return Invitation.endpoint(pairing.getString("host"), pairing.getString("port"), legacy);
    }

    private static void rememberEndpoint(JSONObject pairing, InetSocketAddress address) throws Exception {
        pairing.remove("host");
        pairing.remove("port");
        if (address.getAddress().isLinkLocalAddress()) return; // Scoped addresses must be rediscovered.
        pairing.put("host", address.getAddress().getHostAddress().split("%", 2)[0]).put("port", address.getPort());
        savedEndpoint(pairing, false);
    }

    private static void validateSettings(JSONObject options) throws Exception {
        if (options != null) {
            String[] keys = {"width", "height", "gain", "scroll", "pinch", "rotation"};
            double[] low = {30, 20, .1, .1, .1, .1}, high = {300, 200, 30, 20, 5, 5};
            for (int i = 0; i < keys.length; i++) {
                if (!options.has(keys[i])) continue;
                double value = options.getDouble(keys[i]);
                if (!Double.isFinite(value) || value < low[i] || value > high[i]) throw new IOException("Setting out of range");
            }
        }
    }

    private void start() {
        if (!reconnect.begin()) return;
        try {
            JSONObject pairing = read();
            if (!pairing.getString("fingerprint").matches("[0-9a-fA-F]{64}") ||
                    !pairing.getString("secret").matches("[0-9a-f]{64}")) throw new IOException("Invalid pairing");
            validateSettings(pairing.optJSONObject("settings"));
            Link next = new Link(pairing, null);
            next.automatic = !manualNext;
            manualNext = false;
            begin(next);
        } catch (Exception error) {
            stop();
            status.setText("No usable pairing. Show an invitation on the Mac and scan it with your phone.");
        }
    }

    private void begin(Link next) throws Exception {
        closeSession();
        if (!resumed) { reconnect.active = false; return; }
        reconnect.active = true;
        link = next;
        next.wifiLock = acquireWifiLock(getApplicationContext());
        status.setText("Finding Mac…");
        connect.setText("Disconnect");
        try {
            boolean legacy = !next.pairing.optBoolean("bonjour");
            if (next.pairing.has("host") || legacy) next.startNetwork(savedEndpoint(next.pairing, legacy));
            else next.discover();
        } catch (Exception error) {
            next.fail("Could not start connection.");
        }
    }

    private void rememberIntent() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("connectWanted", reconnect.wanted).apply();
    }

    void stop() {
        manualNext = false;
        reconnect.disconnect();
        rememberIntent();
        handler.removeCallbacks(retryConnection);
        closeSession();
    }

    private void closeSession() {
        Link previous = link; link = null;
        if (previous != null) {
            previous.close();
            if (previous.discovery != null) previous.stopDiscovery(previous.discovery);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (surface != null) { surface.capturing = false; surface.invalidate(); }
        setFullScreen(false);
        if (fullScreenButton != null) fullScreenButton.setEnabled(false);
        if (status != null) { status.setText("Disconnected. Maximum contacts: " + maximum); updateConnect(); }
    }

    private void suspendConnection() {
        manualNext = false;
        reconnect.suspend();
        handler.removeCallbacks(retryConnection);
        closeSession();
    }

    private void resumeConnection() {
        reconnect.foreground = resumed && hasWindowFocus();
        if (reconnect.foreground) start();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("deprecation")
    void setFullScreen(boolean enabled) {
        Link current = link;
        enabled &= current != null && current.ready && current.surfaceScale;
        if (fullScreen == enabled) return;
        surface.cancelGesture();
        fullScreen = enabled;
        if (Build.VERSION.SDK_INT >= 33) {
            if (enabled) exitFullScreen = BackNavigation.register(this);
            else { BackNavigation.unregister(this, exitFullScreen); exitFullScreen = null; }
        }
        controls.setVisibility(enabled ? View.GONE : View.VISIBLE);
        controlsButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        layout.setPadding(enabled ? 0 : 24, enabled ? 0 : 12, enabled ? 0 : 24, enabled ? 0 : 24);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (enabled) bars.hide(WindowInsets.Type.systemBars());
                else bars.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(enabled ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        layout.requestApplyInsets();
    }

    // Keep the Android 13 callback class uninitialized on Android 8–12.
    private static final class BackNavigation {
        static Object register(MainActivity activity) {
            OnBackInvokedCallback callback = () -> activity.setFullScreen(false);
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(MainActivity activity, Object callback) {
            if (callback != null) activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (fullScreen) setFullScreen(false);
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (scanWhenResumed) { scanWhenResumed = false; openScanner(); }
        resumeConnection();
    }
    @Override protected void onPause() { resumed = false; closeScanner(); suspendConnection(); super.onPause(); }
    @Override protected void onStop() { scanPermissionPending = false; scanWhenResumed = false; super.onStop(); }
    @Override protected void onDestroy() { closeScanner(); suspendConnection(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) resumeConnection();
        else suspendConnection();
    }

    // HIGH_PERF is used only on Android 8–9, before LOW_LATENCY was available.
    @SuppressWarnings("deprecation")
    static WifiManager.WifiLock acquireWifiLock(Context context) {
        WifiManager.WifiLock lock = null;
        try {
            WifiManager manager = context.getApplicationContext().getSystemService(WifiManager.class);
            if (manager == null) return null;
            lock = manager.createWifiLock(Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "PixelTrackpad:connection");
            lock.setReferenceCounted(false);
            lock.acquire();
            if (lock.isHeld()) return lock;
        } catch (RuntimeException ignored) {
            // This optional optimization must not prevent pairing or input.
        }
        releaseWifiLock(lock);
        return null;
    }

    static void releaseWifiLock(WifiManager.WifiLock lock) {
        try { if (lock != null && lock.isHeld()) lock.release(); }
        catch (RuntimeException ignored) {
            // A Wi-Fi service failure must not interrupt connection cleanup.
        }
    }

    static JSONObject nextFrame(ArrayBlockingQueue<JSONObject> queue) throws InterruptedException {
        JSONObject frame = queue.poll(250, TimeUnit.MILLISECONDS);
        // latest queued moves reduce stale replay; this does not hide Wi-Fi latency spikes.
        while (frame != null && frame.optBoolean("_move") && queue.peek() != null && queue.peek().optBoolean("_move")) {
            frame = queue.remove();
        }
        if (frame != null) frame.remove("_move");
        return frame;
    }

    final class Surface extends View {
        boolean capturing;
        private int referenceWidth, referenceHeight;
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            cancelGesture();
            if (!fullScreen && width > 0 && height > 0) { referenceWidth = width; referenceHeight = height; }
        }
        void cancelGesture() {
            capturing = false;
            Link current = link;
            if (current == null || !current.ready) return;
            try {
                current.queue.clear();
                if (!current.queue.offer(new JSONObject().put("type", "cancel").put("t", SystemClock.uptimeMillis())))
                    current.fail("Input queue full. Reconnecting…");
            } catch (Exception error) { current.fail("Could not cancel input. Reconnecting…"); }
        }
        Surface() { super(MainActivity.this); setContentDescription("Trackpad touch surface. Connect first. Use the Controls button or toolbar to disconnect or change settings."); setFocusable(true); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(45, 48, 54)); paint.setColor(Color.WHITE); paint.setTextSize(28);
            canvas.drawText("Trackpad", 24, 48, paint);
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onTouchEvent(MotionEvent event) {
            Link current = link;
            if (current == null || !current.ready) { capturing = false; return true; }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) capturing = true;
            if (!capturing) return true;
            if (action == MotionEvent.ACTION_CANCEL) { cancelGesture(); return true; }
            Rect button = new Rect();
            controlsButton.getHitRect(button);
            for (int i = 0; i < event.getPointerCount(); i++) {
                float x = event.getX(i), y = event.getY(i);
                if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || x >= getWidth() || y < 0 || y >= getHeight()
                        || (fullScreen && button.contains((int) x, (int) y))) {
                    cancelGesture(); return true;
                }
            }
            if (referenceWidth <= 0 || referenceHeight <= 0) { cancelGesture(); return true; }
            maximum = Math.max(maximum, event.getPointerCount());
            status.setText("Connected. Maximum contacts: " + maximum);
            try {
                JSONArray contacts = new JSONArray();
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (action == MotionEvent.ACTION_UP || (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex())) continue;
                    JSONObject contact = new JSONObject(); contact.put("id", event.getPointerId(i));
                    contact.put("x", Math.max(0., Math.min(1., event.getX(i) / getWidth())));
                    contact.put("y", Math.max(0., Math.min(1., event.getY(i) / getHeight()))); contacts.put(contact);
                }
                JSONObject frame = new JSONObject(); frame.put("type", "sample");
                frame.put("t", event.getEventTime()); frame.put("contacts", contacts);
                if (current.surfaceScale) frame.put("surface", new JSONArray().put((double) getWidth() / referenceWidth)
                        .put((double) getHeight() / referenceHeight));
                frame.put("_move", action == MotionEvent.ACTION_MOVE);
                if (!current.queue.offer(frame)) current.fail("Input queue full. Reconnecting…"); // Overflow cancels rather than dropping a transition.
                if (action == MotionEvent.ACTION_UP) { capturing = false; performClick(); }
            } catch (Exception error) { current.fail("Could not capture input. Reconnecting…"); }
            return true;
        }
    }

    private static boolean localAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc);
    }

    final class Link implements Runnable {
        final JSONObject pairing;
        final String invitation;
        final NsdManager nsd = getSystemService(NsdManager.class);
        NsdManager.DiscoveryListener discovery;
        InetSocketAddress endpoint;
        boolean usedDiscovery, stopSupported, surfaceScale, terminal;
        boolean automatic = true;
        private Thread worker;
        private final Runnable discoveryTimeout = () -> {
            if (link == this && !this.closed && endpoint == null) fail("Mac not found. Check Wi-Fi and reopen the receiver.");
        };
        final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(64);
        volatile boolean closed, ready;
        volatile Socket socket;
        volatile AckWindow acknowledgements;
        WifiManager.WifiLock wifiLock;
        Link(JSONObject pairing, String invitation) {
            this.pairing = pairing;
            this.invitation = invitation;
        }

        void stopDiscovery(NsdManager.DiscoveryListener listener) {
            try { nsd.stopServiceDiscovery(listener); } catch (IllegalArgumentException ignored) {}
            if (discovery == listener) discovery = null;
        }

        void discover() throws Exception {
            usedDiscovery = true;
            endpoint = null;
            status.setText("Finding Mac with Bonjour…");
            String name = "Pixel Trackpad " + pairing.getString("fingerprint").substring(0, 24);
            discovery = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String type) {
                    runOnUiThread(() -> { if (closed) stopDiscovery(this); });
                }
                @Override public void onServiceLost(NsdServiceInfo info) {}
                @Override public void onDiscoveryStopped(String type) {}
                @Override public void onStopDiscoveryFailed(String type, int error) {}
                @Override public void onStartDiscoveryFailed(String type, int error) {
                    runOnUiThread(() -> { if (link == Link.this) fail("Bonjour discovery unavailable."); });
                }
                @Override public void onServiceFound(NsdServiceInfo info) {
                    if (!name.equals(info.getServiceName())) return;
                    runOnUiThread(() -> {
                        if (closed || discovery != this) return;
                        stopDiscovery(this);
                        resolve(info);
                    });
                }
            };
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
            handler.removeCallbacks(discoveryTimeout);
            handler.postDelayed(discoveryTimeout, 10000);
        }

        // Resolve once per connection so the saved pairing survives endpoint changes.
        @SuppressWarnings("deprecation")
        void resolve(NsdServiceInfo service) {
            try {
                nsd.resolveService(service, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                        runOnUiThread(() -> { if (link == Link.this) fail("Could not resolve Mac. Try again."); });
                    }
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        runOnUiThread(() -> {
                            if (closed) return;
                            InetAddress host = null;
                            if (Build.VERSION.SDK_INT >= 34) {
                                for (InetAddress candidate : info.getHostAddresses()) {
                                    if (!localAddress(candidate)) continue;
                                    host = candidate;
                                    if (candidate instanceof Inet4Address) break;
                                }
                            } else {
                                host = info.getHost();
                            }
                            if (host == null || !localAddress(host) || info.getPort() < 1024 || info.getPort() > 65535) {
                                fail("Mac did not advertise a usable LAN endpoint.");
                                return;
                            }
                            startNetwork(new InetSocketAddress(host, info.getPort()));
                        });
                    }
                });
            } catch (RuntimeException error) {
                fail("Could not resolve Mac. Try again.");
            }
        }

        void fail(String message) {
            if (link != this || closed) return;
            boolean retryable = invitation == null && !terminal && (!ready || stopSupported)
                    && (acknowledgements == null || !acknowledgements.remoteStopped);
            if (acknowledgements != null && acknowledgements.remoteStopped) message = "Stopped on Mac.";
            if (terminal) message = "Connection declined. Check the Mac and tap Connect.";
            closeSession();
            long delay = reconnect.failed(retryable);
            rememberIntent();
            status.setText(delay < 0 ? message + " Tap Connect to retry." : message + " Retrying…");
            if (delay >= 0) {
                connect.setText("Disconnect");
                handler.removeCallbacks(retryConnection);
                handler.postDelayed(retryConnection, delay);
            }
        }

        void startNetwork(InetSocketAddress address) {
            if (closed || link != this || (worker != null && worker.isAlive())) return;
            handler.removeCallbacks(discoveryTimeout);
            endpoint = address;
            status.setText("Connecting securely…");
            worker = new Thread(this, "trackpad-network");
            worker.start();
        }

        void close() {
            closed = true;
            ready = false;
            handler.removeCallbacks(discoveryTimeout);
            if (worker != null) worker.interrupt();
            releaseWifiLock(wifiLock);
            queue.clear();
            if (acknowledgements != null) acknowledgements.close();
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }

        void finishPairing(SSLSocket secure, InputStream input, OutputStream output) throws Exception {
            String code = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
            send(output, new JSONObject().put("type", "pair").put("invite", invitation).put("code", code));
            if (!"pending".equals(response(input).optString("type"))) throw new IOException("Invitation rejected");
            runOnUiThread(() -> {
                if (link == this) status.setText("Compare code " + code + " with your Mac, then approve on the Mac.");
            });
            secure.setSoTimeout(32000);
            JSONObject reply = response(input);
            String secret = reply.getString("secret");
            if (!"paired".equals(reply.optString("type")) || !secret.matches("[0-9a-f]{64}"))
                throw new IOException("Pairing rejected");
            runOnUiThread(() -> {
                if (link != this) return;
                try {
                    JSONObject original = read();
                    original.put("bonjour", true).put("fingerprint", pairing.getString("fingerprint")).put("secret", secret);
                    rememberEndpoint(original, endpoint);
                    save(original);
                    stop();
                    status.setText("Paired. Select the remembered Mac to connect.");
                } catch (Exception error) {
                    fail("Could not save pairing. Forget the phone on the Mac and retry.");
                }
            });
        }

        JSONObject response(InputStream input) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != '\n') {
                if (next == -1 || bytes.size() >= 256) throw new IOException("Invalid response");
                bytes.write(next);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        }
        @Override public void run() {
            try {
                byte[] pin = new byte[32]; String hex = pairing.getString("fingerprint");
                for (int i = 0; i < pin.length; i++) pin[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
                X509TrustManager trust = new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Client certificates unsupported"); }
                    public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                        try {
                            if (chain == null || chain.length == 0) throw new CertificateException("Missing certificate");
                            chain[0].checkValidity();
                            if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))
                                throw new CertificateException("Certificate fingerprint mismatch");
                        } catch (CertificateException error) { throw error; }
                        catch (Exception error) { throw new CertificateException("Certificate rejected", error); }
                    }
                };
                SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, new TrustManager[]{trust}, null);
                Socket raw = new Socket(); socket = raw;
                raw.setTcpNoDelay(true);
                if (closed) throw new IOException("Closed");
                raw.connect(endpoint, 3000);
                SSLSocket secure = (SSLSocket) tls.getSocketFactory().createSocket(raw, endpoint.getAddress().getHostAddress(), endpoint.getPort(), true);
                socket = secure; secure.setSoTimeout(3000); secure.setEnabledProtocols(new String[]{"TLSv1.2"});
                secure.startHandshake();
                if (closed) throw new IOException("Closed");
                OutputStream output = secure.getOutputStream();
                InputStream input = secure.getInputStream();
                if (invitation != null) {
                    finishPairing(secure, input, output);
                    return;
                }
                JSONObject hello = new JSONObject(); hello.put("type", "hello"); hello.put("version", 1);
                hello.put("recovery", true); hello.put("automatic", automatic);
                hello.put("secret", pairing.getString("secret")); hello.put("t", SystemClock.uptimeMillis());
                hello.put("settings", pairing.optJSONObject("settings") == null ? new JSONObject() : pairing.getJSONObject("settings"));
                send(output, hello);
                JSONObject welcome = response(input);
                if (!"ready".equals(welcome.optString("type"))) { terminal = true; throw new IOException("Pairing rejected"); }
                stopSupported = welcome.optBoolean("stop");
                surfaceScale = welcome.optBoolean("surfaceScale");
                ready = true;
                runOnUiThread(() -> {
                    if (link != this || closed) return;
                    if (usedDiscovery) {
                        try {
                            JSONObject original = read();
                            rememberEndpoint(original, endpoint);
                            save(original);
                        } catch (Exception ignored) {
                            // The current authenticated session can continue without updating the cache.
                        }
                    }
                    reconnect.ready();
                    surface.capturing = false;
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    fullScreenButton.setEnabled(surfaceScale);
                    setFullScreen(true);
                    status.setText(surfaceScale ? "Connected. Maximum contacts: " + maximum
                            : "Connected. Update the Mac receiver for full screen and automatic recovery.");
                });
                acknowledgements = new AckWindow(secure, stopSupported);
                if (closed) acknowledgements.close();
                long sequence = 0;
                while (!closed) {
                    acknowledgements.awaitCapacity();
                    JSONObject frame = nextFrame(queue);
                    if (frame == null) { frame = new JSONObject(); frame.put("type", "heartbeat"); frame.put("t", SystemClock.uptimeMillis()); }
                    acknowledgements.beginFrame();
                    frame.put("seq", sequence++); send(output, frame);
                }
            } catch (Exception error) {
                for (Throwable cause = error; cause != null; cause = cause.getCause())
                    terminal |= cause instanceof CertificateException;
                // No pairing data or exception text enters Android logs or UI.
            } finally {
                boolean retry = !closed && !ready && invitation == null && pairing.optBoolean("bonjour") && !usedDiscovery;
                if (acknowledgements != null) acknowledgements.close();
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
                if (acknowledgements != null) acknowledgements.awaitStopped();
                handler.post(() -> {
                    if (link != this || closed) return;
                    worker = null; // All transport and ACK cleanup has completed before this callback.
                    if (retry && !terminal) {
                        try { discover(); return; } catch (Exception ignored) {}
                    }
                    fail(invitation == null ? "Disconnected. Check Wi-Fi and the receiver."
                            : "Pairing ended. Ask the Mac for a new invitation.");
                });
            }
        }
        private void send(OutputStream output, JSONObject packet) throws Exception {
            byte[] bytes = (packet.toString()+"\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 8192) throw new IOException("Frame too large");
            output.write(bytes); output.flush();
        }
    }
}
