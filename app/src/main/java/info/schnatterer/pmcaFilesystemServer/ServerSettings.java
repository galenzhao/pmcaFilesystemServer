package info.schnatterer.pmcaFilesystemServer;

import android.content.Context;
import android.content.SharedPreferences;

class ServerSettings {
    private static final String PREFS = "server";
    private static final String EXPOSE_FULL_FILESYSTEM = "expose_full_filesystem";

    private ServerSettings() {
    }

    static boolean isExposeFullFilesystem(Context context) {
        return preferences(context).getBoolean(EXPOSE_FULL_FILESYSTEM, false);
    }

    static void setExposeFullFilesystem(Context context, boolean expose) {
        preferences(context).edit().putBoolean(EXPOSE_FULL_FILESYSTEM, expose).commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
