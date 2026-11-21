package com.pmpanel.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-server profile registry.
 *
 * A profile is one independent PocketMine server: its own directory, phar,
 * server.properties (unique port), worlds, plugins and console. The FIRST
 * profile ("default") keeps the historic <filesDir>/server directory so an
 * app update never moves existing user data; every other profile lives under
 * <filesDir>/servers/<id>.
 *
 * The registry itself is a JSON array [{id,name}] in pmpanel.json, so it
 * survives reinstalls of the app data dir layout.
 */
public final class Profiles {

    public static final String DEFAULT_ID = "default";

    private Profiles() {
    }

    /* ================= storage ================= */

    private static Prefs prefs() {
        return Installer.prefs();
    }

    private static synchronized JSONArray readAll() {
        try {
            return new JSONArray(prefs().getString("profiles", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized void writeAll(JSONArray arr) {
        prefs().put("profiles", arr.toString()).apply();
    }

    /** Ensure the default profile always exists (first launch / migration). */
    public static synchronized void ensureDefault() {
        JSONArray arr = readAll();
        for (int i = 0; i < arr.length(); i++) {
            if (DEFAULT_ID.equals(arr.optJSONObject(i).optString("id"))) return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", DEFAULT_ID);
            o.put("name", "Server 1");
            arr.put(o);
            writeAll(arr);
        } catch (Exception ignored) {
        }
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9]{1,24}");
    }

    /** Directory of a profile. default -> <files>/server, others -> <files>/servers/<id>. */
    public static File dirOf(String id) {
        if (id == null || id.isEmpty() || DEFAULT_ID.equals(id)) {
            return AppDirs.defaultServerDir();
        }
        return new File(AppDirs.serversRoot(), id);
    }

    public static List<String[]> all() {
        ensureDefault();
        List<String[]> out = new ArrayList<>();
        JSONArray arr = readAll();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(new String[]{o.optString("id"), o.optString("name")});
        }
        return out;
    }

    public static String nameOf(String id) {
        for (String[] p : all()) {
            if (p[0].equals(id)) return p[1];
        }
        return id;
    }

    /* ================= commands ================= */

    /** All profiles with live state, for the UI. */
    public static JSONObject list() {
        try {
            ensureDefault();
            JSONArray out = new JSONArray();
            for (String[] p : all()) {
                ServerManager sm = ServerManager.peek(p[0]);
                JSONObject o = new JSONObject();
                o.put("id", p[0]);
                o.put("name", p[1]);
                o.put("state", sm != null ? sm.stateName() : "idle");
                o.put("uptimeSec", sm != null ? sm.uptimeSec() : 0);
                o.put("port", readPort(dirOf(p[0])));
                out.put(o);
            }
            JSONObject r = ServerManager.JO("ok", true, "profiles", out);
            r.put("active", currentActive());
            return r;
        } catch (Throwable t) {
            return ServerManager.JO("ok", false, "error", Bridge.safeMsg(t));
        }
    }

    /**
     * Create a new profile: fresh bundle (phar, ini, yml, properties).
     * wantedPort > 0 = the port typed by the user in the create dialog
     * (validated against every other profile's port AND against the device's
     * live UDP sockets); 0 = pick the next free port automatically.
     */
    public static JSONObject create(String name, int wantedPort) {
        try {
            name = name == null ? "" : name.trim();
            if (name.isEmpty()) return ServerManager.JO("ok", false, "error", "name is empty");
            if (name.length() > 40) name = name.substring(0, 40);
            synchronized (Profiles.class) {
                String id = "s" + (System.currentTimeMillis() % 100000000L);
                int n = 2;
                while (newProfileIdExists(id)) id = "s" + (System.currentTimeMillis() % 100000000L) + (n++);
                File dir = dirOf(id);
                if (!dir.mkdirs() && !dir.isDirectory()) {
                    return ServerManager.JO("ok", false, "error", "cannot create server folder");
                }
                /* v2.8: validate the user port BEFORE writing anything, so a
                 * bad port never leaves a half-created server behind. */
                if (wantedPort > 0) {
                    String portErr = validateNewPort(wantedPort);
                    if (portErr != null) {
                        deleteRec(dir);
                        return ServerManager.JO("ok", false, "error", portErr);
                    }
                }
                try {
                    Installer.ensureNewProfile(dir, name);
                } catch (Throwable t) {
                    deleteRec(dir);
                    return ServerManager.JO("ok", false, "error", "setup failed: " + Bridge.safeMsg(t));
                }
                if (wantedPort > 0) {
                    try {
                        Installer.setServerPort(dir, wantedPort);
                    } catch (Throwable ignored) {
                    }
                }
                JSONArray arr = readAll();
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("name", name);
                arr.put(o);
                writeAll(arr);
                return ServerManager.JO("ok", true, "id", id, "name", name,
                        "port", readPort(dir));
            }
        } catch (Throwable t) {
            return ServerManager.JO("ok", false, "error", Bridge.safeMsg(t));
        }
    }

    /** Null when the port is acceptable, otherwise a user-readable reason. */
    private static String validateNewPort(int port) {
        if (port < 1024 || port > 65535) {
            return "port must be between 1024 and 65535";
        }
        for (String[] p : all()) {
            int used = readPort(dirOf(p[0]));
            if (used == port || used == port + 1) {
                return "port " + port + " is already used by server \"" + p[1] + "\" (port " + used + ")";
            }
        }
        if (!udpPortFree(port)) {
            return "port " + port + " is busy on this device right now";
        }
        return null;
    }

    private static boolean newProfileIdExists(String id) {
        for (String[] p : all()) if (p[0].equals(id)) return true;
        return false;
    }

    public static JSONObject rename(String id, String name) {
        try {
            name = name == null ? "" : name.trim();
            if (name.isEmpty()) return ServerManager.JO("ok", false, "error", "name is empty");
            if (name.length() > 40) name = name.substring(0, 40);
            synchronized (Profiles.class) {
                JSONArray arr = readAll();
                boolean found = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && o.optString("id").equals(id)) {
                        o.put("name", name);
                        found = true;
                    }
                }
                if (!found) return ServerManager.JO("ok", false, "error", "server not found");
                writeAll(arr);
            }
            return ServerManager.JO("ok", true);
        } catch (Throwable t) {
            return ServerManager.JO("ok", false, "error", Bridge.safeMsg(t));
        }
    }

