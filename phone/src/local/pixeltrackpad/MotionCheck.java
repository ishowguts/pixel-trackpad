package local.pixeltrackpad;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.concurrent.ArrayBlockingQueue;

/** Run with adb shell am instrument -w local.pixeltrackpad/.MotionCheck. No network or input output. */
public final class MotionCheck extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private static JSONObject frame(String label, boolean move) throws Exception {
        return new JSONObject().put("label", label).put("_move", move);
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
            result.putString("stream", "PASS: latest movement retained; every finger transition ordered; metadata removed; idle path preserved. No network or input events.");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "FAIL: motion queue check");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
