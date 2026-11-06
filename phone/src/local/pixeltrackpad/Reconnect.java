package local.pixeltrackpad;

/** Foreground connection intent, one attempt, and one bounded retry per failure. */
final class Reconnect {
    boolean wanted = true, foreground, active, pending;
    int failures;

    boolean begin() {
        if (!wanted || !foreground || active || pending) return false;
        active = true;
        return true;
    }

    void connect() { wanted = true; failures = 0; }
    void ready() { failures = 0; }

    long failed(boolean retryable) {
        active = false;
        if (!retryable) disconnect();
        if (!wanted || !foreground || pending) return -1;
        pending = true;
        long delay = 500L << failures;
        if (failures < 4) failures++;
        return delay;
    }

    boolean retry() {
        if (!pending) return false;
        pending = false;
        return wanted && foreground && !active;
    }

    void suspend() { foreground = false; active = pending = false; }
    void disconnect() { wanted = active = pending = false; }
}
