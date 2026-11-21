package com.pmpanel.app;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * JSON-file replacement for Android's SharedPreferences ("pmpanel").
 * Stored as %APPDATA%\PMPanel\pmpanel.json - same keys, same semantics.
 */
public final class Prefs {

    private static volatile Prefs instance;

    private final File file;
    private final JSONObject map;
    private final Object lock = new Object();

    private Prefs(File file) {
        this.file = file;
        JSONObject m = null;
        try {
            if (file.isFile()) {
                String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (!s.isEmpty()) m = new JSONObject(s);
            }
        } catch (Throwable ignored) {
        }
        this.map = m == null ? new JSONObject() : m;
    }

    public static Prefs get() {
        if (instance == null) {
            synchronized (Prefs.class) {
                if (instance == null) instance = new Prefs(new File(AppDirs.filesDir(), "pmpanel.json"));
            }
        }
        return instance;
    }

    public boolean getBoolean(String key, boolean def) {
        synchronized (lock) {
            return map.optBoolean(key, def);
        }
    }

    public int getInt(String key, int def) {
        synchronized (lock) {
            return map.optInt(key, def);
        }
    }

    public String getString(String key, String def) {
        synchronized (lock) {
            return map.optString(key, def);
        }
    }

    public boolean contains(String key) {
        synchronized (lock) {
            return map.has(key);
        }
    }

    public Prefs put(String key, boolean v) {
        synchronized (lock) {
            map.put(key, v);
            return this;
        }
    }

    public Prefs put(String key, int v) {
        synchronized (lock) {
            map.put(key, v);
            return this;
        }
    }

    public Prefs put(String key, String v) {
        synchronized (lock) {
            map.put(key, v);
            return this;
        }
    }

    /** Equivalent of Editor.apply()/commit() - writes atomically. */
    public void apply() {
        synchronized (lock) {
            String out = map.toString();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out2 = new FileOutputStream(tmp)) {
                out2.write(out.getBytes(StandardCharsets.UTF_8));
                out2.flush();
                out2.getFD().sync();
            } catch (IOException ignored) {
                return;
            }
            if (file.exists()) //noinspection ResultOfMethodCallIgnored
                file.delete();
            try {
                if (!tmp.renameTo(file)) tmp.delete();
            } catch (Throwable ignored) {
            }
        }
    }
}
