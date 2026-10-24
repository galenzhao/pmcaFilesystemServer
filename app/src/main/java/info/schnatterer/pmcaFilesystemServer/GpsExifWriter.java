package info.schnatterer.pmcaFilesystemServer;

import android.media.ExifInterface;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Read-only GPS helpers for gallery EXIF display.
 * Writes go through {@link AvindexGpsWriter} (firmware Avindex path).
 */
final class GpsExifWriter {

    private GpsExifWriter() {
    }

    static boolean isJpeg(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    /**
     * Prefer JPEG for EXIF GPS preview; for ARW use sibling JPG when present.
     */
    static File resolveReadableJpeg(File file) {
        if (isJpeg(file)) {
            return file;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String base = name.substring(0, dot);
        File parent = file.getParentFile();
        if (parent == null) {
            return null;
        }
        File upper = new File(parent, base + ".JPG");
        if (upper.exists() && upper.isFile()) {
            return upper;
        }
        File lower = new File(parent, base + ".jpg");
        if (lower.exists() && lower.isFile()) {
            return lower;
        }
        return null;
    }

    static float[] readLatLong(File jpeg) {
        if (jpeg == null || !isJpeg(jpeg) || !jpeg.exists()) {
            return null;
        }
        try {
            ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
            float[] latLong = new float[2];
            if (exif.getLatLong(latLong)) {
                return latLong;
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
