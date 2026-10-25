package info.schnatterer.pmcaFilesystemServer;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Hashtable;

/**
 * Builds a QR bitmap for the server URL (Smart Remote–style connect UX).
 */
final class QrCodeHelper {

    private QrCodeHelper() {
    }

    /**
     * Always returns a square {@code sizePx × sizePx} bitmap so ImageView scaling cannot skew modules.
     */
    static Bitmap encode(String content, int sizePx) {
        if (content == null || content.length() == 0 || sizePx <= 0) {
            return null;
        }
        try {
            Hashtable<EncodeHintType, Object> hints = new Hashtable<EncodeHintType, Object>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, Integer.valueOf(1));
            // Request 0×0 to get the pure module matrix, then scale evenly into sizePx.
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
            int modules = matrix.getWidth();
            if (modules <= 0 || matrix.getHeight() != modules) {
                // Fall back to sized encode if needed
                matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
                modules = Math.min(matrix.getWidth(), matrix.getHeight());
            }

            int[] pixels = new int[sizePx * sizePx];
            for (int y = 0; y < sizePx; y++) {
                int moduleY = y * modules / sizePx;
                if (moduleY >= modules) {
                    moduleY = modules - 1;
                }
                int row = y * sizePx;
                for (int x = 0; x < sizePx; x++) {
                    int moduleX = x * modules / sizePx;
                    if (moduleX >= modules) {
                        moduleX = modules - 1;
                    }
                    pixels[row + x] = matrix.get(moduleX, moduleY) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx);
            return bitmap;
        } catch (Throwable t) {
            Logger.error("QrCodeHelper.encode failed: " + t.getMessage(), t);
            return null;
        }
    }

    /**
     * Gallery URL with a single access code as the Basic Auth username
     * ({@code http://TOKEN:@host:port/gallery}). Password may be left empty when typing.
     */
    static String galleryUrl(String host, int port, String accessToken) {
        return "http://" + accessToken + ":@" + host + ":" + port + "/gallery";
    }

    /** @deprecated use {@link #galleryUrl(String, int, String)} */
    static String galleryUrl(String host, int port, String username, String password) {
        String token = (password != null && password.length() > 0) ? password : username;
        return galleryUrl(host, port, token);
    }
}
