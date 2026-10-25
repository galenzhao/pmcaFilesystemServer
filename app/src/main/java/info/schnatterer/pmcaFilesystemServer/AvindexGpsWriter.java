package info.schnatterer.pmcaFilesystemServer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.github.ma1co.openmemories.framework.ImageInfo;
import com.github.ma1co.openmemories.framework.MediaManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes GPS into still images (JPEG / ARW) via Sony AvindexStore.updateGps (firmware path).
 */
final class AvindexGpsWriter {

    private static final Pattern DCF_FOLDER = Pattern.compile("(\\d{3})[A-Za-z0-9]{5}");
    private static final Pattern DCF_FILE = Pattern.compile("([A-Za-z]{3,4})(\\d{4})\\.", Pattern.CASE_INSENSITIVE);

    static final class Result {
        final boolean success;
        final String written;
        final String error;
        final boolean raw;

        Result(boolean success, String written, String error, boolean raw) {
            this.success = success;
            this.written = written;
            this.error = error;
            this.raw = raw;
        }
    }

    private final Context context;

    AvindexGpsWriter(Context context) {
        this.context = context.getApplicationContext();
    }

    Result write(File source, double latitude, double longitude, Double altitude) {
        if (source == null) {
            return new Result(false, null, "Missing file", false);
        }
        if (!source.exists() || !source.isFile()) {
            return new Result(false, null, "Missing file: " + source.getPath(), false);
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return new Result(false, null, "Invalid coordinates", false);
        }

        String name = source.getName().toLowerCase(Locale.US);
        if (name.endsWith(".mp4") || name.endsWith(".mts") || name.endsWith(".m2ts")) {
            return new Result(false, null, "Video unsupported (Avindex still GPS only)", false);
        }
        boolean raw = name.endsWith(".arw");
        if (!raw && !name.endsWith(".jpg") && !name.endsWith(".jpeg")) {
            return new Result(false, null, "Unsupported still type: " + source.getName(), false);
        }

        Logger.info("AvindexGpsWriter.write START path=" + source.getAbsolutePath()
                + " size=" + source.length()
                + " mtime=" + source.lastModified()
                + " lat=" + latitude + " lon=" + longitude
                + " alt=" + (altitude == null ? "null" : String.valueOf(altitude))
                + " raw=" + raw);

        try {
            refreshDatabase();
            ResolvedMedia media = resolveStill(source);
            if (media == null) {
                Logger.error("AvindexGpsWriter.write FAIL resolve: Not in Avindex for "
                        + source.getPath());
                return new Result(false, null,
                        "Not in Avindex (path not indexed): " + source.getPath(), raw);
            }

            Logger.info("AvindexGpsWriter.write resolved uri=" + media.uri
                    + " id=" + media.id
                    + " base=" + mediaBaseUri(media.uri));
            logRowSnapshot("BEFORE", media.uri, media.id);
            logUniqId(media.uri, media.id);

            ContentValues values = buildGpsValues(latitude, longitude, altitude);
            logContentValues(values);
            invokeUpdateGps(media.uri, values, media.id);

            logRowSnapshot("AFTER", media.uri, media.id);
            // Force index refresh then re-read
            refreshDatabase();
            logRowSnapshot("AFTER_REFRESH", media.uri, media.id);

            Logger.info("AvindexGpsWriter.write OK " + source.getPath() + " -> " + media.uri);
            return new Result(true, source.getPath() + " -> " + media.uri, null, raw);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.length() == 0) {
                message = e.getClass().getSimpleName();
            }
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null) {
                message = message + ": " + cause.getMessage();
            }
            Logger.error("AvindexGpsWriter failed for " + source.getPath() + ": " + message, e);
            return new Result(false, null, message, raw);
        }
    }

    void refreshDatabase() {
        try {
            Class<?> store = Class.forName("com.sony.scalar.provider.AvindexStore");
            Method getIds = store.getMethod("getExternalMediaIds");
            Object idsObj = getIds.invoke(null);
            if (!(idsObj instanceof String[]) || ((String[]) idsObj).length == 0) {
                Logger.info("AvindexGpsWriter.refreshDatabase: no external media ids");
                tryWaitUpdate(null);
                return;
            }
            String[] ids = (String[]) idsObj;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ids.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ids[i]);
            }
            Logger.info("AvindexGpsWriter.refreshDatabase mediaIds=[" + sb.toString() + "]");
            for (String id : ids) {
                tryWaitUpdate(id);
            }
        } catch (Throwable t) {
            Logger.info("AvindexGpsWriter.refreshDatabase skipped: " + t.getMessage());
        }
    }

    private void tryWaitUpdate(String mediaId) {
        try {
            Class<?> images = Class.forName("com.sony.scalar.provider.AvindexStore$Images");
            ContentResolver resolver = context.getContentResolver();
            if (mediaId != null) {
                Method wait = images.getMethod("waitAndUpdateDatabase", ContentResolver.class, String.class);
                Object ret = wait.invoke(null, resolver, mediaId);
                Logger.info("AvindexGpsWriter.waitAndUpdateDatabase mediaId=" + mediaId
                        + " => " + String.valueOf(ret));
            } else {
                try {
                    Method wait = images.getMethod("waitAndUpdateDatabase", ContentResolver.class);
                    Object ret = wait.invoke(null, resolver);
                    Logger.info("AvindexGpsWriter.waitAndUpdateDatabase(cr) => "
                            + String.valueOf(ret));
                } catch (NoSuchMethodException ignored) {
                    Logger.info("AvindexGpsWriter.waitAndUpdateDatabase(cr) not present");
                }
            }
        } catch (Throwable t) {
            Logger.info("AvindexGpsWriter.waitAndUpdateDatabase: " + t.getMessage());
        }
    }

    private void logContentValues(ContentValues values) {
        if (values == null) {
            Logger.info("AvindexGpsWriter.ContentValues: null");
            return;
        }
        StringBuilder sb = new StringBuilder("AvindexGpsWriter.ContentValues{");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> e : values.valueSet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('}');
        Logger.info(sb.toString());
    }

    private void logRowSnapshot(String label, Uri itemUri, long id) {
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(itemUri, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                Logger.info("AvindexGpsWriter.snapshot " + label + " empty uri=" + itemUri);
                return;
            }
            String[] want = new String[]{
                    "_id", "_data", "dcf_folder_number", "dcf_file_number",
                    "has_gps_info", "latitude", "longitude", "content_type",
                    "exist_jpeg", "exist_raw"
            };
            StringBuilder sb = new StringBuilder("AvindexGpsWriter.snapshot ")
                    .append(label).append(" id=").append(id).append(" {");
            for (int i = 0; i < want.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                int idx = cursor.getColumnIndex(want[i]);
                sb.append(want[i]).append('=');
                if (idx < 0) {
                    sb.append('?');
                } else {
                    try {
                        sb.append(cursor.getString(idx));
                    } catch (Exception e) {
                        sb.append("err");
                    }
                }
            }
            sb.append('}');
            Logger.info(sb.toString());
        } catch (Throwable t) {
            Logger.info("AvindexGpsWriter.snapshot " + label + " failed: " + t.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void logUniqId(Uri itemUri, long id) {
        try {
            Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
            Method getUniqId = null;
            for (Method m : media.getMethods()) {
                if ("getUniqId".equals(m.getName())) {
                    getUniqId = m;
                    Logger.info("AvindexGpsWriter.getUniqId sig"
                            + formatParams(m.getParameterTypes()));
                    break;
                }
            }
            if (getUniqId == null) {
                for (Method m : media.getDeclaredMethods()) {
                    if ("getUniqId".equals(m.getName())) {
                        getUniqId = m;
                        getUniqId.setAccessible(true);
                        Logger.info("AvindexGpsWriter.getUniqId (declared) sig"
                                + formatParams(m.getParameterTypes()));
                        break;
                    }
                }
            }
            if (getUniqId == null) {
                Logger.info("AvindexGpsWriter.getUniqId not found");
                return;
            }
            Class<?>[] params = getUniqId.getParameterTypes();
            Object[] args = new Object[params.length];
            ContentResolver resolver = context.getContentResolver();
            Uri base = mediaBaseUri(itemUri);
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                if (ContentResolver.class.equals(p)) {
                    args[i] = resolver;
                } else if (Uri.class.equals(p)) {
                    args[i] = base;
                } else if (p == Long.TYPE || p == Long.class) {
                    args[i] = Long.valueOf(id);
                } else if (p == Integer.TYPE || p == Integer.class) {
                    args[i] = Integer.valueOf((int) id);
                } else {
                    args[i] = null;
                }
            }
            Object uniq = getUniqId.invoke(null, args);
            Logger.info("AvindexGpsWriter.uniqId=" + String.valueOf(uniq));
        } catch (Throwable t) {
            Logger.info("AvindexGpsWriter.getUniqId failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static final class ResolvedMedia {
        final Uri uri;
        final long id;

        ResolvedMedia(Uri uri, long id) {
            this.uri = uri;
            this.id = id;
        }
    }

    ResolvedMedia resolveStill(File source) throws Exception {
        String absolute;
        try {
            absolute = source.getCanonicalPath();
        } catch (IOException e) {
            absolute = source.getAbsolutePath();
        }
        String basename = source.getName();
        String folderName = source.getParentFile() != null ? source.getParentFile().getName() : "";

        Logger.info("AvindexGpsWriter.resolve path=" + absolute
                + " folder=" + folderName + " file=" + basename);

        // 1) OpenMemories MediaManager (uses EXTERNAL_CONTENT_URI correctly)
        ResolvedMedia viaManager = resolveViaMediaManager(basename, folderName, absolute);
        if (viaManager != null) {
            Logger.info("AvindexGpsWriter resolved via MediaManager: " + viaManager.uri);
            return viaManager;
        }

        // 2) Direct EXTERNAL_CONTENT_URI + path/DCF matching
        Uri base = externalContentUri();
        if (base == null) {
            Logger.info("AvindexGpsWriter: EXTERNAL_CONTENT_URI missing");
            return null;
        }
        Logger.info("AvindexGpsWriter baseUri=" + base);

        ContentResolver resolver = context.getContentResolver();
        dumpSampleRows(resolver, base);

        for (String candidate : pathCandidates(absolute)) {
            ResolvedMedia m = queryByDataEquals(resolver, base, candidate);
            if (m != null) {
                Logger.info("AvindexGpsWriter resolved by DATA=" + candidate + " -> " + m.uri);
                return m;
            }
        }

        ResolvedMedia byDcf = queryByDcf(resolver, base, folderName, basename);
        if (byDcf != null) {
            Logger.info("AvindexGpsWriter resolved by DCF -> " + byDcf.uri);
            return byDcf;
        }

        ResolvedMedia byScan = scanMatch(resolver, base, basename, folderName, absolute);
        if (byScan != null) {
            Logger.info("AvindexGpsWriter resolved by scan -> " + byScan.uri);
            return byScan;
        }

        // 3) Also try getContentUri(mediaId) as last resort
        try {
            Class<?> store = Class.forName("com.sony.scalar.provider.AvindexStore");
            Method getIds = store.getMethod("getExternalMediaIds");
            Object idsObj = getIds.invoke(null);
            if (idsObj instanceof String[]) {
                Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
                Method getContentUri = media.getMethod("getContentUri", String.class);
                for (String mediaId : (String[]) idsObj) {
                    Uri mediaUri = (Uri) getContentUri.invoke(null, mediaId);
                    if (mediaUri == null) {
                        continue;
                    }
                    Logger.info("AvindexGpsWriter try mediaId=" + mediaId + " uri=" + mediaUri);
                    dumpSampleRows(resolver, mediaUri);
                    ResolvedMedia m = scanMatch(resolver, mediaUri, basename, folderName, absolute);
                    if (m != null) {
                        return m;
                    }
                    m = queryByDcf(resolver, mediaUri, folderName, basename);
                    if (m != null) {
                        return m;
                    }
                }
            }
        } catch (Throwable t) {
            Logger.info("AvindexGpsWriter mediaId fallback: " + t.getMessage());
        }

        return null;
    }

    private ResolvedMedia resolveViaMediaManager(String basename, String folderName, String absolute) {
        Cursor cursor = null;
        try {
            MediaManager manager = MediaManager.create(context);
            cursor = manager.queryImages();
            if (cursor == null) {
                Logger.info("AvindexGpsWriter MediaManager.queryImages returned null");
                return null;
            }
            Integer wantFolder = parseFolderNumber(folderName);
            Integer wantFile = parseFileNumber(basename);
            // RAW+JPEG share one Avindex row; ImageInfo filename is usually the .JPG.
            Set<String> nameAliases = filenameAliases(basename);
            int scanned = 0;
            while (cursor.moveToNext()) {
                scanned++;
                ImageInfo info = manager.getImageInfo(cursor);
                String file = info.getFilename();
                String folder = info.getFolder();
                if (file == null) {
                    continue;
                }
                boolean folderMatch = folder == null || folder.length() == 0
                        || folder.equalsIgnoreCase(folderName);
                boolean fileMatch = nameAliases.contains(file.toLowerCase(Locale.US));
                if (fileMatch && folderMatch) {
                    long id = manager.getImageId(cursor);
                    Uri uri = ContentUris.withAppendedId(manager.getImageContentUri(), id);
                    Logger.info("AvindexGpsWriter MediaManager name match file=" + file
                            + " for " + basename);
                    return new ResolvedMedia(uri, id);
                }
            }
            // Second pass: DCF numbers (handles ARW when info filename is JPG)
            if (wantFolder != null && wantFile != null) {
                cursor.moveToPosition(-1);
                while (cursor.moveToNext()) {
                    ImageInfo info = manager.getImageInfo(cursor);
                    String file = info.getFilename();
                    String folder = info.getFolder();
                    Integer gotFolder = parseFolderNumber(folder != null ? folder : folderName);
                    Integer gotFile = parseFileNumber(file != null ? file : "");
                    if (wantFolder.equals(gotFolder) && wantFile.equals(gotFile)) {
                        long id = manager.getImageId(cursor);
                        Uri uri = ContentUris.withAppendedId(manager.getImageContentUri(), id);
                        Logger.info("AvindexGpsWriter MediaManager DCF match "
                                + wantFolder + "/" + wantFile + " via file=" + file);
                        return new ResolvedMedia(uri, id);
                    }
                }
            }
            Logger.info("AvindexGpsWriter MediaManager scanned " + scanned
                    + " images, no match for " + folderName + "/" + basename
                    + " aliases=" + nameAliases);
        } catch (Throwable t) {
            Logger.info("AvindexGpsWriter MediaManager failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /** Basenames Avindex may expose for the same DCF still (JPEG+RAW = one row). */
    private static Set<String> filenameAliases(String basename) {
        Set<String> set = new LinkedHashSet<String>();
        if (basename == null) {
            return set;
        }
        String lower = basename.toLowerCase(Locale.US);
        set.add(lower);
        set.add(stripExtension(lower));
        int dot = lower.lastIndexOf('.');
        String stem = dot > 0 ? lower.substring(0, dot) : lower;
        String[] exts = new String[]{".jpg", ".jpeg", ".arw", ".raw"};
        for (int i = 0; i < exts.length; i++) {
            set.add(stem + exts[i]);
        }
        return set;
    }

    private Uri externalContentUri() {
        try {
            Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
            Field field = media.getField("EXTERNAL_CONTENT_URI");
            return (Uri) field.get(null);
        } catch (Throwable t) {
            Logger.info("AvindexGpsWriter EXTERNAL_CONTENT_URI: " + t.getMessage());
            return null;
        }
    }

    private List<String> pathCandidates(String absolute) {
        Set<String> set = new LinkedHashSet<String>();
        set.add(absolute);
        set.add(absolute.replace("/mnt/sdcard", "/storage/sdcard0"));
        set.add(absolute.replace("/storage/sdcard0", "/mnt/sdcard"));
        set.add(absolute.replace("/mnt/sdcard", "/storage/sdcard1"));
        set.add(absolute.replace("/storage/sdcard1", "/mnt/sdcard"));
        // Sometimes Avindex stores without leading mount or with trailing quirks
        int dcim = absolute.toUpperCase(Locale.US).indexOf("/DCIM/");
        if (dcim >= 0) {
            set.add(absolute.substring(dcim)); // /DCIM/100MSDCF/...
            set.add("DCIM" + absolute.substring(dcim + 5));
        }
        return new ArrayList<String>(set);
    }

    private ResolvedMedia queryByDataEquals(ContentResolver resolver, Uri base, String dataValue) {
        String[] dataColumns = new String[]{"DATA", "_data"};
        String idColumn = firstExistingColumn(resolver, base, new String[]{"_ID", "_id"});
        if (idColumn == null) {
            return null;
        }
        for (String dataColumn : dataColumns) {
            Cursor cursor = null;
            try {
                cursor = resolver.query(base, new String[]{idColumn, dataColumn},
                        dataColumn + "=?", new String[]{dataValue}, null);
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    return new ResolvedMedia(ContentUris.withAppendedId(base, id), id);
                }
            } catch (Exception e) {
                Logger.info("AvindexGpsWriter DATA query (" + dataColumn + "=" + dataValue
                        + ") failed: " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return null;
    }

    private ResolvedMedia queryByDcf(ContentResolver resolver, Uri base, String folderName,
                                     String basename) {
        Integer folderNum = parseFolderNumber(folderName);
        Integer fileNum = parseFileNumber(basename);
        if (folderNum == null || fileNum == null) {
            Logger.info("AvindexGpsWriter DCF parse failed folder=" + folderName
                    + " file=" + basename);
            return null;
        }
        Logger.info("AvindexGpsWriter DCF lookup folder=" + folderNum + " file=" + fileNum);
        String idColumn = firstExistingColumn(resolver, base, new String[]{"_ID", "_id"});
        if (idColumn == null) {
            return null;
        }

        // Real ImageColumns values are lowercase (see AvindexApiProbe).
        String[][] pairs = new String[][]{
                {"dcf_folder_number", "dcf_file_number"},
                {"DCF_FOLDER_NUMBER", "DCF_FILE_NUMBER"},
                {"KEY_FOLDER_NUMBER", "KEY_FILE_NUMBER"},
        };
        for (String[] pair : pairs) {
            Cursor cursor = null;
            try {
                cursor = resolver.query(base, new String[]{idColumn, pair[0], pair[1]},
                        pair[0] + "=? AND " + pair[1] + "=?",
                        new String[]{String.valueOf(folderNum), String.valueOf(fileNum)},
                        null);
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    Logger.info("AvindexGpsWriter DCF query hit via " + pair[0] + " id=" + id);
                    return new ResolvedMedia(ContentUris.withAppendedId(base, id), id);
                }
            } catch (Exception e) {
                Logger.info("AvindexGpsWriter DCF query " + pair[0] + "/" + pair[1]
                        + " failed: " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        // Provider may ignore selection — scan by DCF columns.
        return scanByDcfNumbers(resolver, base, folderNum.intValue(), fileNum.intValue());
    }

    private ResolvedMedia scanByDcfNumbers(ContentResolver resolver, Uri base,
                                           int folderNum, int fileNum) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(base, null, null, null, null);
            if (cursor == null) {
                return null;
            }
            String[] names = cursor.getColumnNames();
            int idIdx = indexOf(names, new String[]{"_ID", "_id"});
            int folderIdx = indexOf(names, new String[]{"dcf_folder_number", "DCF_FOLDER_NUMBER"});
            int fileIdx = indexOf(names, new String[]{"dcf_file_number", "DCF_FILE_NUMBER"});
            if (idIdx < 0 || folderIdx < 0 || fileIdx < 0) {
                Logger.info("AvindexGpsWriter DCF scan missing columns");
                return null;
            }
            while (cursor.moveToNext()) {
                if (cursor.getInt(folderIdx) == folderNum && cursor.getInt(fileIdx) == fileNum) {
                    long id = cursor.getLong(idIdx);
                    Logger.info("AvindexGpsWriter DCF scan hit id=" + id);
                    return new ResolvedMedia(ContentUris.withAppendedId(base, id), id);
                }
            }
        } catch (Exception e) {
            Logger.info("AvindexGpsWriter DCF scan failed: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private ResolvedMedia scanMatch(ContentResolver resolver, Uri base, String basename,
                                    String folderName, String absolute) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(base, null, null, null, null);
            if (cursor == null) {
                Logger.info("AvindexGpsWriter scan: cursor null for " + base);
                return null;
            }
            String[] names = cursor.getColumnNames();
            Logger.info("AvindexGpsWriter scan columns=" + join(names));
            int idIdx = indexOf(names, new String[]{"_ID", "_id"});
            if (idIdx < 0) {
                Logger.info("AvindexGpsWriter scan: no _ID column");
                return null;
            }
            int dataIdx = indexOf(names, new String[]{"DATA", "_data"});
            int folderNumIdx = indexOf(names, new String[]{"dcf_folder_number", "DCF_FOLDER_NUMBER"});
            int fileNumIdx = indexOf(names, new String[]{"dcf_file_number", "DCF_FILE_NUMBER"});
            int fileIdx = indexOf(names, new String[]{
                    "DCF_TBL_FILE_NAME", "DCF_TBLFileName", "DISPLAY_NAME", "_display_name"});
            int dirIdx = indexOf(names, new String[]{
                    "DCF_TBL_DIR_NAME", "DCF_TBLDirName", "BUCKET_DISPLAY_NAME"});

            Set<String> aliases = filenameAliases(basename);
            Integer wantFolder = parseFolderNumber(folderName);
            Integer wantFile = parseFileNumber(basename);
            String lowerBase = basename.toLowerCase(Locale.US);
            String lowerFolder = folderName.toLowerCase(Locale.US);
            int rows = 0;
            while (cursor.moveToNext()) {
                rows++;
                if (wantFolder != null && wantFile != null
                        && folderNumIdx >= 0 && fileNumIdx >= 0) {
                    if (cursor.getInt(folderNumIdx) == wantFolder.intValue()
                            && cursor.getInt(fileNumIdx) == wantFile.intValue()) {
                        long id = cursor.getLong(idIdx);
                        return new ResolvedMedia(ContentUris.withAppendedId(base, id), id);
                    }
                }
                if (dataIdx >= 0) {
                    String data = cursor.getString(dataIdx);
                    if (data != null) {
                        String lowerData = data.toLowerCase(Locale.US);
                        if (lowerData.equals(absolute.toLowerCase(Locale.US))
                                || lowerData.endsWith("/" + lowerBase)
                                || pathCandidates(absolute).contains(data)) {
                            long id = cursor.getLong(idIdx);
                            return new ResolvedMedia(ContentUris.withAppendedId(base, id), id);
                        }
                        // Match relative DCIM suffix
                        if (lowerData.contains("/dcim/")
                                && lowerData.endsWith("/" + lowerFolder + "/" + lowerBase)) {
                            long id = cursor.getLong(idIdx);
                            return new ResolvedMedia(ContentUris.withAppendedId(base, id), id);
                        }
                    }
                }
                if (fileIdx >= 0) {
                    String file = cursor.getString(fileIdx);
                    if (file != null && aliases.contains(file.toLowerCase(Locale.US))) {
                        boolean folderOk = true;
                        if (dirIdx >= 0) {
                            String dir = cursor.getString(dirIdx);
                            folderOk = dir == null || dir.equalsIgnoreCase(folderName);
                        }
                        if (folderOk) {
                            long id = cursor.getLong(idIdx);
                            return new ResolvedMedia(ContentUris.withAppendedId(base, id), id);
                        }
                    }
                }
            }
            Logger.info("AvindexGpsWriter scan rows=" + rows + " no match");
        } catch (Exception e) {
            Logger.info("AvindexGpsWriter scan failed: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private void dumpSampleRows(ContentResolver resolver, Uri base) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(base, null, null, null, null);
            if (cursor == null) {
                Logger.info("AvindexGpsWriter dump: null cursor " + base);
                return;
            }
            String[] names = cursor.getColumnNames();
            Logger.info("AvindexGpsWriter dump " + base + " columns=" + join(names)
                    + " count=" + cursor.getCount());
            int n = 0;
            while (cursor.moveToNext() && n < 3) {
                StringBuilder row = new StringBuilder("row").append(n).append("{");
                for (int i = 0; i < names.length && i < 12; i++) {
                    if (i > 0) {
                        row.append(", ");
                    }
                    row.append(names[i]).append('=');
                    try {
                        row.append(cursor.getString(i));
                    } catch (Exception e) {
                        row.append('?');
                    }
                }
                row.append('}');
                Logger.info("AvindexGpsWriter " + row.toString());
                n++;
            }
        } catch (Exception e) {
            Logger.info("AvindexGpsWriter dump failed: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private String firstExistingColumn(ContentResolver resolver, Uri base, String[] candidates) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(base, null, null, null, null);
            if (cursor == null) {
                return null;
            }
            String[] names = cursor.getColumnNames();
            int idx = indexOf(names, candidates);
            return idx >= 0 ? names[idx] : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static int indexOf(String[] names, String[] candidates) {
        if (names == null) {
            return -1;
        }
        for (String candidate : candidates) {
            for (int i = 0; i < names.length; i++) {
                if (candidate.equalsIgnoreCase(names[i])) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String join(String[] names) {
        if (names == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(names[i]);
        }
        return sb.toString();
    }

    /**
     * Group key for RAW+JPEG siblings that share one Avindex row.
     * Same DCF folder/file number → same key; otherwise the absolute path.
     */
    static String stillGroupKey(File file) {
        if (file == null) {
            return "";
        }
        String folder = file.getParentFile() != null ? file.getParentFile().getName() : "";
        Integer folderNum = parseFolderNumber(folder);
        Integer fileNum = parseFileNumber(file.getName());
        if (folderNum != null && fileNum != null) {
            return "dcf:" + folderNum + "/" + fileNum;
        }
        try {
            return "path:" + file.getCanonicalPath();
        } catch (IOException e) {
            return "path:" + file.getAbsolutePath();
        }
    }

    /** Lower = preferred write target when several siblings are selected (JPG before ARW). */
    static int stillWritePreference(File file) {
        if (file == null) {
            return 100;
        }
        String name = file.getName().toLowerCase(Locale.US);
        if (name.endsWith(".jpg")) {
            return 0;
        }
        if (name.endsWith(".jpeg")) {
            return 1;
        }
        if (name.endsWith(".arw") || name.endsWith(".raw")) {
            return 2;
        }
        return 50;
    }

    static Integer parseFolderNumber(String folderName) {
        if (folderName == null) {
            return null;
        }
        Matcher m = DCF_FOLDER.matcher(folderName);
        if (m.find()) {
            return Integer.valueOf(m.group(1));
        }
        return null;
    }

    static Integer parseFileNumber(String basename) {
        if (basename == null) {
            return null;
        }
        Matcher m = DCF_FILE.matcher(basename);
        if (m.find()) {
            return Integer.valueOf(m.group(2));
        }
        return null;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private ContentValues buildGpsValues(double latitude, double longitude, Double altitude) {
        ContentValues values = new ContentValues();
        values.put("GPSLatitude", toDms(Math.abs(latitude)));
        values.put("GPSLatitudeRef", latitude >= 0 ? "N" : "S");
        values.put("GPSLongitude", toDms(Math.abs(longitude)));
        values.put("GPSLongitudeRef", longitude >= 0 ? "E" : "W");

        double alt = altitude != null ? altitude : 0.0;
        values.put("GPSAltitude", toRational(Math.abs(alt), 100));
        values.put("GPSAltitudeRef", alt >= 0 ? "0" : "1");

        values.put("GPSImgDirection", "0/1");
        values.put("GPSImgDirectionRef", "T");
        values.put("GPSSpeed", "0/1");
        values.put("GPSSpeedRef", "K");
        values.put("GPSTrack", "0/1");
        values.put("GPSTrackRef", "T");

        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        values.put("GPSDateStamp", String.format(Locale.US, "%04d:%02d:%02d",
                utc.get(Calendar.YEAR),
                utc.get(Calendar.MONTH) + 1,
                utc.get(Calendar.DAY_OF_MONTH)));
        // Firmware pattern: H/1:M/1:S/1 (colons), not EXIF-comma form.
        values.put("GPSTimeStamp", String.format(Locale.US, "%d/1:%d/1:%d/1",
                utc.get(Calendar.HOUR_OF_DAY),
                utc.get(Calendar.MINUTE),
                utc.get(Calendar.SECOND)));
        values.put("GPSStatus", "A");
        values.put("GPSMeasureMode", "2");
        values.put("GPSVersionID", "2.3.0.0");
        values.put("GPSMapDatum", "WGS-84");
        return values;
    }

    private void invokeUpdateGps(Uri itemUri, ContentValues values, long id) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri baseUri = mediaBaseUri(itemUri);

        // Real signature (firmware):
        // AvindexStore.Images.Media.updateGps(ContentResolver, Uri baseUri, long origId, ContentValues)
        Method updateGps = findUpdateGps();
        if (updateGps == null) {
            throw new IOException("AvindexStore.Images.Media.updateGps not found");
        }

        Class<?>[] params = updateGps.getParameterTypes();
        Logger.info("AvindexGpsWriter.invoke using " + updateGps.getDeclaringClass().getName()
                + ".updateGps" + formatParams(params)
                + " base=" + baseUri + " id=" + id + " item=" + itemUri);
        Object[] args = buildUpdateArgs(params, resolver, baseUri, itemUri, values, id);
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            String shown;
            if (a instanceof ContentValues) {
                shown = "ContentValues(size=" + ((ContentValues) a).size() + ")";
            } else {
                shown = String.valueOf(a);
            }
            Logger.info("AvindexGpsWriter.invoke arg[" + i + "]="
                    + (a == null ? "null" : a.getClass().getSimpleName()) + ":" + shown);
        }
        Object result = updateGps.invoke(null, args);
        Logger.info("AvindexGpsWriter.updateGps => " + String.valueOf(result)
                + " type=" + (result == null ? "null" : result.getClass().getName()));
        if (result instanceof Number && ((Number) result).intValue() == 0) {
            throw new IOException("updateGps returned 0 (rejected ContentValues or uniqId)");
        }
    }

    static Uri mediaBaseUri(Uri itemUri) {
        if (itemUri == null) {
            return null;
        }
        String s = itemUri.toString();
        int slash = s.lastIndexOf('/');
        if (slash <= 0) {
            return itemUri;
        }
        return Uri.parse(s.substring(0, slash));
    }

    Context getContext() {
        return context;
    }

    private Method findUpdateGps() {
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
                    if (!"updateGps".equals(method.getName())) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    Logger.info("AvindexGpsWriter candidate " + owner + ".updateGps"
                            + formatParams(params));
                    if (!Modifier.isPublic(method.getModifiers())) {
                        try {
                            method.setAccessible(true);
                        } catch (Throwable ignored) {
                        }
                    }
                    // Prefer (ContentResolver, Uri, long, ContentValues)
                    if (params.length == 4
                            && ContentResolver.class.equals(params[0])
                            && Uri.class.equals(params[1])
                            && (params[2] == Long.TYPE || params[2] == Long.class)
                            && ContentValues.class.equals(params[3])) {
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

    private Object[] buildUpdateArgs(Class<?>[] params, ContentResolver resolver, Uri baseUri,
                                     Uri itemUri, ContentValues values, long id) throws Exception {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            if (ContentResolver.class.equals(p)) {
                args[i] = resolver;
            } else if (Uri.class.equals(p)) {
                // Firmware wants the table/base URI, not .../media/<id>
                args[i] = baseUri != null ? baseUri : itemUri;
            } else if (ContentValues.class.equals(p)) {
                args[i] = values;
            } else if (String.class.equals(p)) {
                args[i] = itemUri.getLastPathSegment();
            } else if (p == Long.TYPE || p == Long.class) {
                args[i] = Long.valueOf(id);
            } else if (p == Integer.TYPE || p == Integer.class) {
                args[i] = Integer.valueOf((int) id);
            } else if (p.getName().equals("com.sony.scalar.provider.AvindexGpsParam")) {
                args[i] = buildGpsParam(p, values, itemUri.getLastPathSegment());
            } else if (p == Boolean.TYPE || p == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private Object buildGpsParam(Class<?> paramClass, ContentValues values, String uniqId)
            throws Exception {
        Object param = paramClass.getConstructor().newInstance();
        invokeSetter(param, "setUniqId", uniqId);
        invokeSetter(param, "setLatitude", values.getAsString("GPSLatitude"));
        invokeSetter(param, "setLatitudeRef", values.getAsString("GPSLatitudeRef"));
        invokeSetter(param, "setLongitude", values.getAsString("GPSLongitude"));
        invokeSetter(param, "setLongitudeRef", values.getAsString("GPSLongitudeRef"));
        invokeSetter(param, "setAltitude", values.getAsString("GPSAltitude"));
        invokeSetter(param, "setAltitudeRef", values.getAsString("GPSAltitudeRef"));
        invokeSetter(param, "setImgDirection", values.getAsString("GPSImgDirection"));
        invokeSetter(param, "setImgDirectionRef", values.getAsString("GPSImgDirectionRef"));
        invokeSetter(param, "setSpeed", values.getAsString("GPSSpeed"));
        invokeSetter(param, "setSpeedRef", values.getAsString("GPSSpeedRef"));
        invokeSetter(param, "setTrack", values.getAsString("GPSTrack"));
        invokeSetter(param, "setTrackRef", values.getAsString("GPSTrackRef"));
        invokeSetter(param, "setDateStamp", values.getAsString("GPSDateStamp"));
        invokeSetter(param, "setTimeStamp", values.getAsString("GPSTimeStamp"));
        invokeSetter(param, "setGpsStatus", values.getAsString("GPSStatus"));
        invokeSetter(param, "setGpsMeasureMode", values.getAsString("GPSMeasureMode"));
        invokeSetter(param, "setGpsVersion", values.getAsString("GPSVersionID"));
        invokeSetter(param, "setMapDatum", values.getAsString("GPSMapDatum"));
        return param;
    }

    private void invokeSetter(Object target, String name, String value) {
        if (value == null) {
            return;
        }
        try {
            Method m = target.getClass().getMethod(name, String.class);
            m.invoke(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static String toDms(double coordinate) {
        int degrees = (int) Math.floor(coordinate);
        double minutesFull = (coordinate - degrees) * 60.0;
        int minutes = (int) Math.floor(minutesFull);
        double seconds = (minutesFull - minutes) * 60.0;
        int secMilli = (int) Math.round(seconds * 1000.0);
        if (secMilli >= 60000) {
            secMilli = 0;
            minutes++;
        }
        if (minutes >= 60) {
            minutes = 0;
            degrees++;
        }
        return degrees + "/1," + minutes + "/1," + secMilli + "/1000";
    }

    private static String toRational(double value, int scale) {
        long numerator = Math.round(value * scale);
        return numerator + "/" + scale;
    }
}
