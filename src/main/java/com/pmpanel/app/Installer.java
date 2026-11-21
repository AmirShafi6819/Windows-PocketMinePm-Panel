package com.pmpanel.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * First-run setup, php.ini management, and GitHub update checks.
 *
 * Ported from Android: app-private storage became AppDirs, SharedPreferences
 * became Prefs (pmpanel.json), and the bundled ARM PHP was replaced by the
 * official pmmp Windows x64 PHP build which is extracted from the app jar on
 * first run into %APPDATA%\PMPanel\bin\php.
 */
public class Installer {

    /** Resource paths inside the application jar. */
    static final String RES_PHP_ZIP = "/native/php-windows.zip";
    static final String RES_PMBOT = "/native/pmbot.exe";
    /** Marks which native payload version is already extracted on disk. */
    static final String NATIVE_STAMP = "pmpanel-native-1";

    public static File serverDir() {
        return serverDir(Profiles.DEFAULT_ID);
    }

    /** Per-profile server directory (v2.4 multi-server). */
    public static File serverDir(String profileId) {
        return Profiles.dirOf(profileId);
    }

    public static File logsDir() {
        return new File(serverDir(), "logs");
    }

    public static File cacheTmp() {
        return AppDirs.cacheTmp();
    }

    /** Staging area parent for the zip importer (survives as a sibling of pmtmp). */
    public static File cacheTmpStageBase() {
        return AppDirs.cacheTmpStageBase();
    }

    public static File backupsDir() {
        return new File(serverDir(), "backups");
    }

    /** Backups folder of a specific profile directory. */
    public static File backupsDir(File dir) {
        return new File(dir, "backups");
    }

    /* ---------------- native payload (PHP + bot engine) ---------------- */

    private static File phpDir() {
        return new File(AppDirs.binDir(), "php");
    }

    public static File phpBinary() {
        return new File(phpDir(), "php.exe");
    }

    public static File botBinary() {
        return new File(AppDirs.binDir(), "pmbot.exe");
    }

    /** Extract the bundled PHP runtime / bot engine when missing or outdated. */
    public static synchronized void ensureBinaries() throws IOException {
        File bin = AppDirs.binDir();
        //noinspection ResultOfMethodCallIgnored
        bin.mkdirs();
        File stamp = new File(bin, "native-version.txt");
        if (NATIVE_STAMP.equals(readText(stamp)) && phpBinary().isFile()) return;

        // PHP (official pmmp Windows build; zip root is bin/php/... -> strip "bin/")
        String zipRes = RES_PHP_ZIP;
        try (InputStream zin = Installer.class.getResourceAsStream(zipRes)) {
            if (zin == null) throw new IOException("bundled PHP runtime is missing from this build");
        }
        File zipFile = new File(bin, "php-windows.zip");
        try (InputStream in = Installer.class.getResourceAsStream(zipRes);
             OutputStream out = new FileOutputStream(zipFile)) {
            copy(in, out);
        }
        unzipStripTop(zipFile, bin);
        //noinspection ResultOfMethodCallIgnored
        zipFile.delete();

        // bot engine (Windows build of pmbot)
        try (InputStream in = Installer.class.getResourceAsStream(RES_PMBOT)) {
            if (in != null) {
                File bot = botBinary();
                try (OutputStream out = new FileOutputStream(bot)) {
                    copy(in, out);
                }
            }
            // missing bot engine is not fatal: the UI reports it like on Android
        }

        writeTextFile(stamp, NATIVE_STAMP);
    }

    /** Unzip a file whose entries live under a single top folder ("bin/php/x" -> "php/x"). */
    private static void unzipStripTop(File zip, File destDir) throws IOException {
        String top = null;
        try (ZipFile zf = new ZipFile(zip)) {
            for (java.util.Enumeration<? extends ZipEntry> it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                String n = e.getName();
                if (n.contains("/")) {
                    String t = n.substring(0, n.indexOf('/'));
                    if (top == null) top = t;
                    else if (!top.equals(t)) {
                        top = null;
                        break;
                    }
                } else {
                    top = null;
                    break;
                }
            }
        }
        final String strip = top == null ? "" : top + "/";
        try (ZipFile zf = new ZipFile(zip)) {
            for (java.util.Enumeration<? extends ZipEntry> it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                String n = e.getName();
                if (n.startsWith(strip)) n = n.substring(strip.length());
                if (n.isEmpty()) continue;
                File out = new File(destDir, n);
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (InputStream in = zf.getInputStream(e); OutputStream out2 = new FileOutputStream(out)) {
                    copy(in, out2);
                }
            }
        }
    }

