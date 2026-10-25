package info.schnatterer.pmcaFilesystemServer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Downsizes JPEG for phone download (Sync to Smartphone–style Original / 2M / VGA).
 */
final class JpegResizer {

    enum Preset {
        ORIGINAL(0),
        TWO_MP(1920),
        VGA(640);

        final int maxEdge;

        Preset(int maxEdge) {
            this.maxEdge = maxEdge;
        }

        static Preset fromQuery(String raw) {
            if (raw == null || raw.length() == 0) {
                return ORIGINAL;
            }
            String s = raw.trim().toLowerCase(Locale.US);
            if ("2m".equals(s) || "2mp".equals(s) || "medium".equals(s)) {
                return TWO_MP;
            }
            if ("vga".equals(s) || "small".equals(s) || "640".equals(s)) {
                return VGA;
            }
            if ("original".equals(s) || "full".equals(s) || "0".equals(s)) {
                return ORIGINAL;
            }
            return ORIGINAL;
        }
    }

    private JpegResizer() {
    }

    /**
     * @return JPEG bytes at the requested size, or null if the file is not a resizable still
     */
    static byte[] resizeFile(File source, Preset preset) throws IOException {
        if (source == null || !source.exists()) {
            throw new IOException("Missing file");
        }
        File jpeg = resolveJpeg(source);
        if (jpeg == null) {
            return null;
        }
        if (preset == Preset.ORIGINAL || preset.maxEdge <= 0) {
            return readFully(jpeg);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(jpeg.getAbsolutePath(), bounds);
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0) {
            throw new IOException("Cannot decode " + jpeg.getPath());
        }

        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= preset.maxEdge) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeFile(jpeg.getAbsolutePath(), opts);
        if (decoded == null) {
            throw new IOException("Decode failed: " + jpeg.getPath());
        }

        try {
            int dw = decoded.getWidth();
            int dh = decoded.getHeight();
            float scale = 1f;
            int edge = Math.max(dw, dh);
            if (edge > preset.maxEdge) {
                scale = (float) preset.maxEdge / (float) edge;
            }
            Bitmap scaled = decoded;
            if (scale < 0.999f) {
                Matrix matrix = new Matrix();
                matrix.postScale(scale, scale);
                scaled = Bitmap.createBitmap(decoded, 0, 0, dw, dh, matrix, true);
                if (scaled != decoded) {
                    decoded.recycle();
                    decoded = null;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int quality = preset == Preset.VGA ? 80 : 88;
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
            if (scaled != null && scaled != decoded) {
                scaled.recycle();
            } else if (decoded != null) {
                decoded.recycle();
            }
            return out.toByteArray();
        } finally {
            if (decoded != null && !decoded.isRecycled()) {
                decoded.recycle();
            }
        }
    }

    static File resolveJpeg(File source) {
        String name = source.getName().toLowerCase(Locale.US);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return source;
        }
        if (name.endsWith(".arw") || name.endsWith(".raw")) {
            File sibling = changeExt(source, ".JPG");
            if (sibling.exists()) {
                return sibling;
            }
            sibling = changeExt(source, ".jpg");
            if (sibling.exists()) {
                return sibling;
            }
        }
        return null;
    }

    static String downloadName(File source, Preset preset) {
        String name = source.getName();
        if (preset == Preset.ORIGINAL) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String tag = preset == Preset.VGA ? "_vga" : "_2m";
        return stem + tag + ".JPG";
    }

    private static File changeExt(File file, String ext) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return new File(file.getParentFile(), stem + ext);
    }

    private static byte[] readFully(File file) throws IOException {
        long length = file.length();
        if (length > Integer.MAX_VALUE) {
            throw new IOException("File too large");
        }
        byte[] data = new byte[(int) length];
        FileInputStream in = new FileInputStream(file);
        try {
            int offset = 0;
            while (offset < data.length) {
                int n = in.read(data, offset, data.length - offset);
                if (n < 0) {
                    break;
                }
                offset += n;
            }
            if (offset != data.length) {
                throw new IOException("Short read");
            }
            return data;
        } finally {
            in.close();
        }
    }
}
