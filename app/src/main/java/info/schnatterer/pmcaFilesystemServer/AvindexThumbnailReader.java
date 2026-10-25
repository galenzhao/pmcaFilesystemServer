package info.schnatterer.pmcaFilesystemServer;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Prefer firmware Avindex screennail/thumbnail over parsing EXIF from the card.
 */
final class AvindexThumbnailReader {

    private final AvindexGpsWriter gpsWriter;

    AvindexThumbnailReader(AvindexGpsWriter gpsWriter) {
        this.gpsWriter = gpsWriter;
    }

    byte[] readJpegThumbnail(File source) {
        if (source == null || !source.exists()) {
            return null;
        }
        try {
            AvindexGpsWriter.ResolvedMedia media = gpsWriter.resolveStill(source);
            if (media == null) {
                return null;
            }
            Uri base = AvindexGpsWriter.mediaBaseUri(media.uri);
            ContentResolver resolver = gpsWriter.getContext().getContentResolver();

            byte[] screen = invokeByteArray("getScreennail", resolver, base, media.id);
            if (screen != null && screen.length > 0) {
                Logger.info("AvindexThumbnailReader screennail " + source.getName()
                        + " bytes=" + screen.length);
                return screen;
            }

            byte[] fromStream = invokeInputStreamBytes("getScreennailInputStream",
                    resolver, base, media.id);
            if (fromStream != null && fromStream.length > 0) {
                return fromStream;
            }

            Bitmap bitmap = invokeThumbnailBitmap(resolver, base, media.id);
            if (bitmap != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
                bitmap.recycle();
                byte[] jpeg = out.toByteArray();
                Logger.info("AvindexThumbnailReader Thumbnails.getThumbnail "
                        + source.getName() + " bytes=" + jpeg.length);
                return jpeg;
            }
        } catch (Throwable t) {
            Logger.info("AvindexThumbnailReader failed for " + source.getPath()
                    + ": " + t.getMessage());
        }
        return null;
    }

    private byte[] invokeByteArray(String name, ContentResolver resolver, Uri base, long id) {
        try {
            Method m = findMediaMethod(name, 3);
            if (m == null) {
                return null;
            }
            Object result = m.invoke(null, resolver, base, Long.valueOf(id));
            if (result instanceof byte[]) {
                return (byte[]) result;
            }
        } catch (Throwable t) {
            Logger.info("AvindexThumbnailReader." + name + ": " + t.getMessage());
        }
        return null;
    }

    private byte[] invokeInputStreamBytes(String name, ContentResolver resolver, Uri base, long id) {
        InputStream in = null;
        try {
            Method m = findMediaMethod(name, 3);
            if (m == null) {
                return null;
            }
            Object result = m.invoke(null, resolver, base, Long.valueOf(id));
            if (!(result instanceof InputStream)) {
                return null;
            }
            in = (InputStream) result;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Logger.info("AvindexThumbnailReader." + name + ": " + t.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Bitmap invokeThumbnailBitmap(ContentResolver resolver, Uri base, long id) {
        try {
            Class<?> thumbs = Class.forName(
                    "com.sony.scalar.provider.AvindexStore$Images$Media$Thumbnails");
            Method getThumbnail = null;
            for (Method m : thumbs.getMethods()) {
                if ("getThumbnail".equals(m.getName())) {
                    getThumbnail = m;
                    break;
                }
            }
            if (getThumbnail == null) {
                return null;
            }
            Class<?>[] params = getThumbnail.getParameterTypes();
            Object[] args = new Object[params.length];
            BitmapFactory.Options options = new BitmapFactory.Options();
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                if (ContentResolver.class.equals(p)) {
                    args[i] = resolver;
                } else if (Uri.class.equals(p)) {
                    args[i] = base;
                } else if (p == Long.TYPE || p == Long.class) {
                    args[i] = Long.valueOf(id);
                } else if (p == Integer.TYPE || p == Integer.class) {
                    // kind: MINI_KIND often 1
                    args[i] = Integer.valueOf(1);
                } else if (BitmapFactory.Options.class.equals(p)) {
                    args[i] = options;
                } else {
                    args[i] = null;
                }
            }
            Object result = getThumbnail.invoke(null, args);
            if (result instanceof Bitmap) {
                return (Bitmap) result;
            }
        } catch (Throwable t) {
            Logger.info("AvindexThumbnailReader.getThumbnail: " + t.getMessage());
        }
        return null;
    }

    private Method findMediaMethod(String name, int arity) {
        String[] owners = new String[]{
                "com.sony.scalar.provider.AvindexStore$Images$Media",
                "com.sony.scalar.provider.AvindexStore$Images",
        };
        for (String owner : owners) {
            try {
                Class<?> type = Class.forName(owner);
                Method[][] pools = new Method[][]{type.getMethods(), type.getDeclaredMethods()};
                for (Method[] pool : pools) {
                    for (Method method : pool) {
                        if (!name.equals(method.getName())) {
                            continue;
                        }
                        if (method.getParameterTypes().length != arity) {
                            continue;
                        }
                        if (!Modifier.isPublic(method.getModifiers())) {
                            try {
                                method.setAccessible(true);
                            } catch (Throwable ignored) {
                            }
                        }
                        return method;
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }
}