    private static String readText(File f) {
        try {
            if (!f.isFile()) return "";
            return new String(Files_readAllBytes(f), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] Files_readAllBytes(File f) throws IOException {
        return java.nio.file.Files.readAllBytes(f.toPath());
    }

    /* ---------------- first-run setup ---------------- */

    public static synchronized void ensure() throws IOException {
        ensure(serverDir());
    }

    /** First-run setup for any server directory (default profile or new ones). */
    public static synchronized void ensure(File dir) throws IOException {
        File phar = new File(dir, "PocketMine-MP.phar");
        // Give a CLEAR error early when storage is nearly full instead of failing
        // mid-copy with a confusing message. First setup needs room for the
        // bundled phar; later starts only need room for logs and world saves.
        long minFree = phar.isFile() ? 30L * 1024 * 1024 : 250L * 1024 * 1024;
        if (dir.getUsableSpace() < minFree) {
            throw new IOException("not enough free storage (need about "
                    + (minFree / (1024 * 1024)) + " MB)");
        }
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "plugins").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "logs").mkdirs();
        cacheTmp().mkdirs();
        backupsDir(dir).mkdirs();

        // shared runtime (extracted once, shared by every profile)
        ensureBinaries();

        if (!phar.isFile()) copyAsset("PocketMine-MP.phar", phar);

        File pmYml = new File(dir, "pocketmine.yml");
        if (!pmYml.isFile()) copyAsset("pocketmine.yml", pmYml);

        File ini = new File(dir, "php.ini");
        if (!ini.isFile()) writePhpIni(dir, getMemoryLimitMb());
    }

    /**
     * Set up a freshly created profile directory: bundle + php.ini + a complete
     * server.properties with a UDP port that no other profile uses (two servers
     * on the same port would make the second one crash at boot).
     */
    public static synchronized void ensureNewProfile(File dir, String name)
            throws IOException {
        ensure(dir);
        writeFreshProperties(dir, name);
    }

    /**
     * Guarantee the server directory HAS a usable server.properties with a
     * unique port. The legacy default profile could be started without one
     * (PocketMine would then fall back to port 19132 and collide with any
     * other server created afterwards) - this closes that hole.
     */
    public static synchronized void ensureServerProperties(File dir) throws IOException {
        File f = new File(dir, "server.properties");
        if (f.isFile()) {
            int port = Profiles.readPort(dir);
            if (port > 0 && port < 65536) return; // valid user file - keep it untouched
        }
        writeFreshProperties(dir, "PM Panel Server");
    }

    private static void writeFreshProperties(File dir, String name) throws IOException {
        int port = Profiles.nextFreePort();
        StringBuilder sb = new StringBuilder();
        sb.append("#Generated by PM Panel\n");
        sb.append("motd=").append(name.replace('\n', ' ')).append('\n');
        sb.append("server-port=").append(port).append('\n');
        sb.append("server-portv6=").append(port + 1).append('\n');
        sb.append("enable-ipv6=off\n");
        sb.append("max-players=20\n");
        sb.append("white-list=off\n");
        sb.append("gamemode=SURVIVAL\n");
        sb.append("force-gamemode=off\n");
        sb.append("hardcore=off\n");
        sb.append("pvp=on\n");
        sb.append("difficulty=2\n");
        sb.append("generator-settings=\n");
        sb.append("level-name=world\n");
        sb.append("level-seed=\n");
        sb.append("level-type=DEFAULT\n");
        sb.append("enable-query=on\n");
        sb.append("auto-save=on\n");
        sb.append("view-distance=10\n");
        sb.append("xbox-auth=on\n");
        sb.append("language=eng\n");
        writeTextFile(new File(dir, "server.properties"), sb.toString());
    }

