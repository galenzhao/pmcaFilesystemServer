package info.schnatterer.pmcaFilesystemServer;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * Bases on https://github.com/Bostwickenator/STGUploader/blob/master/app/src/main/java/org/bostwickenator/googlephotos/FilesystemScanner.java
 * Commit b8ce40d
 */
class FilesystemScanner {

    static final Set<String> rawFormats = Collections.unmodifiableSet(Collections.singleton(".arw"));
    static final Set<String> jpegFormats = Collections.unmodifiableSet(Collections.singleton(".jpg"));
    static final Set<String> videoFormats = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(".mts", ".mp4")));

    public static List<File> getRawsOnExternalStorage() {
        return getFileOnExternalStorage(rawFormats);
    }

    public static List<File> getJpegsOnExternalStorage() {
        return getFileOnExternalStorage(jpegFormats);
    }

    public static List<File> getVideosOnExternalStorage() {
        return getFileOnExternalStorage(videoFormats);
    }

    static List<File> getFileOnExternalStorage(Collection<String> extensions) {
        return getFilteredFileList(Environment.getExternalStorageDirectory(), extensions);
    }

    private static List<File> getFilteredFileList(File directory, Collection<String> extensions) {
        File[] subFiles = directory.listFiles();
        List<File> filtered = new ArrayList<File>();
        if (subFiles != null) {
            for (File f : subFiles) {
                String filename = f.getName().toLowerCase();
                if (f.isFile()) {
                    for (String extension : extensions) {
                        if (filename.endsWith(extension)) {
                            filtered.add(f);
                            break;
                        }
                    }
                } else if (f.isDirectory()) {
                    filtered.addAll(getFilteredFileList(f, extensions));
                }
            }
        }
        return filtered;
    }
}
