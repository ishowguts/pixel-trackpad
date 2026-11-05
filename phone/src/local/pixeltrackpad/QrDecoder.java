package local.pixeltrackpad;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.nio.ByteBuffer;
import java.util.Collections;

/** QR-only decoding of a bounded, unpadded luminance plane. No frame storage or network access. */
final class QrDecoder {
    static byte[] luminance(ByteBuffer plane, int width, int height, int rowStride, int pixelStride) {
        if (width <= 0 || height <= 0 || width > 1280 || height > 1280
                || pixelStride < 1 || rowStride < (long) (width - 1) * pixelStride + 1
                || (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride >= plane.remaining())
            throw new IllegalArgumentException("Invalid luminance plane");
        byte[] pixels = new byte[width * height];
        int start = plane.position();
        for (int y = 0; y < height; y++) {
            int row = start + y * rowStride;
            if (pixelStride == 1) plane.duplicate().position(row).get(pixels, y * width, width);
            else for (int x = 0; x < width; x++) pixels[y * width + x] = plane.get(row + x * pixelStride);
        }
        return pixels;
    }

    static String decode(byte[] pixels, int width, int height) throws ReaderException {
        var source = new PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false);
        var bitmap = new BinaryBitmap(new HybridBinarizer(source));
        var reader = new QRCodeReader();
        try {
            return reader.decode(bitmap, Collections.singletonMap(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
        } catch (ReaderException missed) {
            // Straight-on codes on a plain background can avoid ambiguous detector patterns.
            for (int turn = 0; turn < 4; turn++) {
                try {
                    return reader.decode(bitmap, Collections.singletonMap(DecodeHintType.PURE_BARCODE, Boolean.TRUE)).getText();
                } catch (ReaderException unreadable) { bitmap.getBlackMatrix().rotate90(); }
            }
            throw missed;
        }
    }
}
