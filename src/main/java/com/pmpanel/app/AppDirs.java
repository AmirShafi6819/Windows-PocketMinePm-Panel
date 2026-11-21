package com.pmpanel.app;

import java.io.File;

/**
 * Desktop (Windows) equivalent of the Android app-private storage.
 *
 * Android layout                       ->  Windows layout
 * <filesDir>                           ->  %APPDATA%\PMPanel
 * <filesDir>/server                    ->  %APPDATA%\PMPanel\server            (default profile)
 * <filesDir>/servers/<id>              ->  %APPDATA%\PMPanel\servers\<id>
 * <filesDir>/php64                     ->  %APPDATA%\PMPanel\bin\php           (shared PHP runtime)
 * nativeLibraryDir (libpmphp.so)       ->  %APPDATA%\PMPanel\bin\php\php.exe
 * nativeLibraryDir (libpmbot.so)       ->  %APPDATA%\PMPanel\bin\pmbot.exe
 * getExternalCacheDir()/getCacheDir()  ->  %LOCALAPPDATA%\PMPanel\cache
 * APK assets                           ->  %LOCALAPPDATA%\PMPanel\app  (extracted from the jar at boot)
 */
public final class AppDirs {

    private static File dataRoot;   // roaming profile data (survives updates)
    private static File cacheRoot;  // machine-local cache (regenerable)
    private static File resRoot;    // extracted jar resources (www + assets)

    private AppDirs() {
    }

    /** Resolve the base directories from the Windows environment. */
    public static synchronized void init() {
        if (dataRoot != null) return;
        String appData = env("APPDATA");
        String localAppData = env("LOCALAPPDATA");
        if (appData == null) {
            appData = env("USERPROFILE");
            appData = appData == null ? System.getProperty("user.home") : appData + File.separator + "AppData" + File.separator + "Roaming";
        }
        if (localAppData == null) {
            localAppData = env("USERPROFILE");
            localAppData = localAppData == null ? System.getProperty("user.home") : localAppData + File.separator + "AppData" + File.separator + "Local";
        }
        dataRoot = new File(appData, "PMPanel");
        cacheRoot = new File(new File(localAppData, "PMPanel"), "cache");
        resRoot = new File(new File(localAppData, "PMPanel"), "app");
        //noinspection ResultOfMethodCallIgnored
        dataRoot.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        cacheRoot.mkdirs();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) return null;
        File f = new File(v.trim());
        return f.isDirectory() ? f.getAbsolutePath() : null;
    }

    /** Equivalent of Context.getFilesDir(). */
    public static File filesDir() {
        init();
        return dataRoot;
    }

    /** Default (legacy) profile server directory: <files>/server. */
    public static File defaultServerDir() {
        return new File(filesDir(), "server");
    }

    /** Parent of the additional profiles: <files>/servers. */
    public static File serversRoot() {
        return new File(filesDir(), "servers");
    }

    /** Shared runtime binaries (PHP + bot engine): <files>/bin. */
    public static File binDir() {
        return new File(filesDir(), "bin");
    }

    /** Equivalent of Installer.cacheTmp(): <cache>/pmtmp. */
    public static File cacheTmp() {
        File f = new File(cacheRoot, "pmtmp");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** Staging parent for the zip importer: <cache>/import-stage. */
    public static File cacheTmpStageBase() {
        File f = new File(cacheRoot, "import-stage");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** Extracted jar resources root (www/ + assets/). */
    public static File resRoot() {
        init();
        return resRoot;
    }

    /** Extracted APK-equivalent assets folder. */
    public static File assetsDir() {
        return new File(resRoot(), "assets");
    }

    /** Extracted panel UI folder (served to the WebView). */
    public static File wwwDir() {
        return new File(resRoot(), "www");
    }
}
