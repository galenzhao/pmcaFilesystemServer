package info.schnatterer.pmcaFilesystemServer;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Logger {

    public static final String DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS";
    private static final String TAG = "pmcaFilesystemServer";

    private static File logFile;
    private static String resolveError;
    /** When false, skip SD card LOG.TXT writes (logcat still gets errors). */
    private static volatile boolean enabled = true;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            ensureLogFile();
            // Force a line even if something raced; write directly so toggle is visible.
            writeLine("[INFO] logging enabled path="
                    + (logFile != null ? logFile.getAbsolutePath() : "null"));
        } else {
            Log.i(TAG, "logging disabled (no more LOG.TXT writes)");
        }
    }

    public static File getFile() {
        ensureLogFile();
        return logFile;
    }

    public static String getResolveError() {
        ensureLogFile();
        return resolveError;
    }

    /** Call early (e.g. MainActivity) so a log file exists even before Wi‑Fi server starts. */
    public static void bootstrap(String reason) {
        if (!enabled) {
            Log.i(TAG, "bootstrap skipped (logging off): " + reason);
            return;
        }
        ensureLogFile();
        info("bootstrap: " + reason + " path=" + (logFile != null ? logFile.getAbsolutePath() : "null")
                + (resolveError != null ? " err=" + resolveError : ""));
    }

    protected static synchronized void ensureLogFile() {
        if (logFile != null) {
            return;
        }
        File[] candidates = new File[]{
                new File(Environment.getExternalStorageDirectory(), "pmcaFilesystemServer/LOG.TXT"),
                new File(Environment.getExternalStorageDirectory(), "DCIM/PMCAFS/LOG.TXT"),
                new File("/storage/sdcard0/pmcaFilesystemServer/LOG.TXT"),
                new File("/storage/sdcard0/DCIM/PMCAFS/LOG.TXT"),
                new File("/mnt/sdcard/pmcaFilesystemServer/LOG.TXT"),
                new File("/mnt/sdcard/DCIM/PMCAFS/LOG.TXT"),
                new File("/storage/sdcard1/pmcaFilesystemServer/LOG.TXT"),
                new File("/storage/sdcard1/DCIM/PMCAFS/LOG.TXT"),
        };
        StringBuilder errors = new StringBuilder();
        for (File candidate : candidates) {
            try {
                File parent = candidate.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    errors.append(candidate).append(": mkdirs failed; ");
                    continue;
                }
                FileWriter probe = new FileWriter(candidate, true);
                probe.close();
                logFile = candidate;
                Log.i(TAG, "Using log file: " + candidate.getAbsolutePath());
                return;
            } catch (IOException e) {
                errors.append(candidate).append(": ").append(e.getMessage()).append("; ");
            }
        }
        resolveError = errors.toString();
        Log.e(TAG, "No writable log path. Tried: " + resolveError);
        // Keep a non-null fallback for getAbsolutePath() in UI even if unwritable.
        logFile = candidates[0];
    }

    protected static void log(String msg) {
        if (!enabled) {
            return;
        }
        ensureLogFile();
        Log.i(TAG, msg);
        writeLine(msg);
    }

    private static void writeLine(String msg) {
        if (logFile == null) {
            return;
        }
        try {
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true));
            SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN, Locale.US);
            writer.append(sdf.format(new Date()));
            writer.append(" ");
            writer.append(msg);
            writer.newLine();
            writer.close();
        } catch (IOException e) {
            Log.e(TAG, "Error writing log to " + logFile.getAbsolutePath(), e);
        }
    }

    protected static void log(String type, String msg) {
        log("[" + type + "] " + msg);
    }

    public static void info(String msg) {
        log("INFO", msg);
    }

    public static void error(String msg) {
        // Errors always hit logcat; file only when enabled.
        Log.e(TAG, msg);
        if (!enabled) {
            return;
        }
        log("ERROR", msg);
    }

    public static void error(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        if (!enabled) {
            return;
        }
        log("ERROR", msg);
        if (t == null) {
            return;
        }
        log("ERROR", t.getClass().getName() + ": " + t.getMessage());
        StackTraceElement[] stack = t.getStackTrace();
        int limit = Math.min(stack.length, 25);
        for (int i = 0; i < limit; i++) {
            log("ERROR", "  at " + stack[i].toString());
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            log("ERROR", "Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
            StackTraceElement[] cstack = cause.getStackTrace();
            int climit = Math.min(cstack.length, 15);
            for (int i = 0; i < climit; i++) {
                log("ERROR", "  at " + cstack[i].toString());
            }
        }
    }
}
