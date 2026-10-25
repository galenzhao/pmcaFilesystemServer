package info.schnatterer.pmcaFilesystemServer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Still-media ops on the same Avindex JNI bridge as {@link AvindexGpsWriter}:
 * clear GPS, rotate, delete.
 */
final class AvindexMediaWriter {

    /** Firmware {@code Images.Media.RELATIVE_ANGLE_*} */
    static final int ANGLE_PLUS_90 = 0;
    static final int ANGLE_PLUS_180 = 1;
    static final int ANGLE_PLUS_270 = 2;
    static final int ANGLE_MINUS_90 = 3;
    static final int ANGLE_MINUS_180 = 4;
    static final int ANGLE_MINUS_270 = 5;

    private final AvindexGpsWriter gpsWriter;

    AvindexMediaWriter(AvindexGpsWriter gpsWriter) {
        this.gpsWriter = gpsWriter;
    }

    AvindexGpsWriter.Result clearGps(File source) {
        return runStillOp(source, "deleteGps", new StillOp() {
            @Override
            public Object invoke(ContentResolver resolver, Uri baseUri, long id) throws Exception {
                return invokeMediaMethod("deleteGps", resolver, baseUri, id, null);
            }

            @Override
            public void checkResult(Object result) throws IOException {
                if (result instanceof Number && ((Number) result).intValue() == 0) {
                    throw new IOException("deleteGps returned 0");
                }
            }
        });
    }

    AvindexGpsWriter.Result rotate(File source, int relativeAngle) {
        if (relativeAngle < ANGLE_PLUS_90 || relativeAngle > ANGLE_MINUS_270) {
            return new AvindexGpsWriter.Result(false, null, "Invalid rotate angle code: " + relativeAngle, false);
        }
        final int angle = relativeAngle;
        return runStillOp(source, "rotateImage", new StillOp() {
            @Override
            public Object invoke(ContentResolver resolver, Uri baseUri, long id) throws Exception {
                return invokeMediaMethod("rotateImage", resolver, baseUri, id, Integer.valueOf(angle));
            }

            @Override
            public void checkResult(Object result) throws IOException {
                if (result instanceof Boolean && !((Boolean) result).booleanValue()) {
                    throw new IOException("rotateImage returned false");
                }
            }
        });
    }

    AvindexGpsWriter.Result delete(File source) {
        return runStillOp(source, "deleteImage", new StillOp() {
            @Override
            public Object invoke(ContentResolver resolver, Uri baseUri, long id) throws Exception {
                return invokeMediaMethod("deleteImage", resolver, baseUri, id, null);
            }

            @Override
            public void checkResult(Object result) throws IOException {
                if (result instanceof Boolean && !((Boolean) result).booleanValue()) {
                    throw new IOException("deleteImage returned false (protected?)");
                }
            }
        });
    }

    /**
     * Map UI degrees (+90 / -90 / 180 / …) to firmware relative-angle constants.
     */
    static int relativeAngleFromDegrees(int degrees) {
        int d = degrees % 360;
        if (d > 180) {
            d -= 360;
        }
        if (d <= -180) {
            d += 360;
        }
        if (d == 90) {
            return ANGLE_PLUS_90;
        }
        if (d == 180 || d == -180) {
            return ANGLE_PLUS_180;
        }
        if (d == 270 || d == -90) {
            return ANGLE_MINUS_90;
        }
        if (d == -270) {
            return ANGLE_PLUS_90;
        }
        throw new IllegalArgumentException("Unsupported rotate degrees: " + degrees);
    }

    private interface StillOp {
        Object invoke(ContentResolver resolver, Uri baseUri, long id) throws Exception;

        void checkResult(Object result) throws IOException;
    }

