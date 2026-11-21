package com.pmpanel.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the PocketMine PHP process lifecycle and console I/O.
 *
 * v2.4: one instance per server PROFILE. The UI picks the profile (a
 * "profile" field on every bridge call); several servers can run at the same
 * time, each with its own process, console buffer and log files. The default
 * profile keeps the legacy single-server directory.
 *
 * Ported from Android: the process is the pmmp Windows php.exe, PID comes
 * from Process.pid() (no /proc scan), CPU/RSS sampling is done by a
 * PowerShell probe (WinProc) instead of /proc/<pid>/stat, the low-RAM guard
 * uses the com.sun.management OS bean, and the Android foreground service is
 * replaced by the TrayService state (the processes are plain children here,
 * so nothing else is needed to keep them alive).
 */
public class ServerManager {

    public static final int STATE_IDLE = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_STOPPING = 3;
    public static final int STATE_STOPPED = 4;
    public static final int STATE_CRASHED = 5;

    private static final int MAX_CONSOLE_LINES = 6000;
    private static final int KEEP_LOGS = 8;

    private static final Map<String, ServerManager> instances = new HashMap<>();

    /** The legacy single-server manager (default profile). */
    public static ServerManager get() {
        return forProfile(Profiles.DEFAULT_ID);
    }

    /** Manager for a profile id (created on first use, cached afterwards). */
    public static ServerManager forProfile(String profileId) {
        String id = Profiles.validId(profileId) ? profileId : Profiles.DEFAULT_ID;
        synchronized (instances) {
            ServerManager m = instances.get(id);
            if (m == null) {
                m = new ServerManager(id);
                instances.put(id, m);
            }
            return m;
        }
    }

    /** Existing manager or null (no side effects - safe for status lists). */
    public static ServerManager peek(String profileId) {
        synchronized (instances) {
            return instances.get(profileId);
        }
    }

    /** True when at least one profile's process is alive (keeps the tray state up). */
    public static boolean anyRunning() {
        synchronized (instances) {
            for (ServerManager m : instances.values()) {
                if (m.isRunning()) return true;
            }
        }
        return false;
    }

    /** Names of the currently running profiles (tray tooltip / notification). */
    public static String runningNames() {
        List<String> names = new ArrayList<>();
        synchronized (instances) {
            for (Map.Entry<String, ServerManager> e : instances.entrySet()) {
                if (e.getValue().isRunning()) names.add(e.getValue().profileName());
            }
        }
        return names.isEmpty() ? "No server running"
                : (names.size() == 1 ? "Server running: " + names.get(0)
                : "Servers running: " + String.join(", ", names));
    }

    public static void stopAll() {
        synchronized (instances) {
            for (ServerManager m : instances.values()) m.stopAsync();
        }
    }

    public final String profileId;
    public final Fs fs;
    private final File serverDir;
    private final File tmpDir;

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile int state = STATE_IDLE;
    private volatile int exitCode = 0;
    private volatile long startedAt = 0;
    private volatile String lastError = null;
    private volatile boolean wantStop = false;
    private final AtomicBoolean reaped = new AtomicBoolean(false);

    private final Object consoleLock = new Object();
    private final ArrayDeque<String> consoleLines = new ArrayDeque<>();
    private long seq = 0;
    private int consoleSession = 0;

    /* ---- v2.7 statistics: parsed from the console stream ---- */
    /** ANSI-stripped live set of players currently on this server (join/leave msgs). */
    private final java.util.LinkedHashSet<String> onlinePlayers = new java.util.LinkedHashSet<>();
    private volatile double tpsCurrent = -1, tpsAverage = -1, tickLoad = -1;
    private volatile double netUpKbps = -1, netDownKbps = -1;
    private volatile long tpsAt = 0;
    private volatile String lastStatusBlock = "";
    private volatile long lastStatusAt = 0;
    private volatile int lastListNum = -1, lastListMax = -1;
    private boolean statusCollecting = false;
    private int statusCollected = 0;
    private final StringBuilder statusBuf = new StringBuilder();
    /* CPU% sampling (Windows: PowerShell probe instead of /proc) */
    private volatile WinProc winProc;
    private volatile double cpuPercent = -1;

