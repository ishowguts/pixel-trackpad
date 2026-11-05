package local.pixeltrackpad;

import android.app.Activity;
import android.app.Instrumentation;
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

/** Run with adb shell am instrument -w local.pixeltrackpad/.MotionCheck. No network or input output. */
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
        for (String end : new String[]{"eof", "invalid", "stop", "timeout"}) {
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
                    acks = new AckWindow(peer);
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
                if (end.equals("stop")) window.get().close();
                sender.join(end.equals("timeout") ? 3500 : 2000);
                require(!sender.isAlive() && peer.closed, "Session did not close on " + end);
                require(failure.get() == null, "Unexpected sender failure: " + failure.get());
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
        transportCheck(false);
        System.out.println("PASS: four-frame pipeline, ordered sends, ACK credit, EOF/invalid/stop/three-second timeout; no network or input events.");
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
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
            result.putString("stream", "PASS: latest movement retained; every finger transition ordered; metadata removed; idle path preserved; bounded ACK pipeline resumes and terminates. No network or input events.");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "FAIL: motion queue check");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