    private AvindexGpsWriter.Result runStillOp(File source, String opName, StillOp op) {
        if (source == null) {
            return new AvindexGpsWriter.Result(false, null, "Missing file", false);
        }
        if (!source.exists() || !source.isFile()) {
            // Delete may race after sibling already removed the Avindex row.
            if ("deleteImage".equals(opName) && !source.exists()) {
                return new AvindexGpsWriter.Result(true, source.getPath() + " (already gone)", null, isRaw(source));
            }
            return new AvindexGpsWriter.Result(false, null, "Missing file: " + source.getPath(), isRaw(source));
        }
        String name = source.getName().toLowerCase(Locale.US);
        if (name.endsWith(".mp4") || name.endsWith(".mts") || name.endsWith(".m2ts")) {
            return new AvindexGpsWriter.Result(false, null, "Video unsupported (Avindex still only)", false);
        }
        boolean raw = isRaw(source);
        if (!raw && !name.endsWith(".jpg") && !name.endsWith(".jpeg")) {
            return new AvindexGpsWriter.Result(false, null, "Unsupported still type: " + source.getName(), false);
        }

        Logger.info("AvindexMediaWriter." + opName + " START path=" + source.getAbsolutePath());
        try {
            gpsWriter.refreshDatabase();
            AvindexGpsWriter.ResolvedMedia media = gpsWriter.resolveStill(source);
            if (media == null) {
                return new AvindexGpsWriter.Result(false, null,
                        "Not in Avindex (path not indexed): " + source.getPath(), raw);
            }
            Uri base = AvindexGpsWriter.mediaBaseUri(media.uri);
            ContentResolver resolver = gpsWriter.getContext().getContentResolver();
            Object result = op.invoke(resolver, base, media.id);
            Logger.info("AvindexMediaWriter." + opName + " => " + String.valueOf(result)
                    + " uri=" + media.uri);
            op.checkResult(result);
            gpsWriter.refreshDatabase();
            return new AvindexGpsWriter.Result(true, source.getPath() + " -> " + media.uri, null, raw);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.length() == 0) {
                message = e.getClass().getSimpleName();
            }
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null) {
                message = message + ": " + cause.getMessage();
            }
            Logger.error("AvindexMediaWriter." + opName + " failed for " + source.getPath()
                    + ": " + message, e);
            return new AvindexGpsWriter.Result(false, null, message, raw);
        }
    }

    private static boolean isRaw(File source) {
        if (source == null) {
            return false;
        }
        String name = source.getName().toLowerCase(Locale.US);
        return name.endsWith(".arw") || name.endsWith(".raw");
    }

    /**
     * Prefer firmware signatures:
     * deleteGps(cr, baseUri, long) → int
     * rotateImage(cr, baseUri, long, int) → boolean
     * deleteImage(cr, baseUri, long) → boolean
     */
    private Object invokeMediaMethod(String name, ContentResolver resolver, Uri baseUri, long id,
                                     Integer rotateAngle) throws Exception {
        Method method = findMediaMethod(name, rotateAngle != null);
        if (method == null) {
            throw new IOException("AvindexStore.Images.Media." + name + " not found");
        }
        Class<?>[] params = method.getParameterTypes();
        Logger.info("AvindexMediaWriter.invoke " + method.getDeclaringClass().getName()
                + "." + name + formatParams(params)
                + " base=" + baseUri + " id=" + id
                + (rotateAngle != null ? " angle=" + rotateAngle : ""));
        Object[] args = new Object[params.length];
        int intSlotsUsed = 0;
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            if (ContentResolver.class.equals(p)) {
                args[i] = resolver;
            } else if (Uri.class.equals(p)) {
                args[i] = baseUri;
            } else if (p == Long.TYPE || p == Long.class) {
                args[i] = Long.valueOf(id);
            } else if (p == Integer.TYPE || p == Integer.class) {
                if (rotateAngle != null && intSlotsUsed == 0 && params.length >= 4) {
                    // Prefer last int as rotate when signature is (cr, uri, long, int)
                    boolean laterLong = false;
                    for (int j = i + 1; j < params.length; j++) {
                        if (params[j] == Long.TYPE || params[j] == Long.class) {
                            laterLong = true;
                            break;
                        }
                    }
                    if (!laterLong) {
                        args[i] = rotateAngle;
                        intSlotsUsed++;
                    } else {
                        args[i] = Integer.valueOf((int) id);
                    }
                } else if (rotateAngle != null && i == params.length - 1) {
                    args[i] = rotateAngle;
                } else {
                    args[i] = Integer.valueOf((int) id);
                }
            } else {
                args[i] = null;
            }
        }
        // If rotate signature put id in int and never set angle, fix last int.
        if (rotateAngle != null) {
            for (int i = params.length - 1; i >= 0; i--) {
                if (params[i] == Integer.TYPE || params[i] == Integer.class) {
                    if (!(args[i] instanceof Integer)
                            || ((Integer) args[i]).intValue() == (int) id) {
                        // only overwrite if this looks like the rotate slot (not the id)
                        boolean hasLongId = false;
                        for (int j = 0; j < params.length; j++) {
                            if (params[j] == Long.TYPE || params[j] == Long.class) {
                                hasLongId = true;
                                break;
                            }
                        }
                        if (hasLongId) {
                            args[i] = rotateAngle;
                        }
                    }
                    break;
                }
            }
        }
        return method.invoke(null, args);
    }

    private Method findMediaMethod(String name, boolean wantsRotateInt) {
        String[] owners = new String[]{
                "com.sony.scalar.provider.AvindexStore$Images$Media",
                "com.sony.scalar.provider.AvindexStore$Images",
                "com.sony.scalar.provider.AvindexStore",
        };
        Method best = null;
        for (String owner : owners) {
            Class<?> type;
            try {
                type = Class.forName(owner);
            } catch (ClassNotFoundException e) {
                continue;
            }
            Method[][] pools = new Method[][]{type.getMethods(), type.getDeclaredMethods()};
            for (Method[] pool : pools) {
                for (Method method : pool) {
                    if (!name.equals(method.getName())) {
                        continue;
                    }
                    if (!Modifier.isPublic(method.getModifiers())) {
                        try {
                            method.setAccessible(true);
                        } catch (Throwable ignored) {
                        }
                    }
                    Class<?>[] params = method.getParameterTypes();
                    Logger.info("AvindexMediaWriter candidate " + owner + "." + name
                            + formatParams(params));
                    if (wantsRotateInt
                            && params.length == 4
                            && ContentResolver.class.equals(params[0])
                            && Uri.class.equals(params[1])
                            && (params[2] == Long.TYPE || params[2] == Long.class)
                            && (params[3] == Integer.TYPE || params[3] == Integer.class)) {
                        return method;
                    }
                    if (!wantsRotateInt
                            && params.length == 3
                            && ContentResolver.class.equals(params[0])
                            && Uri.class.equals(params[1])
                            && (params[2] == Long.TYPE || params[2] == Long.class)) {
                        return method;
                    }
                    if (best == null) {
                        best = method;
                    }
                }
            }
        }
        return best;
    }

    private static String formatParams(Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.append(')').toString();
    }
}
