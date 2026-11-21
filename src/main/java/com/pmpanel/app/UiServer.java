package com.pmpanel.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Tiny loopback HTTP server that serves the panel UI (www/) to the WebView.
 *
 * Why not file:// ? The desktop shell must attach the Java bridge object
 * (window.PMPanel) to the document; the page's boot() runs on
 * DOMContentLoaded, i.e. before the load worker reports SUCCEEDED. Serving
 * through this server lets us inject ONE small head script (runtime only -
 * the bundled UI files themselves stay byte-identical to the Android app)
 * that defers boot() until the bridge is attached, so the very first screen
 * is already driven by the real backend and the dev-mock never flashes.
 * The same injection adds a TTF fallback for the Vazirmatn web fonts so the
 * typography is identical on every Windows installation.
 *
 * The server binds to 127.0.0.1 only. A fixed port (with fallbacks) keeps
 * the localStorage origin stable across launches so the chosen language
 * persists.
 */
public final class UiServer {

    private static final int BASE_PORT = 54190;
    private static final int PORT_TRIES = 16;

    private HttpServer server;
    private int port = -1;

    public void start() throws IOException {
        IOException last = null;
        for (int i = 0; i < PORT_TRIES; i++) {
            int candidate = BASE_PORT + i;
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate), 32);
                port = candidate;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32);
            port = server.getAddress().getPort();
        }
        server.createContext("/", this::handle);
        /* PERF: the WebView loads the panel in parallel requests (css, 10 js
         * files, fonts, icons). Two worker threads serialized that burst and
         * stretched the first paint; 8 lets the whole UI arrive in one wave. */
        server.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "pm-ui-server");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/index.html";
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
        File root = AppDirs.wwwDir();
        File f = new File(root, path.replace('/', File.separatorChar));
        // path traversal guard
        if (!f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)
                && !f.getCanonicalPath().equals(root.getCanonicalPath())) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), true);
            return;
        }
        if (!f.isFile()) {
            /* Recovery net: if resource extraction failed on the user's machine
               (blocked folder, antivirus, OneDrive...), fall back to serving
               straight from the app jar so the panel still boots. */
            byte[] cp = classpathResource("/www" + path);
            if (cp != null) {
                if (path.endsWith("/index.html") || "/index.html".equals(path)) {
                    send(ex, 200, "text/html; charset=utf-8", injectShim(cp), true);
                } else {
                    send(ex, 200, contentType(path), cp, false);
                }
                DesktopApp.appendCrashLog("ui: served from jar fallback: " + path);
                return;
            }
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), true);
            return;
        }
        if (path.endsWith("/index.html")) {
            byte[] html = readFile(f);
            send(ex, 200, "text/html; charset=utf-8", injectShim(html), true);
            return;
        }
        send(ex, 200, contentType(path), readFile(f), false);
    }

    private static byte[] classpathResource(String resPath) {
        try (InputStream in = UiServer.class.getResourceAsStream(resPath)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The full shim-injected index.html, taken from disk or (if extraction
     * failed) straight from the jar - used by the file:// fallback load.
     */
    static byte[] shimmedIndexHtml() {
        byte[] html = null;
        File f = new File(AppDirs.wwwDir(), "index.html");
        if (f.isFile()) {
            try {
                html = readFile(f);
            } catch (IOException ignored) {
            }
        }
        if (html == null) html = classpathResource("/www/index.html");
        if (html == null) return null;
        return injectShim(html);
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }

    /** The desktop compatibility shim (also injected for file:// loads as a safety net). */
    static final String SHIM_JS =
        "<script>\n" +
        "/* PMPanel Windows desktop shim - injected at runtime by the desktop shell.\n" +
        "   The bundled UI files stay byte-identical to the Android app. */\n" +
        "(function () {\n" +
        "  if (window.__pmDesktopShim) return;\n" +
        "  window.__pmDesktopShim = true;\n" +
        "  /* Vazirmatn TTF fallback for engines without WOFF2 support (same font files) */\n" +
        "  try {\n" +
        "    var st = document.createElement('style');\n" +
        "    st.textContent = '@font-face{font-family:Vazirmatn;src:url(\"fonts/Vazirmatn-Regular.ttf\") format(\"truetype\");font-weight:400;font-display:swap}" +
        "@font-face{font-family:Vazirmatn;src:url(\"fonts/Vazirmatn-Medium.ttf\") format(\"truetype\");font-weight:500;font-display:swap}" +
        "@font-face{font-family:Vazirmatn;src:url(\"fonts/Vazirmatn-Bold.ttf\") format(\"truetype\");font-weight:700;font-display:swap}';\n" +
        "    document.head.appendChild(st);\n" +
        "  } catch (e) {}\n" +
        "  /* Defer app boot until the native bridge exists so the first screen is real.\n" +
        "     The queued DOMContentLoaded listeners are called DIRECTLY once the bridge\n" +
        "     is attached (re-registering them would never fire: the event has already\n" +
        "     happened by the time the load worker reports SUCCEEDED). */\n" +
        "  try {\n" +
        "    var proto = Document.prototype;\n" +
        "    var real = proto.addEventListener;\n" +
        "    proto.__pmReal = real;\n" +
        "    proto.addEventListener = function (type, fn, opts) {\n" +
        "      if (type === 'DOMContentLoaded' && !window.__pmBridgeReady && typeof fn === 'function') {\n" +
        "        (document.__pmDcl = document.__pmDcl || []).push(fn);\n" +
        "        return;\n" +
        "      }\n" +
        "      return real.call(this, type, fn, opts);\n" +
        "    };\n" +
        "  } catch (e) {}\n" +
        "  /* capture any boot error so the watchdog card can show something useful */\n" +
        "  try {\n" +
        "    window.addEventListener('error', function (ev) {\n" +
        "      if (window.__pmBootError) return;\n" +
        "      if (ev && ev.error && ev.error.message) window.__pmBootError = String(ev.error.message);\n" +
        "      else if (ev && ev.message) window.__pmBootError = String(ev.message);\n" +
        "    });\n" +
        "  } catch (e) {}\n" +
        "  /* failsafe: if the bridge never shows up within 6s, boot anyway (dev mock) */\n" +
        "  setTimeout(function () {\n" +
        "    if (window.__pmBridgeReady) return;\n" +
        "    fireQueued();\n" +
        "  }, 6000);\n" +
        "  window.__pmFireBoot = fireQueued;\n" +
        "  function fireQueued() {\n" +
        "    if (window.__pmBridgeReady) return;\n" +
        "    window.__pmBridgeReady = true;\n" +
        "    try { Document.prototype.addEventListener = Document.prototype.__pmReal; } catch (e) {}\n" +
        "    var q = document.__pmDcl || [];\n" +
        "    document.__pmDcl = [];\n" +
        "    var ev = { type: 'DOMContentLoaded', target: document };\n" +
        "    for (var i = 0; i < q.length; i++) {\n" +
        "      try { q[i].call(document, ev); }\n" +
        "      catch (e) { if (!window.__pmBootError) window.__pmBootError = String(e && e.message || e); }\n" +
        "    }\n" +
        "  }\n" +
        "  /* last-resort watchdog: if the view is still empty after 10s, paint a\n" +
        "     readable self-diagnosis card instead of a silent empty window */\n" +
        "  setTimeout(function () {\n" +
        "    try {\n" +
        "      var v = document.querySelector('#view');\n" +
        "      if (!v || v.children.length > 0) return;\n" +
        "      var err = window.__pmBootError || 'unknown';\n" +
        "      var box = document.createElement('div');\n" +
        "      box.setAttribute('dir', 'rtl');\n" +
        "      box.style.cssText = 'margin:60px auto;max-width:620px;padding:26px;border-radius:20px;' +\n" +
        "        'background:#101218;border:1px solid rgba(255,255,255,.1);color:#fafafa;' +\n" +
        "        'font-family:Vazirmatn,sans-serif;font-size:14px;line-height:2';\n" +
        "      box.innerHTML = '<div style=\"font-size:17px;font-weight:700;margin-bottom:10px\">' +\n" +
        "        'رابط کاربری بارگیری نشد · UI failed to boot</div>' +\n" +
        "        '<div style=\"color:#a3a8b8;margin-bottom:12px\">برنامه را ببندید و دوباره باز کنید. اگر تکرار شد، این پیام را همراه گزارش خطا بفرستید:<br>' +\n        'Close and reopen the app. If it repeats, please report this error:</div>' +\n" +
        "        '<pre style=\"white-space:pre-wrap;background:#0b0d14;border-radius:10px;padding:12px;' +\n" +
        "        'color:#f5a524;direction:ltr;text-align:left;font-size:12px\">' +\n" +
        "        String(err).replace(/[<&>]/g, function (c) { return { '<': '&lt;', '&': '&amp;', '>': '&gt;' }[c]; }) +\n" +
        "        '</pre>';\n" +
        "      v.appendChild(box);\n" +
        "    } catch (e) {}\n" +
        "  }, 10000);\n" +
        "})();\n" +
        "</script>\n";

    private static byte[] injectShim(byte[] html) {
        String s = new String(html, StandardCharsets.UTF_8);
        if (s.contains("__pmDesktopShim")) return html;
        int idx = s.indexOf("</head>");
        if (idx < 0) idx = s.toLowerCase().indexOf("</head>");
        if (idx < 0) return html;
        s = s.substring(0, idx) + SHIM_JS + s.substring(idx);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body,
                             boolean noCache) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        /* PERF: static panel assets (css/js/fonts/icons) are version-stamped
         * by the app version stamp on disk, so the engine can keep them for a
         * day instead of re-fetching everything on every reload. The shim-
         * injected index.html stays no-cache so boot always gets fresh. */
        ex.getResponseHeaders().set("Cache-Control",
                noCache ? "no-cache" : "max-age=86400");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
