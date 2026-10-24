package info.schnatterer.pmcaFilesystemServer;

import android.net.Uri;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Logs Avindex GPS-related API signatures once so one camera session has full diagnostics.
 */
final class AvindexApiProbe {

    private static boolean probed;

    private AvindexApiProbe() {
    }

    static synchronized void probeOnce() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Logger.info("AvindexApiProbe: start");

            Class<?> store = Class.forName("com.sony.scalar.provider.AvindexStore");
            logNamedMethods(store, "updateGps");
            logNamedMethods(store, "deleteGps");
            logNamedMethods(store, "getExternalMediaIds");
            logNamedMethods(store, "loadMedia");
            logNamedMethods(store, "waitLoadMediaComplete");
            logMediaIds(store);

            try {
                Class<?> images = Class.forName("com.sony.scalar.provider.AvindexStore$Images");
                logNamedMethods(images, "waitAndUpdateDatabase");
                logNamedMethods(images, "updateGps");
                logNamedMethods(images, "deleteGps");
            } catch (ClassNotFoundException e) {
                Logger.info("AvindexApiProbe: Images class missing: " + e.getMessage());
            }

            try {
                Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
                logStaticUriField(media, "EXTERNAL_CONTENT_URI");
                logStaticUriField(media, "INTERNAL_CONTENT_URI");
                logNamedMethods(media, "getContentUri");
                logNamedMethods(media, "updateGps");
                logNamedMethods(media, "deleteGps");
                logNamedMethods(media, "getUniqId");
                logNamedMethods(media, "getImageInfo");
                logNamedMethods(media, "rotateImage");
                logMethodsMatching(media, "gps");
                logMethodsMatching(media, "Gps");
                logMethodsMatching(media, "Uniq");
            } catch (ClassNotFoundException e) {
                Logger.info("AvindexApiProbe: Images.Media missing: " + e.getMessage());
            }

            try {
                Class<?> columns = Class.forName("com.sony.scalar.provider.AvindexStore$Images$ImageColumns");
                logStaticFields(columns);
            } catch (ClassNotFoundException e) {
                Logger.info("AvindexApiProbe: ImageColumns missing: " + e.getMessage());
            }

            try {
                Class<?> param = Class.forName("com.sony.scalar.provider.AvindexGpsParam");
                logConstructors(param);
                logSetters(param);
                logDeclaredMethodsMatching(param, "set");
                logDeclaredMethodsMatching(param, "toArray");
                logDeclaredMethodsMatching(param, "put");
                logStaticFields(param);
            } catch (ClassNotFoundException e) {
                Logger.info("AvindexApiProbe: AvindexGpsParam missing: " + e.getMessage());
            }

            try {
                Class<?> mpr = Class.forName("com.sony.scalar.provider.InfraScalarMprWrapper");
                logNamedMethods(mpr, "updateGps");
                logNamedMethods(mpr, "deleteGps");
                logMethodsMatching(mpr, "Gps");
            } catch (ClassNotFoundException e) {
                Logger.info("AvindexApiProbe: InfraScalarMprWrapper missing (ok if hidden): "
                        + e.getMessage());
            }

            Logger.info("AvindexApiProbe: done");
        } catch (Throwable t) {
            Logger.error("AvindexApiProbe failed: " + t.getClass().getSimpleName() + ": "
                    + t.getMessage(), t);
        }
    }

    private static void logMediaIds(Class<?> store) {
        try {
            Method getIds = store.getMethod("getExternalMediaIds");
            Object idsObj = getIds.invoke(null);
            if (idsObj instanceof String[]) {
                String[] ids = (String[]) idsObj;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ids.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(ids[i]);
                }
                Logger.info("AvindexApiProbe: getExternalMediaIds=[" + sb.toString() + "] count="
                        + ids.length);
            } else {
                Logger.info("AvindexApiProbe: getExternalMediaIds type="
                        + (idsObj == null ? "null" : idsObj.getClass().getName()));
            }
        } catch (Throwable t) {
            Logger.info("AvindexApiProbe: getExternalMediaIds invoke failed: " + t.getMessage());
        }
    }

    private static void logStaticUriField(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            Object value = field.get(null);
            Logger.info("AvindexApiProbe: " + type.getSimpleName() + "." + name + " = "
                    + String.valueOf(value)
                    + (value instanceof Uri ? " (Uri)" : ""));
        } catch (Throwable t) {
            Logger.info("AvindexApiProbe: " + type.getSimpleName() + "." + name + " missing: "
                    + t.getMessage());
        }
    }

    private static void logNamedMethods(Class<?> type, String exactName) {
        int found = 0;
        for (Method method : type.getMethods()) {
            if (!exactName.equals(method.getName())) {
                continue;
            }
            found++;
            Logger.info("AvindexApiProbe: " + formatMethod(type, method));
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!exactName.equals(method.getName())) {
                continue;
            }
            if (Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            found++;
            Logger.info("AvindexApiProbe: (declared) " + formatMethod(type, method));
        }
        if (found == 0) {
            Logger.info("AvindexApiProbe: " + type.getName() + "." + exactName + " — none");
        }
    }

    private static void logMethodsMatching(Class<?> type, String needle) {
        for (Method method : type.getMethods()) {
            if (method.getName().contains(needle)) {
                Logger.info("AvindexApiProbe: match " + formatMethod(type, method));
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().contains(needle)) {
                continue;
            }
            if (Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            Logger.info("AvindexApiProbe: match (declared) " + formatMethod(type, method));
        }
    }

    private static void logDeclaredMethodsMatching(Class<?> type, String prefix) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().startsWith(prefix)) {
                continue;
            }
            Logger.info("AvindexApiProbe: (declared) " + formatMethod(type, method));
        }
    }

    private static void logSetters(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (!method.getName().startsWith("set") || method.getParameterTypes().length != 1) {
                continue;
            }
            Logger.info("AvindexApiProbe: " + formatMethod(type, method));
        }
    }

    private static String formatMethod(Class<?> type, Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(type.getName()).append('.').append(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getName());
        }
        sb.append(") -> ").append(method.getReturnType().getName());
        if (Modifier.isStatic(method.getModifiers())) {
            sb.append(" [static]");
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            sb.append(" [mod=").append(Modifier.toString(method.getModifiers())).append(']');
        }
        return sb.toString();
    }

    private static void logConstructors(Class<?> type) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            StringBuilder sb = new StringBuilder();
            sb.append(type.getName()).append(".<init>(");
            Class<?>[] params = ctor.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i].getName());
            }
            sb.append(") [mod=").append(Modifier.toString(ctor.getModifiers())).append(']');
            Logger.info("AvindexApiProbe: " + sb.toString());
        }
    }

    private static void logStaticFields(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                Logger.info("AvindexApiProbe: " + type.getSimpleName() + "." + field.getName()
                        + " = " + String.valueOf(value));
            } catch (Throwable e) {
                Logger.info("AvindexApiProbe: " + type.getSimpleName() + "." + field.getName()
                        + " (inaccessible: " + e.getMessage() + ")");
            }
        }
    }
}