    /** Delete a profile (folder + registry entry). Never the default one, never a running one. */
    public static JSONObject delete(String id) {
        try {
            if (id == null || DEFAULT_ID.equals(id)) {
                return ServerManager.JO("ok", false, "error", "the first server cannot be deleted");
            }
            if (!validId(id)) return ServerManager.JO("ok", false, "error", "bad server id");
            ServerManager sm = ServerManager.peek(id);
            if (sm != null && sm.isRunning()) {
                return ServerManager.JO("ok", false, "error", "stop the server first");
            }
            synchronized (Profiles.class) {
                JSONArray arr = readAll();
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && !o.optString("id").equals(id)) out.put(o);
                }
                writeAll(out);
            }
            deleteRec(dirOf(id));
            if (DEFAULT_ID.equals(currentActive()) || !idExists(id)) {
                // active profile may have been the deleted one; caller resets it
            }
            return ServerManager.JO("ok", true);
        } catch (Throwable t) {
            return ServerManager.JO("ok", false, "error", Bridge.safeMsg(t));
        }
    }

    private static boolean idExists(String id) {
        for (String[] p : all()) if (p[0].equals(id)) return true;
        return false;
    }

    /* ================= helpers ================= */

    /** The profile the UI currently drives (sticky, JS sends it with every call). */
    public static String currentActive() {
        return prefs().getString("activeProfile", DEFAULT_ID);
    }

    public static void setActive(String id) {
        if (validId(id)) prefs().put("activeProfile", id).apply();
    }

    /* PERF: profiles.list() runs on every 1.3s poll; re-reading and parsing
     * server.properties for every profile each time stalled the UI thread.
     * The parsed port is cached per directory and re-checked only when the
     * file's mtime changes (one stat() instead of a full read+parse). */
    private static final java.util.Map<String, long[]> PORT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(); // path -> [mtime, port]

    /** server-port from <dir>/server.properties (0 when unknown). */
    public static int readPort(File dir) {
        try {
            File f = new File(dir, "server.properties");
            if (!f.isFile()) return 0;
            String key = f.getAbsolutePath();
            long mtime = f.lastModified();
            long[] c = PORT_CACHE.get(key);
            if (c != null && c[0] == mtime) return (int) c[1];
            int port = 0;
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("server-port=")) {
                    try {
                        port = Integer.parseInt(line.substring(12).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                }
            }
            PORT_CACHE.put(key, new long[]{mtime, port});
            return port;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * Next free even UDP port, skipping every existing profile's port AND any
     * port that is currently bound on this device (a running PocketMine of
     * this app, another app, or a missing server.properties would otherwise
     * slip through and two servers would fight over the same UDP port).
     */
    public static int nextFreePort() {
        int port = 19132;
        outer:
        while (port < 19300) {
            for (String[] p : all()) {
                if (readPort(dirOf(p[0])) == port) {
                    port += 2;
                    continue outer;
                }
            }
            if (!udpPortFree(port)) {
                port += 2;
                continue;
            }
            break;
        }
        return port;
    }

    /** True when a UDP socket can actually bind to this port right now. */
    private static boolean udpPortFree(int port) {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(null)) {
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress(port));
            return true;
        } catch (Throwable t) {
            return false; // BindException -> busy; anything else -> treat as busy too
        }
    }

    private static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
