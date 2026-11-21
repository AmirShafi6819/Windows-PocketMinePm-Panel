package com.pmpanel.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The Windows desktop shell - the port of Android's MainActivity + MainApp.
 *
 * Everything the user sees is the ORIGINAL panel UI (www/), loaded into the
 * JavaFX WebView and driven by the same JSON bridge API (window.PMPanel).
 * Android-specific plumbing is replaced 1:1:
 *
 *   WebView setup            -> JavaFX WebView + local UI server (UiServer)
 *   addJavascriptInterface   -> JSObject.setMember("PMPanel", bridge)
 *   SAF file pickers         -> javafx.stage.FileChooser dialogs
 *   evaluateJavascript push  -> WebEngine.executeScript callbacks
 *   foreground service note  -> TrayService (system tray icon)
 *   POST_NOTIFICATIONS/etc.  -> not needed on desktop (no-op)
 *   crash log                -> identical logs/app-crash.log file
 */
public class DesktopApp extends Application {

    public static final String APP_VERSION = "2.8.2";

    private static volatile DesktopApp instance;
    private static volatile Stage mainStage;
    private static volatile WebEngine webEngine;
    private static volatile boolean pageReady = false;
    private static final List<String> pendingEvents = new ArrayList<>();

    private Bridge bridge;
    private UiServer uiServer;

    /* ---- resilient page loading (white-screen protection) ---- */
    private static volatile String activeUrl;
    private boolean fileFallbackTried = false;
    private boolean recoveryShown = false;
    private boolean reloadRetried = false;

    /* ---------------- pending pickers ---------------- */
    private static class PendingUpload {
        String reqId;
        String destDir;
        String profileId;
        boolean multiple;
    }

    private static class PendingSave {
        String reqId;
        String path;
        String suggested;
        String profileId;
    }

    private PendingUpload pendingUpload;
    private PendingSave pendingSave;
    private String pendingRestoreReqId;
    private String pendingRestoreProfile;

    /* ================= lifecycle ================= */

