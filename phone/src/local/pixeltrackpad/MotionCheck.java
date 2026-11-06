package local.pixeltrackpad;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import java.io.File;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Run with adb shell am instrument -w local.pixeltrackpad/.MotionCheck. No receiver connection or Mac input. */
public final class MotionCheck extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private static JSONObject frame(String label, boolean move) throws Exception {
        return new JSONObject().put("label", label).put("_move", move);
    }

    /** In-memory socket only: ACK delivery is controlled by the check, never a real peer. */
    private static final class Peer extends Socket {
        final ArrayBlockingQueue<Integer> replies = new ArrayBlockingQueue<>(16);
        volatile int timeout;
        volatile boolean closed;
        @Override public void setSoTimeout(int milliseconds) { timeout = milliseconds; }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    try {
                        Integer reply = replies.poll(timeout, TimeUnit.MILLISECONDS);
                        if (reply == null) throw new SocketTimeoutException();
                        return reply;
                    } catch (InterruptedException error) { throw new IOException(error); }
                }
            };
        }
        @Override public void close() { closed = true; replies.offer(-1); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void transportCheck(boolean motion) throws Exception {
        for (String end : new String[]{"eof", "invalid", "stop", "remote", "timeout"}) {
            Peer peer = new Peer();
            ArrayBlockingQueue<String> sent = new ArrayBlockingQueue<>(16);
            AtomicReference<AckWindow> window = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch fifth = new CountDownLatch(1);
            // The instrumentation path exercises the real queue selector while its sender is blocked.
            ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(64);
            if (motion) for (int i = 0; i < 4; i++) queue.add(frame("transition" + i, false));
            Thread sender = new Thread(() -> {
                AckWindow acks = null;
                try {
                    acks = new AckWindow(peer, true);
                    window.set(acks);
                    for (int i = 0; ; i++) {
                        if (i == 4) fifth.countDown();
                        acks.awaitCapacity();
                        String label = motion ? MainActivity.nextFrame(queue).getString("label") : "frame" + i;
                        acks.beginFrame();
                        sent.add(label);
                    }
                } catch (IOException | InterruptedException expected) {
                    // Each termination path must unblock the sender without further ACKs or frames.
                } catch (Throwable error) { failure.set(error); }
                finally {
                    if (acks != null) { acks.close(); acks.awaitStopped(); }
                }
            }, "transport-check-sender");
            sender.start();
            try {
                for (int i = 0; i < 4; i++) require((motion ? "transition" + i : "frame" + i)
                        .equals(sent.poll(2, TimeUnit.SECONDS)), "Four ordered frames must precede first ACK");
                require(fifth.await(2, TimeUnit.SECONDS), "Sender did not reach window limit");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (sender.getState() != Thread.State.WAITING && sender.isAlive() && System.nanoTime() < deadline) Thread.yield();
                require(sender.getState() == Thread.State.WAITING && sent.isEmpty(), "Outstanding frames exceeded four");
                if (motion) {
                    for (int i = 1; i <= 20; i++) queue.add(frame("move" + i, true));
                    queue.add(frame("pointer up", false));
                    queue.add(frame("last move", true));
                    queue.add(frame("up", false));
                }
                // One ACK permits exactly one more send, selecting the latest unsent MOVE.
                peer.replies.add((int) 'K');
                require((motion ? "move20" : "frame4").equals(sent.poll(2, TimeUnit.SECONDS)), "ACK did not resume sender");
                if (motion) for (String label : new String[]{"pointer up", "last move", "up"}) {
                    peer.replies.add((int) 'K');
                    require(label.equals(sent.poll(2, TimeUnit.SECONDS)), "Transition order changed");
                }
                require(sent.isEmpty(), "ACK exceeded window credit");
                long stopped = System.nanoTime();
                if (end.equals("eof")) peer.replies.add(-1);
                if (end.equals("invalid")) peer.replies.add((int) 'X');
                if (end.equals("remote")) peer.replies.add((int) 'S');
                if (end.equals("stop")) window.get().close();
                sender.join(end.equals("timeout") ? 3500 : 2000);
                require(!sender.isAlive() && peer.closed, "Session did not close on " + end);
                require(failure.get() == null, "Unexpected sender failure: " + failure.get());
                require(window.get().remoteStopped == end.equals("remote"), "Explicit stop classification changed");
                if (end.equals("timeout")) require(System.nanoTime() - stopped < TimeUnit.MILLISECONDS.toNanos(3400),
                        "Oldest ACK timeout exceeded three seconds plus scheduling allowance");
            } finally {
                if (window.get() != null) window.get().close();
                peer.close();
                sender.join(2000);
            }
        }
    }

    /** Host JVM entry point tests transport without Android JSON or any network access. */
    public static void main(String[] args) throws Exception {
        recoveryCheck();
        transportCheck(false);
        System.out.println("PASS: foreground reconnect intent, retry cap, explicit stops; four-frame pipeline, ordered sends, ACK credit, EOF/invalid/stop/three-second timeout; no network or input events.");
    }

    private static void recoveryCheck() {
        Reconnect state = new Reconnect();
        require(!state.begin(), "Background attempt started");
        state.foreground = true;
        require(state.begin() && !state.begin(), "Duplicate attempt allowed");
        for (int failure = 0; failure < 128; failure++) {
            long delay = 500L << Math.min(failure, 4);
            require(state.failed(true) == delay && state.wanted && state.pending && !state.begin(), "Retry delay, intent or exclusivity changed");
            require(state.failed(true) == -1 && state.pending, "Duplicate retry scheduled");
            require(state.retry() && !state.retry() && state.begin(), "Retry did not start exactly once");
            require(state.failures <= 4, "Backoff counter did not saturate");
        }
        require(state.failed(true) == 8000 && state.wanted, "Extended outage disabled connection intent");
        state.suspend();
        require(state.wanted && !state.begin() && !state.retry(), "Pause retained background work or lost intent");
        state.foreground = true;
        require(state.begin(), "Resume did not reconnect");
        state.ready();
        require(state.failed(true) == 500, "Ready did not reset backoff");
        state.disconnect();
        require(!state.retry() && !state.begin(), "Phone Disconnect was defeated");
        state.connect(); state.begin();
        state.failed(false);
        state.suspend(); state.foreground = true;
        require(!state.wanted && !state.begin() && !state.retry(), "Mac stop or fatal rejection was defeated on resume");
    }

    /** Separate storage and a synthetic Link keep this UI check away from the user's pairing and network. */
    public static final class CheckActivity extends MainActivity {
        private final String checkName = "motion-check-" + System.nanoTime();
        @Override public File getFilesDir() {
            File directory = new File(getCacheDir(), checkName);
            if (!directory.isDirectory() && !directory.mkdir()) throw new IllegalStateException("Check storage unavailable");
            return directory;
        }
        @Override public SharedPreferences getPreferences(int mode) { return getSharedPreferences(checkName, mode); }
        @Override protected void onDestroy() {
            super.onDestroy();
            deleteSharedPreferences(checkName);
            getFilesDir().delete();
        }
        @Override public void onCreate(Bundle state) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("connectWanted", false).commit();
            super.onCreate(new Bundle());
        }
    }

    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return name.equals(MainActivity.class.getName()) ? new CheckActivity() : super.newActivity(loader, name, intent);
    }

    private static void touch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try { view.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private void awaitLayout(View view) throws InterruptedException {
        CountDownLatch drawn = new CountDownLatch(1);
        runOnMainSync(() -> {
            view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    view.getViewTreeObserver().removeOnPreDrawListener(this);
                    drawn.countDown();
                    return true;
                }
            });
            view.requestLayout(); view.invalidate();
        });
        require(drawn.await(3, TimeUnit.SECONDS), "Check activity did not lay out");
        waitForIdleSync();
    }

    @SuppressWarnings("deprecation")
    private void controlsCheck() throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<MainActivity.Link> connection = new AtomicReference<>();
        try {
            awaitLayout(activity.surface);
            runOnMainSync(() -> {
                MainActivity.Link session = activity.new Link(new JSONObject(), null);
                activity.link = session; connection.set(session);
                session.ready = session.surfaceScale = session.stopSupported = true;
                activity.reconnect.connect(); activity.reconnect.foreground = true; activity.reconnect.begin();
                activity.fullScreenButton.setEnabled(true);
                activity.setFullScreen(true);
            });
            awaitLayout(activity.surface);
            runOnMainSync(() -> {
                MainActivity.Link session = activity.link;
                require(activity.fullScreen && activity.controlsButton.isShown(), "Full-screen Controls missing");
                require(activity.fullScreenButton.getVisibility() == View.VISIBLE && !activity.fullScreenButton.isShown(), "Toolbar still shown");
                session.queue.clear();
                // Dispatch through the actual parent, so Android must route the button gesture away from Surface.
                View parent = (View) activity.controlsButton.getParent();
                float x = activity.controlsButton.getX() + activity.controlsButton.getWidth()/2f;
                float y = activity.controlsButton.getY() + activity.controlsButton.getHeight()/2f;
                touch(parent, MotionEvent.ACTION_DOWN, x, y);
                touch(parent, MotionEvent.ACTION_UP, x, y);
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                MainActivity.Link session = connection.get();
                require(!activity.fullScreen && activity.link == session && session.ready, "Controls disconnected or failed to reveal toolbar");
                require(session.queue.stream().noneMatch(p -> "sample".equals(p.optString("type"))), "Controls posted a touch");
                activity.fullScreenButton.performClick();
                require(activity.fullScreen, "Full screen button failed");
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitForIdleSync();
            runOnMainSync(() -> require(!activity.fullScreen && activity.link == connection.get(), "Back did not exit full screen first"));
            awaitLayout(activity.surface);
            runOnMainSync(() -> {
                MainActivity.Link session = activity.link;
                session.queue.clear();
                touch(activity.surface, MotionEvent.ACTION_DOWN, 40, activity.surface.getHeight()/2f);
                require(activity.surface.capturing && "sample".equals(session.queue.peek().optString("type")), "Fresh DOWN not captured");
                activity.fullScreenButton.performClick();
                require(!activity.surface.capturing && session.queue.size() == 1
                        && "cancel".equals(session.queue.peek().optString("type")), "Mode change did not cancel gesture");
            });
            awaitLayout(activity.surface);
            runOnMainSync(() -> {
                MainActivity.Link session = activity.link;
                session.queue.clear();
                touch(activity.surface, MotionEvent.ACTION_MOVE, 45, activity.surface.getHeight()/2f);
                require(session.queue.isEmpty(), "Move crossed a mode change without DOWN");
                touch(activity.surface, MotionEvent.ACTION_DOWN, 40, activity.surface.getHeight()/2f);
                JSONObject sample = session.queue.peek();
                require(sample != null && sample.optJSONArray("surface") != null, "Resized surface missing calibration scale");
                touch(activity.surface, MotionEvent.ACTION_MOVE, -1, 20);
                require(!activity.surface.capturing && "cancel".equals(session.queue.peek().optString("type")), "Outside touch was forwarded");
                session.fail("Disposable failure.");
                require(activity.link == null && activity.reconnect.pending && !activity.fullScreen, "Failure did not schedule recovery or expose controls");
                session.fail("Stale failure.");
                activity.stop();
                require(!activity.reconnect.pending && !activity.reconnect.wanted, "Explicit stop retained retry");
                activity.onWindowFocusChanged(false);
                activity.onWindowFocusChanged(true);
                require(activity.link == null && !activity.reconnect.wanted, "Focus defeated explicit stop");
                MainActivity.Link paused = activity.new Link(new JSONObject(), null);
                Peer socket = new Peer(); paused.socket = socket;
                paused.wifiLock = MainActivity.acquireWifiLock(activity);
                require(paused.wifiLock != null && paused.wifiLock.isHeld(), "Lifecycle Wi-Fi lock unavailable");
                paused.ready = paused.surfaceScale = paused.stopSupported = true;
                activity.link = paused;
                activity.reconnect.connect(); activity.reconnect.foreground = true; activity.reconnect.begin();
                activity.setFullScreen(true);
                callActivityOnPause(activity);
                require(paused.closed && socket.closed && !paused.wifiLock.isHeld() && activity.link == null
                        && !activity.fullScreen && !activity.surface.capturing && activity.reconnect.wanted
                        && !activity.reconnect.pending, "Pause did not release the session while preserving intent");
                activity.stop();
                callActivityOnResume(activity);
                require(activity.link == null && !activity.reconnect.wanted, "Resume defeated explicit stop");
            });
        } finally { runOnMainSync(activity::finish); waitForIdleSync(); }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            recoveryCheck();
            controlsCheck();
            WifiManager.WifiLock lock = MainActivity.acquireWifiLock(getTargetContext());
            try {
                require(lock != null && lock.isHeld(), "Wi-Fi lock acquisition failed");
                MainActivity.releaseWifiLock(lock);
                require(!lock.isHeld(), "Wi-Fi lock remained held after release");
                MainActivity.releaseWifiLock(lock);
                MainActivity.releaseWifiLock(null);
                require(!lock.isHeld(), "Repeated Wi-Fi cleanup changed lock state");
            } finally { MainActivity.releaseWifiLock(lock); }
            ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(64);
            queue.add(frame("down", false));
            for (int i = 1; i <= 20; i++) queue.add(frame("move" + i, true));
            queue.add(frame("second down", false));
            queue.add(frame("two-finger move", true));
            queue.add(frame("pointer up", false));
            queue.add(frame("last move", true));
            queue.add(frame("up", false));
            queue.add(frame("next down", false));
            queue.add(frame("next move", true));
            queue.add(frame("next up", false));
            String[] expected = {"down", "move20", "second down", "two-finger move", "pointer up",
                                 "last move", "up", "next down", "next move", "next up"};
            for (String label : expected) {
                JSONObject value = MainActivity.nextFrame(queue);
                if (!label.equals(value.getString("label")) || value.has("_move")) throw new AssertionError(label);
            }
            if (MainActivity.nextFrame(queue) != null) throw new AssertionError("Idle heartbeat path");
            transportCheck(true);
            result.putString("stream", "PASS: foreground recovery, retry cap, explicit stop, Controls routing, Full screen, Back, resize cancellation and fresh DOWN; Wi-Fi lock acquired, released, and cleaned up repeatedly; latest movement retained; every finger transition ordered; metadata removed; idle path preserved; bounded ACK pipeline resumes and terminates. No receiver connection or Mac input.");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "FAIL: " + (failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName()));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
