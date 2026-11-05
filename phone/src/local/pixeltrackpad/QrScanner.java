package local.pixeltrackpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.ReaderException;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.function.Predicate;

/** One rear-camera session. Camera callbacks, decoding and teardown share one worker. */
final class QrScanner {
    private final Activity activity;
    private final Predicate<String> accept;
    private final Runnable finished;
    private final HandlerThread thread = new HandlerThread("qr-camera");
    private final Handler worker;
    private final AlertDialog dialog;
    private final TextView hint;
    private final FrameLayout viewfinder;
    private final DisplayManager displays;
    private SurfaceView preview;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean opening, cameraClosing, delivered, finishedPosted;
    private volatile boolean closed;
    private long nextDecode;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) { if (preview != null) preview.requestLayout(); }
    };

    QrScanner(Activity activity, Predicate<String> accept, Runnable finished) {
        this.activity = activity; this.accept = accept; this.finished = finished;
        thread.start(); worker = new Handler(thread.getLooper());
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        hint = new TextView(activity);
        hint.setText("Point the rear camera at the QR on your Mac. Frames stay on this phone.");
        hint.setPadding(24, 12, 24, 12);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(hint);
        viewfinder = new FrameLayout(activity);
        viewfinder.setBackgroundColor(Color.BLACK);
        content.addView(viewfinder, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog = new AlertDialog.Builder(activity).setTitle("Scan pairing QR").setView(content)
                .setNegativeButton("Cancel", (d, button) -> close()).create();
        dialog.setOnDismissListener(d -> close());
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        displays = activity.getSystemService(DisplayManager.class);
        displays.registerDisplayListener(displayListener, new Handler(activity.getMainLooper()));
        worker.post(this::prepare);
    }

    private void prepare() {
        if (closed) return;
        try {
            CameraManager manager = activity.getSystemService(CameraManager.class);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                if (!Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(info.get(CameraCharacteristics.LENS_FACING))) continue;
                StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Size size = null;
                Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
                Size[] visible = map.getOutputSizes(SurfaceHolder.class);
                if (yuv == null || visible == null) continue;
                for (Size candidate : yuv) {
                    if (candidate.getWidth() > 1280 || candidate.getHeight() > 1280) continue;
                    if (Arrays.asList(visible).contains(candidate) && (size == null
                            || candidate.getWidth() * candidate.getHeight() > size.getWidth() * size.getHeight())) size = candidate;
                }
                if (size == null) continue;
                final Size chosen = size;
                int orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                reader.setOnImageAvailableListener(this::frame, worker);
                activity.runOnUiThread(() -> {
                    if (closed) return;
                    preview = new SurfaceView(activity) {
                        @Override protected void onMeasure(int widthSpec, int heightSpec) {
                            int rotation = getDisplay().getRotation() * 90;
                            boolean swap = (orientation - rotation + 360) % 180 != 0;
                            int w = swap ? chosen.getHeight() : chosen.getWidth();
                            int h = swap ? chosen.getWidth() : chosen.getHeight();
                            float scale = Math.min((float) MeasureSpec.getSize(widthSpec) / w,
                                    (float) MeasureSpec.getSize(heightSpec) / h);
                            setMeasuredDimension(Math.round(w * scale), Math.round(h * scale));
                        }
                    };
                    preview.setContentDescription("Rear camera preview. Center the Mac pairing QR in this area.");
                    SurfaceHolder holder = preview.getHolder();
                    holder.setFixedSize(chosen.getWidth(), chosen.getHeight());
                    holder.addCallback(new SurfaceHolder.Callback() {
                        @Override public void surfaceCreated(SurfaceHolder surface) { worker.post(() -> open(manager, id, info, surface)); }
                        @Override public void surfaceChanged(SurfaceHolder surface, int format, int width, int height) {}
                        @Override public void surfaceDestroyed(SurfaceHolder surface) { close(); }
                    });
                    viewfinder.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                });
                return;
            }
            fail("No supported rear camera. You can still open a pairing link manually.");
        } catch (Exception error) { fail("Camera unavailable. Close other camera apps and tap Scan QR to retry."); }
    }

    @SuppressWarnings("deprecation")
    private void open(CameraManager manager, String id, CameraCharacteristics info, SurfaceHolder holder) {
        if (closed) return;
        try {
            opening = true;
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    opening = false; camera = device;
                    if (closed) { release(); return; }
                    try {
                        device.createCaptureSession(Arrays.asList(holder.getSurface(), reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession configured) {
                                if (closed) { configured.close(); return; }
                                session = configured;
                                try {
                                    CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    request.addTarget(holder.getSurface()); request.addTarget(reader.getSurface());
                                    int[] modes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                                    int mode = CaptureRequest.CONTROL_AF_MODE_OFF;
                                    if (modes != null) for (int available : modes) {
                                        if (available == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) { mode = available; break; }
                                        if (available == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                                || (available == CaptureRequest.CONTROL_AF_MODE_AUTO && mode == CaptureRequest.CONTROL_AF_MODE_OFF)) mode = available;
                                    }
                                    request.set(CaptureRequest.CONTROL_AF_MODE, mode);
                                    configured.setRepeatingRequest(request.build(), null, worker);
                                    if (mode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                                        // Auto-only cameras need another focus pass when the QR moves closer.
                                        new Runnable() {
                                            @Override public void run() {
                                                if (closed || session != configured) return;
                                                try {
                                                    request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                                                    configured.capture(request.build(), null, worker);
                                                    request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                                                    configured.capture(request.build(), null, worker);
                                                    worker.postDelayed(this, 1500);
                                                } catch (Exception error) { fail("Camera focus unavailable. Tap Scan QR to retry."); }
                                            }
                                        }.run();
                                    }
                                } catch (Exception error) { fail("Could not start camera preview. Tap Scan QR to retry."); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession configured) {
                                configured.close(); fail("Camera preview unavailable. Tap Scan QR to retry.");
                            }
                        }, worker);
                    } catch (Exception error) { fail("Camera preview unavailable. Tap Scan QR to retry."); }
                }
                @Override public void onDisconnected(CameraDevice device) { opening = false; camera = device; fail("Camera disconnected. Tap Scan QR to retry."); if (closed) release(); }
                @Override public void onError(CameraDevice device, int error) { opening = false; camera = device; fail("Camera unavailable. Tap Scan QR to retry."); if (closed) release(); }
                @Override public void onClosed(CameraDevice device) { cameraClosing = false; finishIfClosed(); }
            }, worker);
        } catch (Exception error) { opening = false; fail("Camera access unavailable. Tap Scan QR to retry."); if (closed) release(); }
    }

    private void frame(ImageReader source) {
        byte[] pixels = null;
        try {
            int width, height;
            try (Image image = source.acquireLatestImage()) {
                if (image == null || closed || delivered || SystemClock.elapsedRealtime() < nextDecode) return;
                nextDecode = SystemClock.elapsedRealtime() + 250;
                Rect crop = image.getCropRect();
                width = crop.width(); height = crop.height();
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer().duplicate();
                buffer.position(buffer.position() + crop.top * plane.getRowStride() + crop.left * plane.getPixelStride());
                pixels = QrDecoder.luminance(buffer, width, height, plane.getRowStride(), plane.getPixelStride());
            }
            if (closed) return;
            String text = QrDecoder.decode(pixels, width, height);
            if (closed) return;
            delivered = true;
            activity.runOnUiThread(() -> {
                if (closed) return;
                if (!accept.test(text)) {
                    hint.setText("Invalid pairing QR. Scan the invitation shown by Pixel Trackpad on your Mac.");
                    worker.post(() -> delivered = false);
                }
            });
        } catch (ReaderException ignored) {
            // A frame without a readable QR is normal. The next frame can try again.
        } catch (RuntimeException error) {
            if (!closed) fail("Could not read camera frames. Tap Scan QR to retry.");
        } finally {
            if (pixels != null) Arrays.fill(pixels, (byte) 0);
        }
    }

    private void fail(String message) {
        activity.runOnUiThread(() -> {
            if (closed) return;
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            close();
        });
    }

    void close() {
        if (closed) return;
        closed = true;
        displays.unregisterDisplayListener(displayListener);
        dialog.dismiss();
        worker.post(this::release);
    }

    private void release() {
        if (session != null) { session.close(); session = null; }
        if (camera != null) { CameraDevice previous = camera; camera = null; cameraClosing = true; previous.close(); }
        if (reader != null) { reader.close(); reader = null; }
        finishIfClosed();
    }

    private void finishIfClosed() {
        // Keep callbacks alive until a pending open and the device close have completed.
        if (!closed || opening || cameraClosing || finishedPosted) return;
        finishedPosted = true;
        thread.quitSafely();
        activity.runOnUiThread(finished);
    }
}
