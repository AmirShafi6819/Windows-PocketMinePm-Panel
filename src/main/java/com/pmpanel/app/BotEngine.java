package com.pmpanel.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Manages the fake-player engine process (pmbot.exe - a Go binary speaking
 * the real Bedrock protocol via gophertunnel).
 *
 * Protocol: JSON lines over stdin (ops) / stdout (events). Every stdout line
 * is pushed into the WebView through DesktopApp.pushBotEvent().
 *
 * Ported from Android: the binary is now the GOOS=windows build shipped in
 * the app jar (extracted next to the PHP runtime on first run). Hostnames
 * are still resolved on the Java side before every op so the engine always
 * dials IP literals (identical to the Android flow).
 */
public final class BotEngine {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private static volatile Process process;
    private static volatile BufferedWriter stdin;
    private static volatile boolean wantStop = false;

    private BotEngine() {
    }

    public static boolean available() {
        File f = binary();
        return f != null && f.isFile();
    }

    public static boolean running() {
        Process p = process;
        return p != null && p.isAlive();
    }

    private static File binary() {
        return Installer.botBinary();
    }

    public static synchronized JSONObject start() {
        if (!available()) {
            return ServerManager.JO("ok", false, "error", "bot engine binary is missing on this device");
        }
        if (running()) {
            return ServerManager.JO("ok", true, "running", true);
        }
        wantStop = false;
        try {
            File bin = binary();
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath());
            pb.redirectErrorStream(false);
            java.util.Map<String, String> env = pb.environment();
            env.put("HOME", AppDirs.filesDir().getAbsolutePath());
            env.put("TMPDIR", AppDirs.cacheTmp().getAbsolutePath());
            env.put("TEMP", AppDirs.cacheTmp().getAbsolutePath());
            env.remove("LD_PRELOAD");
            env.remove("LD_LIBRARY_PATH");
            env.remove("TERM");
            process = pb.start();
        } catch (Throwable t) {
            process = null;
            return ServerManager.JO("ok", false, "error", "failed to start bot engine: " + Bridge.safeMsg(t));
        }

        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        final Process p = process;
        // stdout -> WebView events
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 1 << 15)) {
                String line;
                while ((line = r.readLine()) != null) {
                    /* 64 KB ceiling: pack-list events carry file names+sizes and
                     * can legitimately exceed the old 4 KB truncation, which
                     * produced broken JSON on the JS side. */
                    if (line.length() > 65536) line = line.substring(0, 65536);
                    DesktopApp.pushBotEvent(line);
                }
            } catch (Throwable ignored) {
            }
        }, "pm-bot-reader");
        reader.setDaemon(true);
        reader.start();

        // stderr -> app crash log (engine normally writes nothing there)
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8), 1 << 12)) {
                String line;
                while ((line = r.readLine()) != null) {
                    DesktopApp.appendCrashLog("bot-engine: " + line);
                }
            } catch (Throwable ignored) {
            }
        }, "pm-bot-err");
        errReader.setDaemon(true);
        errReader.start();

        return ServerManager.JO("ok", true, "running", true);
    }

    /** Send one op line to the engine; resolves hostnames first. */
    public static synchronized JSONObject send(String line) {
        Process p = process;
        BufferedWriter w = stdin;
        if (p == null || !p.isAlive()) {
            return ServerManager.JO("ok", false, "error", "engine not running");
        }
        try {
            w.write(resolveHostsInOp(line));
            w.write("\n");
            w.flush();
        } catch (IOException e) {
            return ServerManager.JO("ok", false, "error", "failed to write to engine: " + Bridge.safeMsg(e));
        }
        return ServerManager.JO("ok", true);
    }

    public static synchronized JSONObject stop() {
        wantStop = true;
        Process p = process;
        if (p == null) return ServerManager.JO("ok", true, "running", false);
        try {
            send("{\"op\":\"stop\"}");
        } catch (Throwable ignored) {
        }
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        process = null;
        stdin = null;
        return ServerManager.JO("ok", true, "running", false);
    }

    /** Kill the engine immediately (app teardown). */
    public static synchronized void kill() {
        wantStop = true;
        Process p = process;
        process = null;
        stdin = null;
        if (p != null) {
            try { p.destroyForcibly(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // host resolution: "play.example.com:19132" -> "93.184.x.x:19132"
    // ------------------------------------------------------------------

    private static String resolveHostsInOp(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String op = o.optString("op");
            if (!"spawn".equals(op) && !"ping".equals(op) && !"getpacks".equals(op)) return line;
            String addr = o.optString("addr");
            String resolved = resolveAddr(addr);
            if (resolved != null && !resolved.equals(addr)) {
                o.put("addr", resolved);
                return o.toString();
            }
        } catch (Throwable ignored) {
        }
        return line;
    }

    private static String resolveAddr(String addr) {
        if (addr == null || addr.isEmpty()) return addr;
        // "host" without a port: the engine (Go) adds :19132 itself; the host
        // is resolved HERE, on the Java side, in every shape it can arrive.
        if (addr.lastIndexOf(':') < 0) addr = addr + ":19132";
        int i = addr.lastIndexOf(':');
        if (i <= 0) return addr;
        String host = addr.substring(0, i);
        String port = addr.substring(i + 1);
        if (IPV4.matcher(host).matches() || host.contains(":")) return addr; // IP literal
        try {
            InetAddress a = InetAddress.getByName(host);
            String ip = a.getHostAddress();
            if (ip != null && !ip.isEmpty()) return ip + ":" + port;
        } catch (Throwable ignored) {
        }
        return addr;
    }
}