    @Override
    public void init() {
        // runs on the launcher thread BEFORE the FX window: extract resources so
        // the very first paint already reads from disk (like Android assets).
        instance = this;
        AppDirs.init();
        try {
            extractResources();
        } catch (Throwable t) {
            appendCrashLog("resource extraction failed: " + Bridge.safeMsg(t));
        }
        // register the Vazirmatn fonts platform-wide so the WebView can use them
        for (String f : new String[]{"/fonts/Vazirmatn-Regular.ttf",
                "/fonts/Vazirmatn-Medium.ttf", "/fonts/Vazirmatn-Bold.ttf"}) {
            try (InputStream in = DesktopApp.class.getResourceAsStream(f)) {
                if (in != null) javafx.scene.text.Font.loadFont(in, 12);
            } catch (Throwable ignored) {
            }
        }
        // default uncaught handler -> app-crash.log (same as MainApp on Android)
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            appendCrashLog("FATAL on " + t.getName() + ": " + stackOf(e));
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    @Override
    public void start(Stage stage) {
        mainStage = stage;
        bridge = new Bridge(this);
        uiServer = new UiServer();
        try {
            uiServer.start();
        } catch (Throwable t) {
            appendCrashLog("ui server failed: " + Bridge.safeMsg(t));
        }

        WebView web = new WebView();
        WebEngine engine = web.getEngine();
        webEngine = engine;
        web.setContextMenuEnabled(false);
        web.setFontSmoothingType(javafx.scene.text.FontSmoothingType.GRAY);

        StackPane root = new StackPane(web);
        Scene scene = new Scene(root, 1100, 760);
        scene.setFill(javafx.scene.paint.Color.web("#05060A"));
        // keep the WebView filling the window
        root.setMinSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);

        engine.setOnError(e -> appendCrashLog("JS " + e.getMessage()));
        engine.setOnAlert(e -> { /* page never calls alert(); ignore safely */ });
        engine.locationProperty().addListener((o, old, url) -> {
            // open external links in the default browser (same as Android WebViewClient)
            if (isForeignUrl(url)) {
                String u = url;
                Platform.runLater(() -> {
                    if (webEngine != null && activeUrl != null) {
                        try {
                            // prevent navigating the panel away; open externally instead
                            String back = activeUrl;
                            webEngine.load(back);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                openUrl(u);
            }
        });

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                try {
                    JSObject win = (JSObject) engine.executeScript("window");
                    win.setMember("PMPanel", bridge);
                    // release the deferred DOMContentLoaded listener -> panel boots
                    engine.executeScript(
                            "try { if (window.__pmFireBoot) window.__pmFireBoot(); } catch (e) {}");
                } catch (Throwable t) {
                    appendCrashLog("bridge attach failed: " + Bridge.safeMsg(t));
                }
                pageReady = true;
                flushPendingEvents();
            } else if (newState == Worker.State.FAILED) {
                // BootGuard listens for FAILED too (same worker) - it owns the
                // fallback chain, so nothing extra to do here.
            }
        });

        /* primary load: local loopback UI server; BootGuard recovers through
           the file:// fallback and the recovery page if anything goes wrong */
        String url = uiServer != null ? uiServer.baseUrl() : null;
        if (url == null || url.isEmpty()) {
            url = fileFallbackUrl();
        }
        activeUrl = url;
        BootGuard.arm(web, url, bootFallback);
        engine.load(url);

        stage.setTitle("PM Panel - PocketMine for Windows");
        try {
            stage.getIcons().add(new Image("/icons/pmpanel-256.png"));
            stage.getIcons().add(new Image("/icons/pmpanel-64.png"));
        } catch (Throwable ignored) {
        }
        stage.setMinWidth(480);
        stage.setMinHeight(600);
        applySavedWindowGeometry(stage);
        installWindowKeys(stage, scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            saveWindowGeometry(stage);
            if (ServerManager.anyRunning()) {
                // servers keep running in the background (same contract as the
                // Android foreground service); the tray icon provides control.
                e.consume();
                Platform.setImplicitExit(false);
                stage.hide();
                TrayService.start();
                TrayService.notify("PM Panel",
                        "Server keeps running in the background. Use the tray icon to stop it or reopen the panel.");
            } else {
                exitApplication();
            }
        });
        stage.show();
        TrayService.start();
        TrayService.updateState(ServerManager.runningNames());
    }

    @Override
    public void stop() {
        if (uiServer != null) uiServer.stop();
    }

    /* ================= desktop window behaviour ================= */

    /*
     * The Android app was a phone screen; the desktop window is a real
     * Windows window: first launch opens MAXIMIZED (fullscreen-like work
     * area), the chosen size/position is remembered across launches, and
     * F11 toggles true Windows fullscreen (ESC leaves it again).
     */

    /** Restore the remembered geometry, or open maximized on first launch. */
    private static void applySavedWindowGeometry(Stage stage) {
        Prefs p = Prefs.get();
        if (p.getBoolean("win.full", false)) {
            Platform.runLater(() -> stage.setFullScreen(true));
            return;
        }
        if (p.contains("win.w") && p.contains("win.h")) {
            int w = p.getInt("win.w", 0), h = p.getInt("win.h", 0);
            if (w >= (int) stage.getMinWidth() && h >= (int) stage.getMinHeight()) {
                stage.setWidth(w);
                stage.setHeight(h);
                int x = p.getInt("win.x", Integer.MIN_VALUE);
                int y = p.getInt("win.y", Integer.MIN_VALUE);
                if (fitsOnScreen(x, y, w, h)) {
                    stage.setX(x);
                    stage.setY(y);
                }
                if (p.getBoolean("win.max", false)) {
                    Platform.runLater(() -> stage.setMaximized(true));
                }
                return;
            }
        }
        // first launch (or unusable geometry): fill the Windows work area
        Platform.runLater(() -> stage.setMaximized(true));
    }

