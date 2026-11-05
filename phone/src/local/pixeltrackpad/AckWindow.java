package local.pixeltrackpad;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** One sender and one ACK reader; closing either direction stops the session. */
final class AckWindow implements Closeable {
    static final int LIMIT = 4;
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(3);
    private final Socket socket;
    private final InputStream input;
    private final Thread sender = Thread.currentThread();
    private final Thread reader;
    private final long[] deadlines = new long[LIMIT];
    private int head, count;
    private boolean closed;

    AckWindow(Socket socket) throws IOException {
        this.socket = socket;
        input = socket.getInputStream();
        reader = new Thread(this::drain, "trackpad-acks");
        reader.start();
    }

    synchronized void awaitCapacity() throws IOException, InterruptedException {
        while (!closed && count == LIMIT) wait();
        if (closed) throw new IOException("Session closed");
    }

    // The single sender waits before selecting a frame, so blocked movement stays coalescible.
    synchronized void beginFrame() throws IOException {
        if (closed || count == LIMIT) throw new IOException("Session closed or full");
        deadlines[(head + count) % LIMIT] = System.nanoTime() + TIMEOUT;
        count++;
    }

    private synchronized int readTimeout() throws SocketTimeoutException {
        long remaining = count == 0 ? TIMEOUT : deadlines[head] - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("Acknowledgement timeout");
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private void drain() {
        try {
            while (true) {
                socket.setSoTimeout(readTimeout());
                int ack;
                try { ack = input.read(); }
                catch (SocketTimeoutException timeout) {
                    // Recheck the oldest deadline; later ACKs never extend its three-second bound.
                    readTimeout();
                    continue;
                }
                synchronized (this) {
                    if (closed) return;
                    readTimeout();
                    if (ack != 'K' || count == 0) throw new IOException("Invalid acknowledgement or EOF");
                    head = (head + 1) % LIMIT;
                    count--;
                    notifyAll();
                }
            }
        } catch (IOException error) {
            // Closing the socket also unblocks a stalled write; no exception data leaves the session.
        } finally { close(); }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            notifyAll();
        }
        sender.interrupt();
        try { socket.close(); } catch (IOException ignored) {}
    }

    void awaitStopped() {
        boolean interrupted = false;
        while (reader.isAlive()) {
            try { reader.join(); }
            catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
