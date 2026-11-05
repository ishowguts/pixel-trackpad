package local.pixeltrackpad;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.ReaderException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Collections;

/** Run with phone/check.sh. Uses the production QR decoder and invitation parser, without Android. */
public final class ScannerCheck {
    private static void require(boolean value) { if (!value) throw new AssertionError("Scanner check failed"); }

    private static String scan(String text, int pixelStride, int rotation) throws Exception {
        BitMatrix qr = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 560, 560,
                Collections.singletonMap(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        int width = qr.getWidth(), height = qr.getHeight();
        int rowStride = width * pixelStride + 23, start = 7;
        // The final row has no trailing padding, as permitted for Android image planes.
        ByteBuffer buffer = ByteBuffer.allocate(start + (height - 1) * rowStride + (width - 1) * pixelStride + 1);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int sourceX = x, sourceY = y;
            for (int turn = 0; turn < rotation; turn++) {
                int previousX = sourceX; sourceX = sourceY; sourceY = width - 1 - previousX;
            }
            boolean black = qr.get(sourceX, sourceY);
            buffer.put(start + y * rowStride + x * pixelStride, black ? (byte) 0 : (byte) 255);
        }
        buffer.position(start);
        byte[] pixels = QrDecoder.luminance(buffer, width, height, rowStride, pixelStride);
        require(buffer.position() == start);
        try { return QrDecoder.decode(pixels, width, height); }
        catch (ReaderException error) {
            throw new AssertionError("QR decode failed: length=" + text.length() + ", stride=" + pixelStride + ", rotation=" + rotation, error);
        }
    }

    private static void reject(String text) throws Exception {
        try { Invitation.parse(text); throw new AssertionError("Accepted invalid invitation"); }
        catch (IOException expected) { /* Invalid invitations never reach pairing. */ }
    }

    public static void main(String[] args) throws Exception {
        byte[] random = new byte[32];
        SecureRandom generator = new SecureRandom();
        generator.nextBytes(random); String pin = HexFormat.of().formatHex(random);
        generator.nextBytes(random); String token = HexFormat.of().formatHex(random);
        String valid = "local.pixeltrackpad://pair/v2/192.168.1.23/54321/" + pin + "/" + token;
        for (int stride : new int[]{1, 2}) for (int rotation = 0; rotation < 4; rotation++) {
            String decoded = scan(valid, stride, rotation);
            require(valid.equals(decoded));
            Invitation result = Invitation.parse(decoded);
            require(pin.equals(result.fingerprint) && token.equals(result.token)
                    && "192.168.1.23".equals(result.host) && "54321".equals(result.port));
        }
        String ipv6 = valid.replace("192.168.1.23", "fd12::abcd");
        require("fd12::abcd".equals(Invitation.parse(scan(ipv6, 1, 0)).host));
        require(Invitation.parse("local.pixeltrackpad://pair/v1/" + pin + "/" + token).host == null);
        for (String invalid : new String[]{"https://example.com/", valid + "/extra", valid + "?x=1",
                valid.replace("192.168.1.23", "8.8.8.8"), valid.replace("192.168.1.23", "127.0.0.1"),
                valid.replace("192.168.1.23", "192.168.1.999"), valid.replace("192.168.1.23", "example.com"),
                valid.replace("192.168.1.23", "fe80::1"), valid.replace("54321", "99999"),
                valid.substring(0, valid.length() - 1), valid + "x".repeat(211)}) reject(scan(invalid, 1, 0));
        reject(null);
        try { QrDecoder.luminance(ByteBuffer.allocate(3), 2, 2, 2, 1); throw new AssertionError("Short plane accepted"); }
        catch (IllegalArgumentException expected) { /* A truncated plane must be rejected. */ }
        System.out.println("PASS: generated v2 QR decode, rotation, padded row/pixel strides, shared parser rejection, IPv6/v1 compatibility.");
    }
}
