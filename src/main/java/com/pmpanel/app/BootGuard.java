package com.pmpanel.app;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.io.File;

/**
 * Boot watchdog for the desktop shell.
 *
 * Two failure classes produce the dreaded "white window, nothing inside" on
 * Windows, and this class detects and recovers from both:
 *
 * 1. The page never finished booting (UiServer dead, extraction failed,
 *    proxy/firewall blocking the loopback URL, stale cache).   -> the shell
 *    retries through its load-fallback chain (UiServer -> file:// -> bundled
 *    error page), see DesktopApp.
 *
 * 2. The page DID load and boot, but the JavaFX render pipeline paints
 *    nothing (known Direct3D/driver issue on some Windows machines: the whole
 *    scene stays white - JDK-8185550 / openjdk-jfx#557, cured by software
 *    rendering `-Dprism.order=sw`). -> we verify the actual rendered pixels
 *    via a WebView snapshot and, if the window paints white while the page is
 *    provably dark, relaunch PMPanel.exe once with the software pipeline
 *    (jpackage launchers accept `-J` JVM options). A marker file keeps the
 *    self-heal from looping.
 *
 * Everything it does is logged to logs/app-crash.log so a user report can be
 * diagnosed afterwards. No UI is changed or removed - recovery only.
 */
final class BootGuard {

    /** Total budget before we consider a booted page "stuck". */
    private static final long BOOT_TIMEOUT_MS = 8000;
    /** Do not self-relaunch more often than this (loop guard). */
    private static final long RELAUNCH_COOLDOWN_MS = 5 * 60 * 1000;

    private static volatile boolean booted = false;
    private static volatile boolean cssDark = false;
    private static volatile boolean swMode = false;
    private static volatile boolean guardDone = false;
    private static WebView webRef;

    /** Recovery callbacks implemented by the desktop shell (load chain). */
    interface LoadFallback {
        /** Page load reported FAILED - try the next load URL. */
        void onLoadFailed();
        /** Page loaded but never booted within the timeout - try a fallback. */
        void onBootTimeout();
        /** Everything failed - show the bundled recovery page. */
        void showRecoveryPage();
    }

    private BootGuard() {
    }

    /** True if this launch runs with the software pipeline (set at arm() time). */
    static boolean runningInSoftwareMode() {
        return swMode;
    }

    /**
     * Start watching. Called from the FX thread right after the WebView is
     * created, before the first load.
     */
    static void arm(WebView web, String uiUrl, LoadFallback fallback) {
        webRef = web;
        swMode = "sw".equalsIgnoreCase(System.getProperty("prism.order", ""))
                || Boolean.getBoolean("pm.force.sw");
        final WebEngine engine = web.getEngine();

        engine.getLoadWorker().stateProperty().addListener((obs, oldSt, newSt) -> {
            if (newSt == Worker.State.SUCCEEDED) {
                DesktopApp.appendCrashLog("boot: page loaded (" + engine.getLocation() + ")");
                scheduleProbe(1200, uiUrl, fallback);
                scheduleProbe(3500, uiUrl, fallback);
                scheduleProbe(BOOT_TIMEOUT_MS, uiUrl, fallback);
            } else if (newSt == Worker.State.FAILED) {
                Throwable ex = engine.getLoadWorker().getException();
                DesktopApp.appendCrashLog("boot: page load FAILED - "
                        + Bridge.safeMsg(ex));
                fallback.onLoadFailed();
            }
        });

        DesktopApp.appendCrashLog("boot: watchdog armed (url=" + uiUrl
                + ", prism.order=" + System.getProperty("prism.order", "default") + ")");
    }