    /** Patch just server-port / server-portv6 inside an existing server.properties. */
    public static synchronized void setServerPort(File dir, int port) throws IOException {
        File f = new File(dir, "server.properties");
        java.util.List<String> lines = f.isFile()
                ? java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)
                : new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean p1 = false, p2 = false;
        for (String line : lines) {
            if (line.startsWith("server-port=")) {
                sb.append("server-port=").append(port).append('\n');
                p1 = true;
            } else if (line.startsWith("server-portv6=")) {
                sb.append("server-portv6=").append(port + 1).append('\n');
                p2 = true;
            } else {
                sb.append(line).append('\n');
            }
        }
        if (!p1) sb.append("server-port=").append(port).append('\n');
        if (!p2) sb.append("server-portv6=").append(port + 1).append('\n');
        writeTextFile(f, sb.toString());
    }

    private static void writeTextFile(File f, String content) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
        if (!tmp.renameTo(f)) throw new IOException("cannot write " + f.getName());
    }

    /** Copy a bundled (extracted) asset file. */
    public static void copyAsset(String asset, File dest) throws IOException {
        File src = new File(AppDirs.assetsDir(), asset);
        if (!src.isFile()) throw new IOException("asset missing: " + asset);
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
        }
        if (dest.exists()) //noinspection ResultOfMethodCallIgnored
            dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("cannot install " + asset);
    }

    private static String readAllText(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** Read a bundled asset as UTF-8 text. */
    public static String readAssetText(String asset) throws IOException {
        File src = new File(AppDirs.assetsDir(), asset);
        try (InputStream in = new FileInputStream(src)) {
            return readAllText(in);
        }
    }

    /* ---------------- php.ini management (Windows) ---------------- */

    /**
     * The PMPanel php.ini settings (same as the Android asset php.ini). On
     * Windows they are MERGED INTO the official pmmp php.ini so the extension
     * directives (extension_dir, extension=..., zend extensions) required by
     * the non-static Windows PHP build are preserved; memory_limit is patched
     * on top of that.
     */
    private static final String[] PANEL_INI_KEYS = {
            "date.timezone", "short_open_tag", "asp_tags", "phar.require_hash",
            "igbinary.compact_strings", "zend.assertions", "error_reporting",
            "display_errors", "display_startup_errors", "recursionguard.enabled",
    };

    /** PMPanel settings block with the given memory limit. */
    private static String panelIniBlock(int memMb) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n; --- PM Panel settings (Windows desktop build) ---\n");
        sb.append("memory_limit=").append(memMb).append("M\n");
        sb.append("date.timezone=UTC\n");
        sb.append("short_open_tag=0\n");
        sb.append("phar.require_hash=1\n");
        sb.append("igbinary.compact_strings=0\n");
        sb.append("zend.assertions=-1\n");
        sb.append("error_reporting=-1\n");
        sb.append("display_errors=1\n");
        sb.append("display_startup_errors=1\n");
        sb.append("recursionguard.enabled=0\n");
        return sb.toString();
    }

    /**
     * Build the effective php.ini template: the pmmp php.ini (extensions) with
     * an absolute extension_dir, PMPanel's settings replacing any conflicting
     * keys, and the requested memory limit.
     */
    private static String desktopIniTemplate(int memMb) {
        String base = "";
        File pmIni = new File(phpDir(), "php.ini");
        try {
            if (pmIni.isFile()) {
                base = new String(Files_readAllBytes(pmIni), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        if (base.isEmpty()) {
            try {
                base = readAssetText("php.ini");
            } catch (Throwable ignored) {
            }
        }

        // make extension_dir absolute so it works from any working directory
        String extDir = new File(phpDir(), "ext").getAbsolutePath();
        base = base.replaceAll("(?m)^\\s*extension_dir\\s*=.*$", "extension_dir = \"" + extDir.replace("\\", "\\\\") + "\"");

        // strip any existing panel keys so ours win
        for (String key : PANEL_INI_KEYS) {
            base = base.replaceAll("(?mi)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=.*$", "");
        }
        base = base.replaceAll("(?mi)^\\s*memory_limit\\s*=.*$", "");
        base = base + panelIniBlock(memMb);
        return base;
    }

    /* ---------------- memory limit prefs ---------------- */

    public static Prefs prefs() {
        return Prefs.get();
    }

    public static int getMemoryLimitMb() {
        // Default tuned to the device: small-RAM machines get a smaller PHP
        // limit so PocketMine does not fight the UI for memory.
        long totalRam = 0;
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            totalRam = os.getTotalPhysicalMemorySize();
        } catch (Throwable ignored) {
        }
        int def = (totalRam > 0 && totalRam < 3500L * 1024 * 1024) ? 512 : 1024;
        return prefs().getInt("memoryLimitMb", def);
    }

    /* ---------------- per-server memory limit (v2.5) ----------------
     * Every profile owns its memory limit: stored under "memLimit_<id>",
     * applied by patching ONLY the memory_limit line of that server's php.ini
     * (the rest of the file, e.g. user-edited extension settings, is kept). */

    private static String memKey(String profileId) {
        return "memLimit_" + (profileId == null ? Profiles.DEFAULT_ID : profileId);
    }

    public static boolean hasMemoryPref(String profileId) {
        return prefs().contains(memKey(profileId));
    }

    /** Effective limit of one profile: its own setting, else its php.ini, else the app default. */
    public static int getMemoryLimitMb(String profileId) {
        String key = memKey(profileId);
        if (prefs().contains(key)) return prefs().getInt(key, 1024);
        int fromIni = readPhpIniLimit(Profiles.dirOf(profileId));
        return fromIni > 0 ? fromIni : getMemoryLimitMb();
    }

    /** Store a per-profile limit and write it into that profile's php.ini. */
    public static synchronized void setMemoryLimit(String profileId, int mb) {
        prefs().put(memKey(profileId), mb).apply();
        try {
            patchMemoryLimit(Profiles.dirOf(profileId), mb);
        } catch (Throwable ignored) {
        }
    }

    /** On start: follow the stored per-profile setting (never touches php.ini when the user never changed it). */
    public static synchronized void applyProfileMemory(String profileId, File dir) {
        if (!prefs().contains(memKey(profileId))) return;
        try {
            patchMemoryLimit(dir, getMemoryLimitMb(profileId));
        } catch (Throwable ignored) {
        }
    }

    /** memory_limit value inside dir/php.ini (0 when missing/unparseable). */
    public static int readPhpIniLimit(File dir) {
        try {
            File f = new File(dir, "php.ini");
            if (!f.isFile()) return 0;
            for (String line : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("memory_limit=")) {
                    String v = t.substring(13).trim().toLowerCase(Locale.US);
                    int mult = 1;
                    if (v.endsWith("m")) { mult = 1; v = v.substring(0, v.length() - 1); }
                    else if (v.endsWith("g")) { mult = 1024; v = v.substring(0, v.length() - 1); }
                    v = v.trim();
                    int n = Integer.parseInt(v);
                    if (n > 0) return n * mult;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** Replace ONLY the memory_limit line inside an existing php.ini (template when the file is missing). */
    public static synchronized void patchMemoryLimit(File dir, int mb) throws IOException {
        File ini = new File(dir, "php.ini");
        String content;
        if (ini.isFile()) {
            StringBuilder sb = new StringBuilder();
            try (FileInputStream in = new FileInputStream(ini);
                 InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8);
                 BufferedReader br = new BufferedReader(r)) {
                char[] buf = new char[8192];
                int n;
                while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            }
            content = sb.toString();
        } else {
            content = desktopIniTemplate(mb);
        }
        if (content.contains("memory_limit=")) {
            content = content.replaceAll("(?mi)^\\s*memory_limit\\s*=.*$", "memory_limit=" + mb + "M");
        } else {
            content = "memory_limit=" + mb + "M\n" + content;
        }
        writeTextFile(ini, content);
    }

    /** (Re)write php.ini from the Windows template with the given memory limit (fresh servers only). */
    public static synchronized void writePhpIni(File dir, int memMb) throws IOException {
        writeTextFile(new File(dir, "php.ini"), desktopIniTemplate(memMb));
    }

    public static JSONObject bundleInfo() {
        try {
            return new JSONObject(readAssetText("bundle-info.json"));
        } catch (Exception e) {
            return ServerManager.JO("error", Bridge.safeMsg(e));
        }
    }

    /** Swap in a new server phar (keeps one .bak of the previous one) - default profile. */
    public static void applyPhar(File newPhar) throws IOException {
        applyPhar(serverDir(), newPhar);
    }

    /** Swap in a new server phar inside the given server directory. */
    public static void applyPhar(File dir, File newPhar) throws IOException {
        File phar = new File(dir, "PocketMine-MP.phar");
        File bak = new File(dir, "PocketMine-MP.phar.bak");
        //noinspection ResultOfMethodCallIgnored
        bak.delete();
        if (phar.exists() && !phar.renameTo(bak)) throw new IOException("cannot backup current phar");
        File target = new File(dir, "PocketMine-MP.phar");
        if (!newPhar.renameTo(target)) throw new IOException("cannot move new phar");
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    /** Progress in raw bytes (used by the generic downloader). */
    public interface ByteProgressListener {
        void onProgress(long done, long total);
    }

    /** Download a file with percent progress callbacks. */
    public static void downloadFile(String urlStr, File dest, ProgressListener listener) throws IOException {
        downloadFile(urlStr, dest, (done, total) -> {
            if (listener != null && total > 0) listener.onProgress((int) (done * 100 / total));
        });
        if (listener != null) listener.onProgress(100);
    }

    /** Download a file with byte-level progress callbacks (total<0 when unknown). */
    public static void downloadFile(String urlStr, File dest, ByteProgressListener listener) throws IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        //noinspection ResultOfMethodCallIgnored
        tmp.getParentFile().mkdirs();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "PMPanel-Windows");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                long done = 0;
                long last = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    done += r;
                    if (listener != null && (done - last >= 262144 || done == total)) {
                        listener.onProgress(done, total);
                        last = done;
                    }
                }
                out.flush();
            }
        } finally {
            conn.disconnect();
        }
        if (dest.exists()) //noinspection ResultOfMethodCallIgnored
            dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("cannot finalize download");
        if (listener != null) listener.onProgress(dest.length(), dest.length());
    }

    private static String ghGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "PMPanel-Windows");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                return readAllText(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    /** Check for updates: official pmmp release, community NG-phar builds, NG source commit. */
    public static JSONObject checkUpdates() {
        JSONObject res = ServerManager.JO("ok", true);

        // Official pmmp latest release
        try {
            JSONObject rel = new JSONObject(ghGet("https://api.github.com/repos/pmmp/PocketMine-MP/releases/latest"));
            JSONObject o = new JSONObject();
            o.put("tag", rel.optString("tag_name"));
            o.put("date", rel.optString("published_at"));
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a != null && "PocketMine-MP.phar".equals(a.optString("name"))) {
                        o.put("url", a.optString("browser_download_url"));
                        o.put("size", a.optLong("size"));
                    }
                }
            }
            res.put("pmmp", o);
        } catch (Exception e) {
            put(res, "pmmp", ServerManager.JO("error", Bridge.safeMsg(e)));
        }

        // Community NG phar builds (TheWindows)
        try {
            JSONArray rels = new JSONArray(ghGet("https://api.github.com/repos/TheWindows/PocketMine-NG-Builds-phar/releases?per_page=15"));
            JSONObject found = null;
            for (int i = 0; i < rels.length() && found == null; i++) {
                JSONObject rel = rels.optJSONObject(i);
                if (rel == null) continue;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.optJSONObject(j);
                    if (a != null && a.optString("name").toLowerCase().endsWith(".phar")) {
                        found = new JSONObject();
                        found.put("tag", rel.optString("tag_name"));
                        found.put("date", rel.optString("published_at"));
                        found.put("url", a.optString("browser_download_url"));
                        found.put("asset", a.optString("name"));
                        found.put("size", a.optLong("size"));
                        break;
                    }
                }
            }
            if (found == null) found = new JSONObject().put("error", "no phar assets found");
            res.put("ngBuilds", found);
        } catch (Exception e) {
            put(res, "ngBuilds", ServerManager.JO("error", Bridge.safeMsg(e)));
        }

        // NG source latest stable commit
        try {
            JSONObject c = new JSONObject(ghGet("https://api.github.com/repos/NetherGamesMC/PocketMine-MP/commits/stable"));
            JSONObject o = new JSONObject();
            o.put("sha", c.optString("sha"));
            JSONObject commit = c.optJSONObject("commit");
            if (commit != null) o.put("date", commit.optJSONObject("committer") != null
                    ? commit.getJSONObject("committer").optString("date") : "");
            o.put("bundledSha", bundleInfo().optString("ngCommit", ""));
            o.put("message", commit != null ? commit.optString("message", "") : "");
            res.put("ngSource", o);
        } catch (Exception e) {
            put(res, "ngSource", ServerManager.JO("error", Bridge.safeMsg(e)));
        }
        return res;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }
}