    /** Keep maximized/fullscreen flags current while the window is used. */
    private static void installWindowKeys(Stage stage, Scene scene) {
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
            } else if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && stage.isFullScreen()) {
                stage.setFullScreen(false);
            }
        });
        stage.fullScreenProperty().addListener((o, was, full) -> {
            Prefs.get().put("win.full", full).apply();
            if (!full) saveWindowGeometry(stage);
        });
        stage.maximizedProperty().addListener((o, was, max) -> {
            Prefs.get().put("win.max", max).apply();
            if (!max) saveWindowGeometry(stage);
        });
    }

    /** Persist the current window bounds (skipped while maximized/fullscreen). */
    private static void saveWindowGeometry(Stage stage) {
        try {
            Prefs p = Prefs.get();
            if (!stage.isFullScreen() && !stage.isMaximized() && stage.getWidth() > 0) {
                p.put("win.w", (int) stage.getWidth());
                p.put("win.h", (int) stage.getHeight());
                p.put("win.x", (int) stage.getX());
                p.put("win.y", (int) stage.getY());
            }
            p.put("win.max", stage.isMaximized());
            p.put("win.full", stage.isFullScreen());
            p.apply();
        } catch (Throwable ignored) {
        }
    }

    private static boolean fitsOnScreen(double x, double y, double w, double h) {
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) return false;
        for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) {
            javafx.geometry.Rectangle2D b = s.getVisualBounds();
            if (x + 60 > b.getMinX() && x < b.getMaxX() - 60
                    && y + 40 > b.getMinY() && y < b.getMaxY() - 40) {
                return true;
            }
        }
        return false;
    }

    /* ================= resilient page loading ================= */

    /**
     * The load-fallback chain driven by BootGuard:
     *   1. local loopback UiServer (primary, injected shim, stable origin)
     *   2. file:// load of the extracted www (shim-injected copy) - used when
     *      the server is unreachable (firewall/proxy/half-bound socket)
     *   3. one reload of the primary URL (transient first-run hiccups)
     *   4. bundled recovery page (dark, readable, tells the user what to do)
     */
    private final BootGuard.LoadFallback bootFallback = new BootGuard.LoadFallback() {

        @Override
        public void onLoadFailed() {
            Platform.runLater(() -> {
                if (recoveryShown) return;
                String cur = activeUrl;
                if (cur != null && cur.startsWith("http://127.0.0.1:")) {
                    String fb = fileFallbackUrl();
                    if (fb != null) {
                        appendCrashLog("boot: http load failed -> falling back to file://");
                        loadUrl(fb);
                        return;
                    }
                }
                showRecoveryPage();
            });
        }

        @Override
        public void onBootTimeout() {
            Platform.runLater(() -> {
                if (recoveryShown) return;
                String cur = activeUrl;
                boolean http = cur != null && cur.startsWith("http://127.0.0.1:");
                if (http && !fileFallbackTried) {
                    String fb = fileFallbackUrl();
                    if (fb != null) {
                        appendCrashLog("boot: http page never booted -> file:// fallback");
                        loadUrl(fb);
                        return;
                    }
                }
                if (!reloadRetried) {
                    reloadRetried = true;
                    appendCrashLog("boot: retrying the primary URL once");
                    loadUrl(cur);
                    return;
                }
                showRecoveryPage();
            });
        }

        @Override
        public void showRecoveryPage() {
            Platform.runLater(() -> {
                if (recoveryShown) return;
                recoveryShown = true;
                appendCrashLog("boot: showing recovery page");
                loadUrl(BootGuard.recoveryPageUrl());
            });
        }
    };

    private void loadUrl(String url) {
        if (url == null) return;
        activeUrl = url;
        if (webEngine != null) webEngine.load(url);
    }

    /** file:// URL of the extracted www (shim injected; falls back to the jar copy). */
    private String fileFallbackUrl() {
        try {
            byte[] html = uiServer != null ? UiServer.shimmedIndexHtml() : null;
            if (html == null) return null;
            File out = new File(AppDirs.wwwDir(), "index-desktop.html");
            out.getParentFile().mkdirs();
            try (FileOutputStream fo = new FileOutputStream(out)) {
                fo.write(html);
            }
            fileFallbackTried = true;
            appendCrashLog("boot: file:// fallback prepared at " + out.getAbsolutePath());
            return out.toURI().toString();
        } catch (Throwable t) {
            appendCrashLog("boot: file:// fallback unavailable - " + Bridge.safeMsg(t));
            return null;
        }
    }

    /** Anything that is NOT the panel's own page opens in the system browser. */
    private boolean isForeignUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("http://127.0.0.1:")) return false;   // ui server
        if (url.startsWith("file:")) return false;               // local fallback
        if (url.startsWith("data:")) return false;               // recovery page
        if (url.startsWith("about:")) return false;
        return true;
    }


    /* ================= window / app control ================= */

    public static void showWindow() {
        Platform.runLater(() -> {
            Stage s = mainStage;
            if (s != null) {
                s.show();
                s.toFront();
                s.setIconified(false);
            }
        });
    }

    public static void exitApplication() {
        new Thread(() -> {
            ServerManager.stopAll();
            BotEngine.kill();
        }, "pm-shutdown").start();
        Platform.runLater(() -> {
            pageReady = false;
            Platform.exit();
        });
        // give the stop threads a moment, then hard-exit (php child processes
        // received their "stop" command; destroyForcibly already handled)
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "pm-exit").start();
    }

    /** Exit for the self-restart path (software-rendering relaunch). */
    static void exitForRestart() {
        pageReady = false;
        Platform.exit();
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "pm-restart-exit").start();
    }

    /** Tray callback after the last server stopped. */
    public static void onAllServersStopped() {
        TrayService.notify("PM Panel", "All servers stopped.");
    }

    /* ================= resource extraction ================= */

    private static void extractResources() throws IOException {
        File resRoot = AppDirs.resRoot();
        //noinspection ResultOfMethodCallIgnored
        resRoot.mkdirs();
        File stamp = new File(resRoot, "version.txt");
        String wanted = APP_VERSION;
        String have = "";
        try {
            if (stamp.isFile()) have = new String(java.nio.file.Files.readAllBytes(stamp.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
        }
        if (!wanted.equals(have)) {
            deleteRec(resRoot);
            //noinspection ResultOfMethodCallIgnored
            resRoot.mkdirs();
        }
        extractDir("www", new File(resRoot, "www"));
        extractDir("assets", new File(resRoot, "assets"));
        try (OutputStream out = new FileOutputStream(stamp)) {
            out.write(wanted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void extractDir(String resDir, File destDir) throws IOException {
        // resource listing via a manifest: jar listing is not portable, so the
        // build writes www-files.txt / assets-files.txt next to the resources.
        try (InputStream in = DesktopApp.class.getResourceAsStream("/" + resDir + "-files.txt")) {
            if (in == null) throw new IOException("missing resource manifest: " + resDir + "-files.txt");
            List<String> files = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            byte[] rbuf = new byte[4096];
            int n;
            while ((n = in.read(rbuf)) > 0) sb.append(new String(rbuf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            for (String line : sb.toString().split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) files.add(t);
            }
            for (String rel : files) {
                String resPath = "/" + resDir + "/" + rel;
                File out = new File(destDir, rel.replace('/', File.separatorChar));
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (InputStream rin = DesktopApp.class.getResourceAsStream(resPath);
                     OutputStream rout = new FileOutputStream(out)) {
                    if (rin == null) throw new IOException("missing resource: " + resPath);
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = rin.read(buf)) > 0) rout.write(buf, 0, r);
                }
            }
        }
    }

    private static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /* ================= JS bridge helpers ================= */

    /** Push an asynchronous result event into the WebView. */
    public void pushEvent(String reqId, JSONObject payload) {
        if (reqId == null || reqId.isEmpty()) return;
        try {
            payload.put("reqId", reqId);
        } catch (Exception ignored) {
        }
        dispatchJs("window.__pmEvent && window.__pmEvent(" + jsSafe(payload) + ")");
    }

    /** Push one bot-engine event line (raw JSON from pmbot.exe) into the WebView. */
    public static void pushBotEvent(String rawLine) {
        String payload = rawLine;
        try {
            new JSONObject(rawLine); // validate
        } catch (Throwable t) {
            try {
                payload = new JSONObject()
                        .put("ev", "log")
                        .put("text", rawLine).toString();
            } catch (Throwable ignored) {
                return;
            }
        }
        dispatchJs("window.__pmBotEvent && window.__pmBotEvent(" + rawLine + ")");
    }

    private static String jsSafe(JSONObject payload) {
        // U+2028/U+2029 are valid JSON but invalid JS literals - escape them
        return payload.toString()
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private static void dispatchJs(String js) {
        if (!pageReady) {
            synchronized (pendingEvents) {
                pendingEvents.add(js);
                if (pendingEvents.size() > 200) pendingEvents.remove(0);
            }
            return;
        }
        Platform.runLater(() -> {
            try {
                WebEngine e = webEngine;
                if (e != null) e.executeScript(js);
            } catch (Throwable ignored) {
                // WebView already torn down - nothing to deliver
            }
        });
    }

    private static void flushPendingEvents() {
        List<String> batch;
        synchronized (pendingEvents) {
            batch = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
        }
        for (String js : batch) {
            try {
                WebEngine e = webEngine;
                if (e != null) e.executeScript(js);
            } catch (Throwable ignored) {
            }
        }
    }

    /* ================= misc Android-equivalent hooks ================= */

    public void applyKeepScreenOn() {
        // Android-only (display wake lock) - nothing to do on desktop
    }

    public void requestIgnoreBatteryOptimizations() {
        // Android-only - the bridge treats batteryOpt as a no-op here
    }

    public void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", url).start();
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- crash log ---------------- */

    private static final long CRASH_LOG_MAX = 400 * 1024;

    public static void appendCrashLog(String line) {
        try {
            File logs = Installer.logsDir();
            //noinspection ResultOfMethodCallIgnored
            logs.mkdirs();
            File f = new File(logs, "app-crash.log");
            if (f.length() > CRASH_LOG_MAX) f.delete();
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(("\n[" + stamp + "] " + line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // never let the logger itself crash the app
        }
    }

    private static String stackOf(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        e.printStackTrace(pw);
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    /* ---------------- app/device info (Bridge.info) ---------------- */

    public static String appVersion() {
        return APP_VERSION;
    }

    /** Same keys the Android info() produced, filled with Windows values. */
    public static void fillDeviceInfo(JSONObject dev) {
        try {
            String computer = System.getenv("COMPUTERNAME");
            if (computer == null || computer.isEmpty()) {
                try {
                    computer = java.net.InetAddress.getLocalHost().getHostName();
                } catch (Throwable t) {
                    computer = "PC";
                }
            }
            dev.put("model", computer);
            dev.put("manufacturer", "Windows PC");
            dev.put("android", System.getProperty("os.name", "Windows")
                    + " " + System.getProperty("os.version", ""));
            dev.put("sdk", 0);
            dev.put("abi", System.getProperty("os.arch", "x86_64"));
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            dev.put("totalRam", os.getTotalPhysicalMemorySize());
        } catch (Throwable t) {
            try {
                dev.put("model", "PC");
            } catch (Throwable ignored) {
            }
        }
    }

    /* ================= file pickers (SAF replacement) ================= */

    public void pickUpload(String reqId, String destDir, boolean multiple, String profileId) {
        PendingUpload pu = new PendingUpload();
        pu.reqId = reqId;
        pu.destDir = destDir == null || destDir.isEmpty() ? "" : destDir;
        pu.profileId = Profiles.validId(profileId) ? profileId : Profiles.DEFAULT_ID;
        pu.multiple = multiple;
        pendingUpload = pu;
        Platform.runLater(() -> {
            try {
                FileChooser fc = new FileChooser();
                fc.setTitle("Upload files to the server");
                List<File> chosen = pu.multiple ? fc.showOpenMultipleDialog(mainStage)
                        : single(fc.showOpenDialog(mainStage));
                if (chosen == null || chosen.isEmpty()) {
                    pendingUpload = null;
                    pushEvent(pu.reqId, Bridge.errObj("cancelled"));
                    return;
                }
                final List<File> picked = chosen;
                new Thread(() -> {
                    JSONArray files = new JSONArray();
                    Fs fs = ServerManager.forProfile(pu.profileId).fs;
                    for (File u : picked) {
                        try {
                            String name = u.getName();
                            if (name == null || name.isEmpty()) name = "upload-" + System.currentTimeMillis();
                            File dest = fs.resolve(pu.destDir + "/" + name);
                            //noinspection ResultOfMethodCallIgnored
                            dest.getParentFile().mkdirs();
                            try (InputStream in = new FileInputStream(u);
                                 OutputStream out = new FileOutputStream(dest)) {
                                copyStream(in, out);
                            }
                            files.put(ServerManager.JO("name", name, "size", dest.length()));
                        } catch (Exception e) {
                            files.put(ServerManager.JO("error", Bridge.safeMsg(e)));
                        }
                    }
                    pushEvent(pu.reqId, ServerManager.JO("ok", true, "files", files));
                }, "pm-upload").start();
            } catch (Throwable t) {
                pendingUpload = null;
                pushEvent(pu.reqId, Bridge.errObj("no file picker available"));
            }
        });
    }

    public void pickSave(String reqId, String path, String suggested, String profileId) {
        PendingSave ps = new PendingSave();
        ps.reqId = reqId;
        ps.path = path;
        ps.suggested = (suggested == null || suggested.isEmpty()) ? "file" : suggested;
        ps.profileId = Profiles.validId(profileId) ? profileId : Profiles.DEFAULT_ID;
        pendingSave = ps;
        Platform.runLater(() -> {
            try {
                FileChooser fc = new FileChooser();
                fc.setTitle("Save file");
                fc.setInitialFileName(ps.suggested);
                File target = fc.showSaveDialog(mainStage);
                if (target == null) {
                    pendingSave = null;
                    pushEvent(ps.reqId, Bridge.errObj("cancelled"));
                    return;
                }
                final File dest = target;
                new Thread(() -> {
                    try {
                        File src = ServerManager.forProfile(ps.profileId).fs.resolve(ps.path);
                        try (InputStream in = new FileInputStream(src);
                             OutputStream out = new FileOutputStream(dest)) {
                            copyStream(in, out);
                        }
                        pushEvent(ps.reqId, ServerManager.JO("ok", true,
                                "name", src.getName(), "size", src.length()));
                    } catch (Exception e) {
                        pushEvent(ps.reqId, Bridge.errObj(Bridge.safeMsg(e)));
                    }
                }, "pm-save").start();
            } catch (Throwable t) {
                pendingSave = null;
                pushEvent(ps.reqId, Bridge.errObj("no file picker available"));
            }
        });
    }

    public void pickRestore(String reqId, String profileId) {
        pendingRestoreReqId = reqId;
        pendingRestoreProfile = profileId == null ? "default" : profileId;
        Platform.runLater(() -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose a backup zip to restore");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Backup archive (*.zip)", "*.zip"));
            File f = fc.showOpenDialog(mainStage);
            if (f == null) {
                pendingRestoreReqId = null;
                pendingRestoreProfile = null;
                pushEvent(reqId, Bridge.errObj("cancelled"));
                return;
            }
            final File chosen = f;
            final String reqId2 = pendingRestoreReqId;
            final String profile2 = pendingRestoreProfile;
            pendingRestoreReqId = null;
            pendingRestoreProfile = null;
            if (ServerManager.forProfile(profile2).isRunning()) {
                pushEvent(reqId2, Bridge.errObj("stop the server first"));
                return;
            }
            new Thread(() -> {
                try {
                    bridge.restoreFromFile(chosen, reqId2, profile2);
                } catch (Throwable t) {
                    pushEvent(reqId2, Bridge.errObj(Bridge.safeMsg(t)));
                }
            }, "pm-restore-pick").start();
        });
    }

    private static List<File> single(File f) {
        if (f == null) return null;
        List<File> l = new ArrayList<>();
        l.add(f);
        return l;
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }
}
