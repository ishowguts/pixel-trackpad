package local.pixeltrackpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
public final class MainActivity extends Activity {
    private TextView status;
    private Button connect;
    private AlertDialog invitationDialog;
    private QrScanner scanner;
    private boolean resumed, scanPermissionPending, scanWhenResumed;
    private static final int CAMERA_PERMISSION = 7;
    private Surface surface;
    private volatile Link link;
    private int maximum;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final String SERVICE_TYPE = "_pixeltrackpad._tcp.";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AtomicFile storage() {
        return new AtomicFile(new File(getFilesDir(), "pairing.json"));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 12, 24, 24);
        LinearLayout controls = new LinearLayout(this);
        connect = new Button(this); connect.setText("Connect");
        connect.setOnClickListener(v -> { if (link == null) start(); else stop(); });
        Button settings = new Button(this); settings.setText("Settings");
        settings.setOnClickListener(v -> edit());
        Button scan = new Button(this); scan.setText("Scan QR");
        scan.setOnClickListener(v -> scan());
        status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("Disconnected. Maximum contacts: 0");
        controls.addView(connect); controls.addView(settings); controls.addView(scan);
        controls.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(controls);
        surface = new Surface();
        layout.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
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
            note.setText("Measure the grey active area with a ruler in landscape. Enter its width and height in mm. Changes affect this receiver only. Save while disconnected.");
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
        try {
            JSONObject pairing = read();
            if (!pairing.getString("fingerprint").matches("[0-9a-fA-F]{64}") ||
                    !pairing.getString("secret").matches("[0-9a-f]{64}")) throw new IOException("Invalid pairing");
            validateSettings(pairing.optJSONObject("settings"));
            begin(new Link(pairing, null));
        } catch (Exception error) {
            status.setText("No usable pairing. Show an invitation on the Mac and scan it with your phone.");
        }
    }

    private void begin(Link next) throws Exception {
        stop();
        link = next;
        status.setText("Finding Mac…");
        connect.setText("Disconnect");
        try {
            boolean legacy = !next.pairing.optBoolean("bonjour");
            if (next.pairing.has("host") || legacy) next.startNetwork(savedEndpoint(next.pairing, legacy));
            else next.discover();
        } catch (Exception error) {
            stop();
            throw error;
        }
    }

    private void stop() {
        Link previous = link; link = null;
        if (previous != null) {
            previous.close();
            if (previous.discovery != null) previous.stopDiscovery(previous.discovery);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (status != null) { status.setText("Disconnected. Maximum contacts: " + maximum); updateConnect(); }
        if (surface != null) { surface.capturing = false; surface.invalidate(); }
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (scanWhenResumed) { scanWhenResumed = false; openScanner(); }
    }
    @Override protected void onPause() { resumed = false; closeScanner(); stop(); super.onPause(); }
    @Override protected void onStop() { scanPermissionPending = false; scanWhenResumed = false; super.onStop(); }
    @Override protected void onDestroy() { closeScanner(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (!focused && link != null) stop(); }

    static JSONObject nextFrame(ArrayBlockingQueue<JSONObject> queue) throws InterruptedException {
        JSONObject frame = queue.poll(250, TimeUnit.MILLISECONDS);
        // latest queued moves reduce stale replay; this does not hide Wi-Fi latency spikes.
        while (frame != null && frame.optBoolean("_move") && queue.peek() != null && queue.peek().optBoolean("_move")) {
            frame = queue.remove();
        }
        if (frame != null) frame.remove("_move");
        return frame;
    }

    private final class Surface extends View {
        private boolean capturing;
        Surface() { super(MainActivity.this); setContentDescription("Trackpad touch surface. Connect first. Use controls above to disconnect or change settings."); setFocusable(true); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(45, 48, 54)); paint.setColor(Color.WHITE); paint.setTextSize(28);
            canvas.drawText("Trackpad — navigation stays outside this area", 24, 48, paint);
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onTouchEvent(MotionEvent event) {
            Link current = link;
            if (current == null || !current.ready) { capturing = false; return true; }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) capturing = true;
            if (!capturing) return true;
            if (action == MotionEvent.ACTION_CANCEL) { stop(); return true; }
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
                frame.put("_move", action == MotionEvent.ACTION_MOVE);
                if (!current.queue.offer(frame)) stop(); // Overflow cancels rather than dropping a transition.
                if (action == MotionEvent.ACTION_UP) { capturing = false; performClick(); }
            } catch (Exception error) { stop(); }
            return true;
        }
    }

    private static boolean localAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc);
    }

    private final class Link implements Runnable {
        final JSONObject pairing;
        final String invitation;
        final NsdManager nsd = getSystemService(NsdManager.class);
        NsdManager.DiscoveryListener discovery;
        InetSocketAddress endpoint;
        boolean usedDiscovery;
        final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(64);
        volatile boolean closed, ready;
        volatile Socket socket;
        volatile AckWindow acknowledgements;
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
            handler.postDelayed(() -> {
                if (link == this && endpoint == null) fail("Mac not found. Check Wi-Fi and reopen the receiver.");
            }, 10000);
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
            stop();
            status.setText(message);
        }

        void startNetwork(InetSocketAddress address) {
            endpoint = address;
            status.setText("Connecting securely…");
            new Thread(this, "trackpad-network").start();
        }

        void close() {
            closed = true;
            ready = false;
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
                hello.put("secret", pairing.getString("secret")); hello.put("t", SystemClock.uptimeMillis());
                hello.put("settings", pairing.optJSONObject("settings") == null ? new JSONObject() : pairing.getJSONObject("settings"));
                send(output, hello);
                if (!"ready".equals(response(input).optString("type"))) throw new IOException("Pairing rejected");
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
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    status.setText("Connected. Maximum contacts: " + maximum);
                });
                acknowledgements = new AckWindow(secure);
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
                // No pairing data or exception text enters Android logs or UI.
            } finally {
                boolean retry = !closed && !ready && invitation == null && pairing.optBoolean("bonjour") && !usedDiscovery;
                if (acknowledgements != null) acknowledgements.close();
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
                if (acknowledgements != null) acknowledgements.awaitStopped();
                runOnUiThread(() -> {
                    if (link != this) return;
                    if (retry) {
                        try { discover(); return; } catch (Exception ignored) {}
                    }
                    fail(invitation == null ? "Disconnected. Check Wi-Fi and receiver. If the Mac endpoint changed, forget the phone on the Mac and pair again."
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
