package com.pmpanel.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windows replacement for the Android /proc/&lt;pid&gt;/stat + /proc/&lt;pid&gt;/status
 * sampling done by ServerManager (CPU% + RSS of the PHP process).
 *
 * Spawns ONE long-running PowerShell probe per running server that prints
 * "cpu=<ms> rss=<kb>" every second; a reader thread parses those lines and the
 * ServerManager computes the CPU% from consecutive deltas exactly like the
 * Android implementation did with /proc ticks. When PowerShell is unavailable
 * (or fails) the caller gets cpu=-1 / rss=0 and the UI shows "unknown" -
 * identical to the Android behavior on devices where /proc is restricted.
 */
public final class WinProc {

    private volatile Process probe;
    private volatile double cpuMs = -1;   // cumulative processor time (ms) from the last line
    private volatile long rssKb = 0;
    private volatile long atMs = 0;
    private volatile int cpuCores = Math.max(1, Runtime.getRuntime().availableProcessors());
    private volatile double lastCpuPercent = -1;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread reader;

    private WinProc(long pid) {
        String script = "$p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                + "if($p -eq $null){exit 0}; "
                + "while($true){ "
                + "$p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                + "if($p -eq $null){exit 0}; "
                + "$c=[long]$p.TotalProcessorTime.TotalMilliseconds; "
                + "$m=[long]($p.WorkingSet64/1KB); "
                + "Write-Output ('cpu=' + $c + ' rss=' + $m); "
                + "Start-Sleep -Milliseconds 1000 }";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-Command", script);
            pb.redirectErrorStream(true);
            probe = pb.start();
        } catch (Throwable t) {
            probe = null;
            reader = null;
            return;
        }
        reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed.get() && (line = r.readLine()) != null) {
                    parse(line);
                }
            } catch (Throwable ignored) {
            }
        }, "pm-winproc-" + pid);
        reader.setDaemon(true);
        reader.start();
    }

    private void parse(String line) {
        long c = -1, m = -1;
        int i = line.indexOf("cpu=");
        if (i >= 0) {
            int j = line.indexOf(' ', i);
            String v = line.substring(i + 4, j < 0 ? line.length() : j).trim();
            try {
                c = Long.parseLong(v);
            } catch (NumberFormatException ignored) {
            }
        }
        i = line.indexOf("rss=");
        if (i >= 0) {
            String v = line.substring(i + 4).trim();
            try {
                m = Long.parseLong(v);
            } catch (NumberFormatException ignored) {
            }
        }
        if (c >= 0) {
            long now = System.currentTimeMillis();
            double prev = cpuMs;
            long prevAt = atMs;
            cpuMs = c;
            atMs = now;
            if (prev >= 0 && prevAt > 0 && now > prevAt) {
                double dCpu = c - prev;
                double dt = (now - prevAt);
                if (dCpu >= 0 && dt >= 300) {
                    double pct = (dCpu / dt) * 100.0;   // percent of ONE core (same as /proc math)
                    if (pct > 800) pct = 800;
                    lastCpuPercent = pct;
                }
            }
        }
        if (m >= 0) rssKb = m;
    }

    /** Latest CPU% of one core (calibrated over >=300 ms), or -1 when unknown. */
    public double cpuPercent() {
        return lastCpuPercent;
    }

    /** Resident set size in KB (0 unknown). */
    public long rssKb() {
        return rssKb;
    }

    public void close() {
        closed.set(true);
        Process p = probe;
        probe = null;
        if (p != null) {
            p.destroy();
        }
    }

    public static WinProc start(long pid) {
        if (pid <= 0) return null;
        return new WinProc(pid);
    }

    /** Quick existence probe used by Installer (can we run PowerShell at all?). */
    public static boolean powershellAvailable() {
        try {
            File f = new File(System.getenv("WINDIR") == null ? "C:\\Windows" : System.getenv("WINDIR"),
                    "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            return f.isFile();
        } catch (Throwable t) {
            return false;
        }
    }
}