    /** Probe the page: did boot() run and is the UI actually there? */
    private static void scheduleProbe(long delayMs, String uiUrl, LoadFallback fallback) {
        java.util.Timer t = new java.util.Timer("pm-bootprobe", true);
        t.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> probe(delayMs, uiUrl, fallback));
            }
        }, delayMs);
    }

    private static void probe(long atMs, String uiUrl, LoadFallback fallback) {
        if (guardDone || webRef == null) return;
        String bootState;
        try {
            Object r = webRef.getEngine().executeScript(
                "(function(){try{return JSON.stringify({"
                + "booted:!!(document.querySelector('#brandIcon svg')||document.querySelector('#view > *')),"
                + "shell:!!document.getElementById('appShell'),"
                + "css:(function(){try{return getComputedStyle(document.body).backgroundColor}catch(e){return 'n/a'}})(),"
                + "bridge:!!(window.PMPanel&&window.PMPanel.api),"
                + "url:location.href"
                + "})}catch(e){return '{\"err\":\"'+String(e.message||e)+'\"}'}})()");
            bootState = String.valueOf(r);
        } catch (Throwable t) {
            bootState = "{\"err\":\"" + Bridge.safeMsg(t) + "\"}";
        }
        org.json.JSONObject s;
        try {
            s = new org.json.JSONObject(bootState);
        } catch (Throwable t) {
            s = new org.json.JSONObject();
        }
        boolean hasShell = s.optBoolean("shell");
        boolean ui = s.optBoolean("booted");
        String css = s.optString("css", "n/a");
        boolean bridge = s.optBoolean("bridge");

        if (ui && hasShell) {
            booted = true;
            cssDark = css.contains("rgb(5, 6, 10)") || css.contains("rgb(5,6,10)");
            guardDone = true;
            DesktopApp.appendCrashLog("boot: OK (bridge=" + bridge + ", css=" + css + ")");
            schedulePaintCheck(1500, fallback);
            schedulePaintCheck(4000, fallback);
            return;
        }
        DesktopApp.appendCrashLog("boot: probe@" + atMs + "ms not ready yet -> " + bootState);

        if (atMs >= BOOT_TIMEOUT_MS) {
            guardDone = true;
            DesktopApp.appendCrashLog("boot: TIMEOUT - page never booted; triggering fallback");
            fallback.onBootTimeout();
        }
    }

    /* ================= paint self-check (white-window self-heal) ================= */

    private static void schedulePaintCheck(long delayMs, LoadFallback fallback) {
        java.util.Timer t = new java.util.Timer("pm-paintcheck", true);
        t.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> checkPaint(fallback));
            }
        }, delayMs);
    }

    private static void checkPaint(LoadFallback fallback) {
        if (!booted || webRef == null) return;
        try {
            WritableImage img = webRef.snapshot(null, new WritableImage(96, 96));
            int white = 0, total = 0;
            for (int y = 0; y < 96; y += 2) {
                for (int x = 0; x < 96; x += 2) {
                    Color c = img.getPixelReader().getColor(x, y);
                    total++;
                    if (c.getRed() >= 0.96 && c.getGreen() >= 0.96 && c.getBlue() >= 0.96) white++;
                }
            }
            double ratio = total == 0 ? 1.0 : (double) white / total;
            DesktopApp.appendCrashLog("boot: paint check white-ratio=" + String.format("%.2f", ratio)
                    + (cssDark ? " (page is dark)" : " (page css unknown)"));
            debugShot(img);
            if (cssDark && ratio > 0.9) {
                guardDone = true;
                DesktopApp.appendCrashLog("boot: WINDOW PAINTS WHITE although the page is dark"
                        + " - Direct3D/driver render bug; self-healing to software rendering");
                relaunchSoftware(fallback);
            }
        } catch (Throwable t) {
            DesktopApp.appendCrashLog("boot: paint check skipped - " + Bridge.safeMsg(t));
        }
    }

    /** Optional developer hook: -Dpm.debug.shot=/path/out.png dumps the snapshot. */
    private static void debugShot(WritableImage img) {
        String path = System.getProperty("pm.debug.shot");
        if (path == null || path.isEmpty() || img == null) return;
        try {
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            for (int y = 0; y < h; y++) {
                raw.write(0); // filter type: none
                for (int x = 0; x < w; x++) {
                    Color c = img.getPixelReader().getColor(x, y);
                    raw.write((int) Math.round(c.getRed() * 255));
                    raw.write((int) Math.round(c.getGreen() * 255));
                    raw.write((int) Math.round(c.getBlue() * 255));
                }
            }
            byte[] px = raw.toByteArray();
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(png);
            d.writeInt(0x89504E47); d.writeInt(0x0D0A1A0A);
            chunk(d, 0x49484452, ihdr(w, h));
            chunk(d, 0x49444154, deflate(px, w, h));
            chunk(d, 0x49454E44, new byte[0]);
            java.nio.file.Files.write(java.nio.file.Paths.get(path), png.toByteArray());
            DesktopApp.appendCrashLog("boot: debug snapshot written to " + path);
        } catch (Throwable t) {
            DesktopApp.appendCrashLog("boot: debug snapshot failed - " + Bridge.safeMsg(t));
        }
    }

    private static byte[] ihdr(int w, int h) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(b);
        try {
            d.writeInt(w); d.writeInt(h);
            d.writeByte(8); d.writeByte(2); // 8-bit RGB
            d.writeByte(0); d.writeByte(0); d.writeByte(0);
        } catch (java.io.IOException ignored) {
        }
        return b.toByteArray();
    }

    private static byte[] deflate(byte[] px, int w, int h) {
        try {
            java.io.ByteArrayOutputStream z = new java.io.ByteArrayOutputStream();
            java.util.zip.DeflaterOutputStream zo = new java.util.zip.DeflaterOutputStream(z);
            zo.write(px);
            zo.close();
            return z.toByteArray();
        } catch (java.io.IOException e) {
            return new byte[0];
        }
    }

    private static void chunk(java.io.DataOutputStream d, int type, byte[] data) {
        try {
            d.writeInt(data.length);
            d.writeInt(type);
            d.write(data);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            java.io.ByteArrayOutputStream t = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream td = new java.io.DataOutputStream(t);
            td.writeInt(type); td.write(data);
            crc.update(t.toByteArray());
            d.writeInt((int) crc.getValue());
        } catch (java.io.IOException ignored) {
        }
    }

    /** Relaunch the packaged exe (or gradle run) with the software pipeline. */
    private static void relaunchSoftware(LoadFallback fallback) {
        File flag = new File(AppDirs.filesDir(), "sw-mode.flag");
        long now = System.currentTimeMillis();
        if (flag.isFile()) {
            try {
                long ts = Long.parseLong(new String(java.nio.file.Files.readAllBytes(flag.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim());
                if (now - ts < RELAUNCH_COOLDOWN_MS) {
                    DesktopApp.appendCrashLog("boot: software-mode relaunch already tried recently - showing error page");
                    fallback.showRecoveryPage();
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            java.nio.file.Files.write(flag.toPath(),
                    String.valueOf(now).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }

        String exe = System.getProperty("jpackage.app-path");
        java.util.List<String> cmd = new java.util.ArrayList<>();
        if (exe != null && new File(exe).isFile()) {
            cmd.add(exe);
        } else {
            // running from IDE/gradle: relaunch java itself with the same classpath
            String cp = System.getProperty("java.class.path");
            cmd.add(System.getProperty("java.home") + java.io.File.separator + "bin"
                    + java.io.File.separator + (File.separatorChar == '\\' ? "java.exe" : "java"));
            cmd.add("-Dprism.order=sw");
            if (cp != null && !cp.isEmpty()) cmd.add("-cp");
            if (cp != null && !cp.isEmpty()) cmd.add(cp);
            cmd.add(DesktopApp.class.getName().replace('.', '/').replace('/', '.'));
        }
        cmd.add("-J-Dprism.order=sw");
        cmd.add("-J-Dprism.dirtyopts=false");

        DesktopApp.appendCrashLog("boot: relaunching with software rendering: " + cmd);
        try {
            new ProcessBuilder(cmd).directory(new File(".").getAbsoluteFile()).start();
        } catch (Throwable t) {
            DesktopApp.appendCrashLog("boot: relaunch failed - " + Bridge.safeMsg(t));
            fallback.showRecoveryPage();
            return;
        }
        Platform.runLater(DesktopApp::exitForRestart);
    }

    /* ================= recovery page (last resort) ================= */

    /** Dark self-explanatory page shown when nothing else worked. */
    static String recoveryPageUrl() {
        String logDir = Installer.logsDir().getAbsolutePath();
        String html =
            "<!DOCTYPE html><html><head><meta charset='utf-8'><title>PM Panel</title></head>"
            + "<body style='margin:0;background:#05060A;color:#fafafa;"
            + "font-family:Segoe UI,Tahoma,sans-serif;display:flex;align-items:center;"
            + "justify-content:center;height:100vh;text-align:center;padding:28px'>"
            + "<div style='max-width:640px'>"
            + "<div style='font-size:40px;margin-bottom:14px'>&#9888;&#65039;</div>"
            + "<h2 style='margin:0 0 12px;font-weight:600'>PM Panel could not start its interface</h2>"
            + "<p style='color:#a3a8b8;line-height:1.8;font-size:14px;margin:0 0 6px'>"
            + "رابط کاربری بارگذاری نشد. معمولاً بستن و باز کردن دوباره برنامه مشکل را حل می‌کند.</p>"
            + "<p style='color:#a3a8b8;line-height:1.8;font-size:14px;margin:0 0 18px'>"
            + "The panel UI failed to load. Restarting the app usually fixes this.<br>"
            + "Details were written to the log file:<br>"
            + "<span style='direction:ltr;display:inline-block;font-family:Consolas,monospace;"
            + "font-size:12px;color:#57a5ff'>" + escape(logDir) + "</span></p>"
            + "<button onclick='location.reload()' style='background:#006fee;color:#fff;border:none;"
            + "border-radius:12px;padding:11px 26px;font-size:14px;cursor:pointer'>Retry · تلاش دوباره</button>"
            + "</div></body></html>";
        return dataUri(html);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String dataUri(String html) {
        String b64 = java.util.Base64.getEncoder().encodeToString(
                html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "data:text/html;charset=utf-8;base64," + b64;
    }
}
