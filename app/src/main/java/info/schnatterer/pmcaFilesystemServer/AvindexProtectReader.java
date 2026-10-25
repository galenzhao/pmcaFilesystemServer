package info.schnatterer.pmcaFilesystemServer;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Read-only protect flag via Avindex {@code getImageInfo} / {@code DCF_TBLProtectInfo}.
 * Writing protect is not exposed on this firmware's Avindex JNI.
 */
final class AvindexProtectReader {

    static final int PROTECT_OFF = 0;
    static final int PROTECT_ON = 1;
    static final int PROTECT_UNKNOWN = 2;

    private final AvindexGpsWriter gpsWriter;

    AvindexProtectReader(AvindexGpsWriter gpsWriter) {
        this.gpsWriter = gpsWriter;
    }

    /**
     * @return {@link #PROTECT_ON}, {@link #PROTECT_OFF}, or {@link #PROTECT_UNKNOWN}
     */
    int readProtect(File source) {
        if (source == null || !source.exists()) {
            return PROTECT_UNKNOWN;
        }
        try {
            AvindexGpsWriter.ResolvedMedia media = gpsWriter.resolveStill(source);
            if (media == null) {
                return PROTECT_UNKNOWN;
            }
            Uri base = AvindexGpsWriter.mediaBaseUri(media.uri);
            ContentResolver resolver = gpsWriter.getContext().getContentResolver();
            Object info = invokeGetImageInfo(resolver, base, media.id);
            if (info == null) {
                return PROTECT_UNKNOWN;
            }
            Integer value = readProtectAttribute(info);
            if (value == null) {
                return PROTECT_UNKNOWN;
            }
            if (value.intValue() == PROTECT_ON) {
                return PROTECT_ON;
            }
            if (value.intValue() == PROTECT_OFF) {
                return PROTECT_OFF;
            }
            return PROTECT_UNKNOWN;
        } catch (Throwable t) {
            Logger.info("AvindexProtectReader: " + t.getMessage());
            return PROTECT_UNKNOWN;
        }
    }

    static String label(int protect) {
        if (protect == PROTECT_ON) {
            return "protected";
        }
        if (protect == PROTECT_OFF) {
            return "unprotected";
        }
        return "unknown";
    }

    private Object invokeGetImageInfo(ContentResolver resolver, Uri base, long id) throws Exception {
        Method method = findGetImageInfo();
        if (method == null) {
            return null;
        }
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 3
                && ContentResolver.class.equals(params[0])
                && Uri.class.equals(params[1])
                && (params[2] == Long.TYPE || params[2] == Long.class)) {
            return method.invoke(null, resolver, base, Long.valueOf(id));
        }
        if (params.length == 1 && String.class.equals(params[0])) {
            // need uniqId — skip
            return null;
        }
        return null;
    }

    private Integer readProtectAttribute(Object info) {
        String[] tags = new String[]{
                "DCF_TBLProtectInfo",
                "FILE_SYSTEMProtectInfo",
                "AVIEmProtectInfo",
        };
        for (int i = 0; i < tags.length; i++) {
            try {
                Method getInt = info.getClass().getMethod("getAttributeInt", String.class, Integer.TYPE);
                Object value = getInt.invoke(info, tags[i], Integer.valueOf(PROTECT_UNKNOWN));
                if (value instanceof Number) {
                    int v = ((Number) value).intValue();
                    if (v == PROTECT_ON || v == PROTECT_OFF) {
                        return Integer.valueOf(v);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Method findGetImageInfo() {
        try {
            Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
            Method[][] pools = new Method[][]{media.getMethods(), media.getDeclaredMethods()};
            Method best = null;
            for (Method[] pool : pools) {
                for (Method method : pool) {
                    if (!"getImageInfo".equals(method.getName())) {
                        continue;
                    }
                    if (!Modifier.isPublic(method.getModifiers())) {
                        try {
                            method.setAccessible(true);
                        } catch (Throwable ignored) {
                        }
                    }
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 3
                            && ContentResolver.class.equals(params[0])
                            && Uri.class.equals(params[1])) {
                        return method;
                    }
                    if (best == null) {
                        best = method;
                    }
                }
            }
            return best;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