    private volatile BufferedWriter logWriter;
    private volatile File currentLogFile;

    private ServerManager(String profileId) {
        this.profileId = profileId;
        this.serverDir = Profiles.dirOf(profileId);
        this.fs = new Fs(serverDir);
        this.tmpDir = Installer.cacheTmp();
    }

    public String profileName() {
        return Profiles.nameOf(profileId);
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public long uptimeSec() {
        return (state == STATE_STARTING || state == STATE_RUNNING || state == STATE_STOPPING) && startedAt > 0
                ? (System.currentTimeMillis() - startedAt) / 1000 : 0;
    }

    /**
     * Locate the 64-bit PHP for this machine (shared by all profiles).
     * The pmmp Windows x64 runtime is extracted on first run into
     * %APPDATA%\PMPanel\bin\php (see Installer.ensureBinaries).
     */
    public File resolvePhp() {
        File php = Installer.phpBinary();
        if (php.isFile()) return php;
        // development/testing fallback on non-Windows machines (bin/php/php)
        File unixPhp = new File(php.getParentFile(), "php");
        return unixPhp.isFile() ? unixPhp : null;
    }

    public String stateName() {
        switch (state) {
            case STATE_STARTING: return "starting";
            case STATE_RUNNING: return "running";
            case STATE_STOPPING: return "stopping";
            case STATE_STOPPED: return "stopped";
            case STATE_CRASHED: return "crashed";
            default: return "idle";
        }
    }

    /** JSON object builder that never throws (safe inside lambdas/threads). */
    static JSONObject JO(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    public JSONObject ok() {
        return JO("ok", true);
    }

    public JSONObject err(String msg) {
        return JO("ok", false, "error", msg);
    }

    public synchronized JSONObject start() {
        if (isRunning() || state == STATE_STARTING) {
            return err("server is already running");
        }
        try {
            Installer.ensure(serverDir);
        } catch (Throwable e) {
            state = STATE_IDLE;
            return err("setup failed: " + Bridge.safeMsg(e));
        }

        // Every server MUST own a server.properties with its own UDP port: a
        // server started without one falls back to PocketMine's default 19132
        // and collides with any other server created afterwards.
        try {
            Installer.ensureServerProperties(serverDir);
            Installer.applyProfileMemory(profileId, serverDir);
        } catch (Throwable ignored) {
        }

        File php = resolvePhp();
        if (php == null) {
            state = STATE_CRASHED;
            lastError = "PHP server binary is missing (setup problem). Reinstall the app and try again.";
            return err(lastError);
        }
        File phar = new File(serverDir, "PocketMine-MP.phar");
        if (!phar.isFile()) {
            state = STATE_IDLE;
            return err("PocketMine-MP.phar not found");
        }
        File ini = new File(serverDir, "php.ini");
        if (!ini.isFile()) {
            state = STATE_IDLE;
            return err("php.ini not found");
        }

        wantStop = false;
        exitCode = 0;
        lastError = null;
        reaped.set(false);
        state = STATE_STARTING;
        startedAt = System.currentTimeMillis();

        // A new server session starts with a fresh console buffer; the web panel sees the
        // session counter change and clears its views too.
        synchronized (consoleLock) {
            consoleLines.clear();
            consoleSession++;
        }
        onlinePlayers.clear();
        tpsCurrent = tpsAverage = tickLoad = netUpKbps = netDownKbps = -1;
        tpsAt = lastStatusAt = 0;
        lastStatusBlock = "";
        lastListNum = lastListMax = -1;
        statusCollecting = false;
        cpuPercent = -1;

        /* Running a SECOND server needs its own ~450 MB of free RAM: without this
         * guard the OS may starve one of the two PHP processes. The Android
         * ActivityManager check became the com.sun.management OS bean. */
        try {
            int runningOthers = 0;
            for (String[] p : Profiles.all()) {
                if (p[0].equals(profileId)) continue;
                ServerManager other = ServerManager.peek(p[0]);
                if (other != null && other.isRunning()) runningOthers++;
            }
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long avail = os.getFreePhysicalMemorySize();
            long need = (runningOthers > 0 ? 450L : 280L) * 1024 * 1024;
            if (avail > 0 && avail < need) {
                state = STATE_IDLE;
                return err("not enough free RAM to start this server (" + (avail / (1024 * 1024))
                        + " MB free, about " + (need / (1024 * 1024)) + " MB needed) - stop another server or free memory");
            }
        } catch (Throwable ignored) {
        }

        // Port self-heal: if another RUNNING server already owns this profile's
        // UDP port, silently move this profile to a genuinely free one instead
        // of letting PocketMine crash at boot. (Creation already picks a unique
        // port; this only fixes legacy profiles created before that guard.)
        try {
            int myPort = Profiles.readPort(serverDir);
            if (myPort > 0) {
                for (String[] p : Profiles.all()) {
                    if (p[0].equals(profileId)) continue;
                    ServerManager other = ServerManager.peek(p[0]);
                    if (other != null && other.isRunning()
                            && Profiles.readPort(other.fs.getRoot()) == myPort) {
                        int np = Profiles.nextFreePort();
                        if (np != myPort) {
                            Installer.setServerPort(serverDir, np);
                            addConsole("[PMPanel] UDP port " + myPort + " is used by \""
                                    + p[1] + "\" - this server now uses " + np
                                    + " (change server-port in Settings if you prefer another)");
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        /* Source plugins (plugin.yml + src/ folders) are compiled to .phar right
         * here, on every start: no DevTools needed, edits are picked up on
         * restart. Users reported source plugins not loading even with DevTools
         * installed (its phar is usually built for another API version), so the
         * panel now does the job itself. Runs AFTER the console reset so the
         * compile lines land in the new session. */
        try {
            JSONObject compiled = PluginTools.autoCompileSourcePlugins(fs);
            JSONArray done = compiled.optJSONArray("compiled");
            for (int i = 0; done != null && i < done.length(); i++) {
                addConsole("[PMPanel] compiled source plugin -> plugins/" + done.optString(i));
            }
            JSONArray failed = compiled.optJSONArray("failed");
            for (int i = 0; failed != null && i < failed.length(); i++) {
                addConsole("[PMPanel] source plugin compile FAILED: " + failed.optString(i));
            }
        } catch (Throwable t) {
            addConsole("[PMPanel] source plugin scan failed: " + Bridge.safeMsg(t));
        }

        try {
            openLogFile();
            //noinspection ResultOfMethodCallIgnored
            tmpDir.mkdirs();

            ProcessBuilder pb = new ProcessBuilder(
                    php.getAbsolutePath(),
                    "-c", ini.getAbsolutePath(),
                    phar.getAbsolutePath(),
                    "--no-wizard",
                    "--no-log-file",
                    "--enable-ansi",
                    "--data=" + serverDir.getAbsolutePath(),
                    "--plugins=" + new File(serverDir, "plugins").getAbsolutePath());
            pb.directory(serverDir);
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.put("TMPDIR", tmpDir.getAbsolutePath());
            env.put("TEMP", tmpDir.getAbsolutePath());
            env.put("HOME", AppDirs.filesDir().getAbsolutePath());
            // Put the php runtime first on PATH so its DLLs resolve deterministically;
            // LD_* and TERM removals mirror the Android launcher (TERM unset keeps
            // PocketMine on portable 256-color ANSI codes instead of shelling to tput).
            env.put("PATH", php.getParentFile().getAbsolutePath() + ";" + env.getOrDefault("PATH", ""));

            addConsole("# " + profileName() + " - " +
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));

            process = pb.start();
        } catch (Throwable t) {
            state = STATE_CRASHED;
            lastError = "failed to start process: " + Bridge.safeMsg(t);
            closeLogFile();
            addConsole("# ERROR: " + lastError);
            return err(lastError);
        }

        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        final Process p = process;
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    onConsoleLine(line);
                }
            } catch (Throwable ignored) {
            } finally {
                onProcessExit(p);
            }
        }, "pm-console-reader-" + profileId);
        reader.setDaemon(true);
        reader.start();

        // CPU/RSS probe for the statistics view (replaces /proc sampling)
        WinProc old = winProc;
        winProc = WinProc.start(p.pid());
        if (old != null) old.close();

        TrayService.updateState(runningNames());
        return ok();
    }

    public void stopAsync() {
        new Thread(this::stopInternal, "pm-stop-" + profileId).start();
    }

    public void restartAsync() {
        new Thread(() -> {
            stopInternal();
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            start();
        }, "pm-restart-" + profileId).start();
    }

    private synchronized void stopInternal() {
        Process p = process;
        if (p == null) {
            return;
        }
        wantStop = true;
        if (state == STATE_RUNNING || state == STATE_STARTING) {
            state = STATE_STOPPING;
        }
        JSONObject r = sendCommand("stop");
        if (!r.optBoolean("ok")) {
            // maybe the reader is stuck; destroy directly
        }
        for (int i = 0; i < 250; i++) {
            if (!p.isAlive() || process != p) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (p.isAlive() && process == p) {
            p.destroy();
            try {
                if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void onProcessExit(Process p) {
        if (!reaped.compareAndSet(false, true)) return;
        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            exitCode = code;
            boolean graceful = wantStop || code == 0;
            if (state != STATE_CRASHED || graceful) {
                state = graceful ? STATE_STOPPED : STATE_CRASHED;
            }
            if (state == STATE_CRASHED && lastError == null) {
                lastError = "process exited with code " + code;
            }
            if (lastError == null && !graceful) lastError = "process exited with code " + code;
            stdin = null;
            process = null;
            closeLogFile();
        }
        WinProc wp = winProc;
        winProc = null;
        if (wp != null) wp.close();
        // the tray state only goes away when the LAST server stops;
        // while others still run, the tooltip is refreshed (name list)
        if (anyRunning()) TrayService.updateState(runningNames());
        else {
            TrayService.updateState("No server running");
            DesktopApp.onAllServersStopped();
        }
    }

    public synchronized JSONObject sendCommand(String cmd) {
        BufferedWriter w = stdin;
        Process p = process;
        if (w == null || p == null || !p.isAlive()) {
            return err("server is not running");
        }
        try {
            w.write(cmd);
            w.write("\n");
            w.flush();
        } catch (IOException e) {
            return err("failed to write command: " + Bridge.safeMsg(e));
        }
        return ok();
    }

    private void onConsoleLine(String line) {
        addConsole(line);
        if (line.contains("For help, type")) {
            if (state == STATE_STARTING) state = STATE_RUNNING;
        } else if (line.contains("An unrecoverable error has occurred")) {
            state = STATE_CRASHED;
            lastError = "server crashed (see logs)";
        }
        analyzeLine(line);
    }

    /* ---------------- v2.7 console analysis (statistics source) ---------------- */

    private static String stripAnsi(String s) {
        if (s.indexOf(0x1B) < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == 0x1B) {
                int j = i + 1;
                if (j < s.length() && s.charAt(j) == '[') {
                    j++;
                    while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == ';')) j++;
                    if (j < s.length() && s.charAt(j) == 'm') j++;
                } else {
                    j = Math.min(j + 1, s.length());
                }
                i = j;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private void analyzeLine(String raw) {
        String line = stripAnsi(raw);
        try {
            int ji = line.indexOf(" joined the game");
            if (ji > 0) {
                String name = playerNameFromConsole(line, ji);
                if (!name.isEmpty()) synchronized (onlinePlayers) { onlinePlayers.add(name); }
            }
            int li = line.indexOf(" left the game");
            if (li > 0) {
                String name = playerNameFromConsole(line, li);
                if (!name.isEmpty()) synchronized (onlinePlayers) { onlinePlayers.remove(name); }
            }
            int li2 = line.indexOf("players online:");
            if (li2 > 0) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("There are (\\d+)/(\\d+) players online")
                        .matcher(line);
                if (m.find()) {
                    lastListNum = Integer.parseInt(m.group(1));
                    lastListMax = Integer.parseInt(m.group(2));
                }
            }
            if (line.contains("Current TPS:")) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("Current TPS:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(line);
                if (m.find()) {
                    tpsCurrent = Double.parseDouble(m.group(1));
                    tpsAt = System.currentTimeMillis();
                }
                java.util.regex.Matcher ml = java.util.regex.Pattern
                        .compile("\\(([0-9]+(?:\\.[0-9]+)?)%\\)").matcher(line);
                if (ml.find()) tickLoad = Double.parseDouble(ml.group(1));
                /* the /status reply block: capture this line + the following ones */
                statusCollecting = true;
                statusCollected = 1;
                statusBuf.setLength(0);
                statusBuf.append(line);
                lastStatusAt = System.currentTimeMillis();
            } else if (statusCollecting) {
                statusBuf.append('\n').append(line);
                statusCollected++;
                if (statusCollected >= 16 || line.contains("Average TPS") == false && statusCollected > 3 && !isStatusContinuation(line)) {
                    lastStatusBlock = statusBuf.toString();
                    statusCollecting = false;
                }
                if (line.contains("Average TPS:")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("Average TPS:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(line);
                    if (m.find()) tpsAverage = Double.parseDouble(m.group(1));
                } else if (line.contains("Network upload:")) {
                    netUpKbps = parseKbps(line);
                } else if (line.contains("Network download:")) {
                    netDownKbps = parseKbps(line);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isStatusContinuation(String line) {
        return line.contains("Network") || line.contains("memory") || line.contains("Memory")
                || line.contains("Thread") || line.contains("World") || line.contains("chunks")
                || line.contains("entities") || line.contains("TPS") || line.contains("Load")
                || line.contains("kB/s") || line.contains("MB");
    }

    private static double parseKbps(String line) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([0-9]+(?:\\.[0-9]+)?) kB/s").matcher(line);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String playerNameFromConsole(String line, int idx) {
        String s = line.substring(0, idx).trim();
        int c = s.lastIndexOf("]: ");
        if (c >= 0) s = s.substring(c + 3).trim();
        // PM5 formats like "Player [Steve/xuid]" or plain "Steve"
        if (s.startsWith("Player [")) {
            s = s.substring(8);
            int slash = s.indexOf('/');
            if (slash > 0) s = s.substring(0, slash);
            s = s.replace("]", "").trim();
        }
        if (s.length() > 40 || s.isEmpty() || s.contains(" ") && s.contains("[")) return "";
        return s;
    }

    private void addConsole(String line) {
        synchronized (consoleLock) {
            seq++;
            consoleLines.addLast(seq + "\u0001" + line);
            while (consoleLines.size() > MAX_CONSOLE_LINES) consoleLines.removeFirst();
        }
        BufferedWriter w = logWriter;
        if (w != null) {
            try {
                w.write(line);
                w.newLine();
                w.flush();
            } catch (IOException ignored) {
            }
        }
    }

    public long lastSeq() {
        synchronized (consoleLock) {
            return seq;
        }
    }

    public JSONObject consoleSince(long after) throws JSONException {
        JSONArray arr = new JSONArray();
        long last;
        synchronized (consoleLock) {
            last = seq;
            /* PERF: the buffer is seq-ordered (addConsole appends with seq++).
             * Scanning BACKWARDS stops at the first line the caller already
             * has, so a poll costs O(new lines) instead of re-parsing the
             * whole 6000-line buffer every 1.3s on the UI-blocking bridge. */
            java.util.Iterator<String> it = consoleLines.descendingIterator();
            java.util.ArrayList<long[]> seqs = new java.util.ArrayList<>();
            java.util.ArrayList<String> texts = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String entry = it.next();
                int i = entry.indexOf('\u0001');
                if (i <= 0) continue;
                long s;
                try {
                    s = Long.parseLong(entry.substring(0, i));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (s <= after) break;
                seqs.add(new long[]{s});
                texts.add(entry.substring(i + 1));
            }
            for (int k = seqs.size() - 1; k >= 0; k--) {
                JSONArray pair = new JSONArray();
                pair.put(seqs.get(k)[0]);
                pair.put(texts.get(k));
                arr.put(pair);
            }
        }
        JSONObject res = new JSONObject();
        res.put("ok", true);
        res.put("lines", arr);
        res.put("last", last);
        res.put("session", consoleSession);
        return res;
    }

    public JSONObject status() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("ok", true);
        res.put("state", stateName());
        res.put("uptimeSec", uptimeSec());
        res.put("pid", pidOf());
        res.put("exitCode", exitCode);
        if (lastError != null) res.put("lastError", lastError);
        res.put("rssKb", readRss());
        res.put("freeSpace", serverDir.getFreeSpace());
        res.put("consoleLast", lastSeq());
        res.put("session", consoleSession);
        res.put("profile", profileId);
        res.put("name", profileName());
        /* v2.7 statistics fields */
        res.put("cpuPercent", Math.round(sampleCpu() * 10.0) / 10.0);
        JSONArray players = new JSONArray();
        synchronized (onlinePlayers) {
            for (String p : onlinePlayers) players.put(p);
        }
        res.put("playersOnline", players);
        res.put("playersCount", players.length());
        if (lastListMax > 0) {
            res.put("playersMax", lastListMax);
        } else {
            res.put("playersMax", maxPlayersFromProps());
        }
        if (tpsAt > 0) res.put("tpsCurrent", tpsCurrent);
        if (tpsAverage >= 0) res.put("tpsAverage", tpsAverage);
        if (tickLoad >= 0) res.put("tickLoad", tickLoad);
        if (netUpKbps >= 0) res.put("netUpKbps", netUpKbps);
        if (netDownKbps >= 0) res.put("netDownKbps", netDownKbps);
        if (tpsAt > 0) res.put("tpsAgeSec", (System.currentTimeMillis() - tpsAt) / 1000);
        if (lastStatusBlock.length() > 0) {
            res.put("lastStatus", lastStatusBlock);
            res.put("lastStatusAgeSec", (System.currentTimeMillis() - lastStatusAt) / 1000);
        }
        return res;
    }

    /* PERF: status() runs on every 1.3s poll; reading + parsing
     * server.properties each time stalled the UI thread on slow disks.
     * The value is cached for 60s (it only changes when the user edits the
     * file, and the live player list overrides it anyway once known). */
    private int maxPlayersCache = -1;
    private long maxPlayersAt = 0;

    private int maxPlayersFromProps() {
        long now = System.currentTimeMillis();
        if (maxPlayersCache > 0 && now - maxPlayersAt < 60_000) return maxPlayersCache;
        int fallback = 20;
        try {
            File f = new File(serverDir, "server.properties");
            if (!f.isFile()) {
                maxPlayersCache = fallback;
                maxPlayersAt = now;
                return fallback;
            }
            for (String line : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("max-players=")) {
                    try {
                        fallback = Math.max(1, Integer.parseInt(line.substring(12).trim()));
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        maxPlayersCache = fallback;
        maxPlayersAt = now;
        return fallback;
    }

    /** CPU% of the php process (PowerShell probe deltas on Windows). */
    private double sampleCpu() {
        WinProc wp = winProc;
        if (wp == null) return -1;
        double cpu = wp.cpuPercent();
        cpuPercent = cpu;
        return cpu;
    }

    /** Players currently on this server (console join/leave tracking). */
    public java.util.List<String> currentPlayers() {
        synchronized (onlinePlayers) {
            return new java.util.ArrayList<>(onlinePlayers);
        }
    }

    private long pidOf() {
        Process p = process;
        return p == null ? 0 : p.pid();
    }

    private long readRss() {
        WinProc wp = winProc;
        return wp == null ? 0 : wp.rssKb();
    }

    private void openLogFile() throws IOException {
        closeLogFile();
        File logs = new File(serverDir, "logs");
        //noinspection ResultOfMethodCallIgnored
        logs.mkdirs();
        rotateLogs(logs);
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        currentLogFile = new File(logs, "server-" + stamp + ".log");
        logWriter = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(currentLogFile), StandardCharsets.UTF_8));
    }

    private void rotateLogs(File logs) {
        File[] files = logs.listFiles();
        if (files == null || files.length <= KEEP_LOGS) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - KEEP_LOGS; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private void closeLogFile() {
        BufferedWriter w = logWriter;
        logWriter = null;
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        }
    }
}
