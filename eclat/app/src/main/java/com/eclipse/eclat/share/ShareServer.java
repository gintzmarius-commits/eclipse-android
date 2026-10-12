package com.eclipse.eclat.share;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The small web server of the sending phone. It serves one file, only to
 * whoever knows its secret token (given over NFC):
 *
 * <pre>
 * GET  /eclat/TOKEN          page with a preview and a download button (iPhone, browsers)
 * GET  /eclat/TOKEN/file     the file, as a download (Range supported)
 * GET  /eclat/TOKEN/view     the file, to show in the page
 * GET  /eclat/TOKEN/thumb    a small JPEG preview (images and videos)
 * POST /eclat/TOKEN/hello    an Éclat phone asks its user (body: its name)
 * POST /eclat/TOKEN/decline  ... who said no
 * </pre>
 *
 * Pure Java (the file comes through {@link Source}): tested on the PC.
 */
public final class ShareServer {

    public interface Source {
        String name();

        String mime();

        long size();

        InputStream open() throws IOException;

        /** A small JPEG preview, or null. */
        byte[] thumbnail();
    }

    public interface Listener {
        /** Someone asks (Éclat phone: its name; browser: "iPhone", "navigateur"). */
        void onAsked(String who, boolean eclatPhone);

        void onDeclined(String who);

        void onProgress(long sent, long total);

        /** The whole file went to someone. */
        void onSent(String who);
    }

    private final Source source;
    private final String token;
    private final Listener listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private volatile String lastAsker = "";

    public ShareServer(Source source, String token, Listener listener) {
        this.source = source;
        this.token = token;
        this.listener = listener;
    }

    /** Starts listening on every network of the phone; returns the port. */
    public int start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(0));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
        return server.getLocalPort();
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    public void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket s = server;
            if (s == null) return;
            try {
                final Socket c = s.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(c);
                    }
                });
            } catch (IOException e) {
                return;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------

    private void handle(Socket c) {
        try {
            c.setSoTimeout(15000);
            InputStream in = c.getInputStream();
            OutputStream out = new BufferedOutputStream(c.getOutputStream(), 64 * 1024);
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0];
            String path = first[1];
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    h.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
                }
            }
            String body = "";
            if (h.containsKey("content-length")) {
                int n = Math.min(4096, Integer.parseInt(h.get("content-length")));
                byte[] b = new byte[n];
                int got = 0;
                while (got < n) {
                    int r = in.read(b, got, n - got);
                    if (r < 0) break;
                    got += r;
                }
                body = new String(b, 0, got, StandardCharsets.UTF_8).trim();
            }
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            String base = ShareLink.PATH + token;
            String ua = h.containsKey("user-agent") ? h.get("user-agent") : "";
            if (!path.equals(base) && !path.startsWith(base + "/")) {
                send(out, 404, "text/plain; charset=utf-8", "Ce fichier n'est plus partagé.".getBytes(StandardCharsets.UTF_8), method);
                return;
            }
            String what = path.substring(base.length());
            if (what.isEmpty() || what.equals("/")) {
                lastAsker = browserName(ua);
                listener.onAsked(lastAsker, false);
                send(out, 200, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8), method);
            } else if (what.equals("/hello") && method.equals("POST")) {
                lastAsker = body.isEmpty() ? "Un téléphone" : clip(body);
                listener.onAsked(lastAsker, true);
                send(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), method);
            } else if (what.equals("/decline") && method.equals("POST")) {
                listener.onDeclined(body.isEmpty() ? lastAsker : clip(body));
                send(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), method);
            } else if (what.equals("/thumb")) {
                byte[] t = source.thumbnail();
                if (t == null) {
                    send(out, 404, "text/plain", new byte[0], method);
                } else {
                    send(out, 200, "image/jpeg", t, method);
                }
            } else if (what.equals("/file") || what.equals("/view")) {
                sendFile(out, method, h.get("range"), what.equals("/file"), h.containsKey("x-eclat") ? clip(ShareLink.dec(h.get("x-eclat"))) : browserName(ua));
            } else {
                send(out, 404, "text/plain", new byte[0], method);
            }
        } catch (IOException | RuntimeException ignored) {
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String clip(String s) {
        s = s.replaceAll("[\\r\\n<>]", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    static String browserName(String ua) {
        if (ua.contains("iPhone")) return "iPhone";
        if (ua.contains("iPad")) return "iPad";
        if (ua.contains("Macintosh")) return "Mac";
        if (ua.contains("Android")) return "Android";
        return "navigateur";
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int prev3 = 0;
        while (b.length() < 16384) {
            int ch;
            try {
                ch = in.read();
            } catch (SocketTimeoutException e) {
                return null;
            }
            if (ch < 0) return null;
            b.append((char) ch);
            prev3 = (prev3 << 8 | ch) & 0xFFFFFFFF;
            if (prev3 == 0x0D0A0D0A) {
                return b.substring(0, b.length() - 4);
            }
        }
        return null;
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default: return "Error";
        }
    }

    private static void writeHead(OutputStream out, int code, String type, long length, String extra) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        b.append("Content-Type: ").append(type).append("\r\n");
        b.append("Content-Length: ").append(length).append("\r\n");
        b.append("Cache-Control: no-store\r\n");
        b.append("Connection: close\r\n");
        if (extra != null) b.append(extra);
        b.append("\r\n");
        out.write(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String method) throws IOException {
        writeHead(out, code, type, body.length, null);
        if (!"HEAD".equals(method)) out.write(body);
        out.flush();
    }

    private void sendFile(OutputStream out, String method, String range, boolean download, String who) throws IOException {
        long size = source.size();
        long from = 0, to = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && size > 0) {
            String r = range.substring(6).split(",")[0].trim();
            int dash = r.indexOf('-');
            try {
                if (dash == 0) {
                    from = Math.max(0, size - Long.parseLong(r.substring(1)));
                } else {
                    from = Long.parseLong(r.substring(0, dash));
                    if (dash < r.length() - 1) to = Math.min(size - 1, Long.parseLong(r.substring(dash + 1)));
                }
                partial = true;
            } catch (NumberFormatException ignored) {
            }
            if (from >= size || from > to) {
                writeHead(out, 416, "text/plain", 0, "Content-Range: bytes */" + size + "\r\n");
                out.flush();
                return;
            }
        }
        long length = size < 0 ? -1 : to - from + 1;
        StringBuilder extra = new StringBuilder("Accept-Ranges: bytes\r\n");
        String fileName = source.name();
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        extra.append("Content-Disposition: ").append(download ? "attachment" : "inline")
                .append("; filename=\"").append(ascii).append("\"; filename*=UTF-8''")
                .append(ShareLink.enc(fileName)).append("\r\n");
        if (partial) extra.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(size).append("\r\n");
        if (length < 0) {
            // Unknown size: no Content-Length, the end of the connection ends the file.
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + source.mime() + "\r\nConnection: close\r\n" + extra + "\r\n").getBytes(StandardCharsets.UTF_8));
        } else {
            writeHead(out, partial ? 206 : 200, source.mime(), length, extra.toString());
        }
        if ("HEAD".equals(method)) {
            out.flush();
            return;
        }
        boolean whole = !partial || (from == 0 && to == size - 1);
        try (InputStream in = source.open()) {
            long skipped = 0;
            while (skipped < from) {
                long s = in.skip(from - skipped);
                if (s <= 0) {
                    if (in.read() < 0) break;
                    s = 1;
                }
                skipped += s;
            }
            byte[] buf = new byte[64 * 1024];
            long left = length < 0 ? Long.MAX_VALUE : length;
            long sent = 0;
            long lastReport = 0;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) break;
                out.write(buf, 0, r);
                left -= r;
                sent += r;
                if (whole && sent - lastReport > 256 * 1024) {
                    lastReport = sent;
                    listener.onProgress(sent, size);
                }
            }
            out.flush();
            if (whole && (length < 0 || left == 0)) {
                listener.onProgress(size < 0 ? sent : size, size < 0 ? sent : size);
                listener.onSent(who);
            }
        }
    }

    // ------------------------------------------------------------------
    // The page an iPhone (or any browser) sees

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String humanSize(long n) {
        if (n < 0) return "";
        if (n < 1024) return n + " o";
        double v = n / 1024.0;
        String[] u = {"Ko", "Mo", "Go", "To"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.FRANCE, v < 10 ? "%.1f %s" : "%.0f %s", v, u[i]);
    }

    String page() {
        String mime = source.mime();
        String name = esc(source.name());
        String base = ShareLink.PATH + token;
        String preview;
        String tip;
        if (mime.startsWith("image/")) {
            preview = "<img src=\"" + base + "/view\" alt=\"\">";
            tip = "Touche longuement l'image puis « Ajouter à Photos » pour la garder dans ta galerie.";
        } else if (mime.startsWith("video/")) {
            preview = "<video src=\"" + base + "/view\" controls playsinline preload=\"metadata\"></video>";
            tip = "Après le téléchargement, ouvre « Fichiers » puis partage la vidéo vers Photos pour la garder dans ta galerie.";
        } else if (mime.startsWith("audio/")) {
            preview = "<div class=\"icon\">♪</div><audio src=\"" + base + "/view\" controls></audio>";
            tip = "Le fichier arrive dans l'app « Fichiers », dossier Téléchargements.";
        } else {
            preview = "<div class=\"icon\">📄</div>";
            tip = "Le fichier arrive dans l'app « Fichiers », dossier Téléchargements.";
        }
        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
                + "<title>" + name + " — Éclat</title><style>"
                + "body{margin:0;min-height:100vh;font:16px/1.45 -apple-system,system-ui,sans-serif;color:#1b1b1f;"
                + "background:linear-gradient(160deg,#f7ecf3,#eae9fb 45%,#e3f1fb);display:flex;align-items:center;justify-content:center}"
                + ".card{margin:20px;max-width:520px;width:calc(100% - 40px);padding:22px;border-radius:30px;text-align:center;"
                + "background:rgba(255,255,255,.55);border:1px solid rgba(255,255,255,.8);-webkit-backdrop-filter:blur(24px) saturate(175%);"
                + "backdrop-filter:blur(24px) saturate(175%);box-shadow:inset 0 1px 0 #fff,0 14px 40px rgba(54,44,110,.14)}"
                + "img,video{max-width:100%;max-height:55vh;border-radius:20px;display:block;margin:0 auto 14px;box-shadow:0 8px 24px rgba(0,0,0,.15)}"
                + "audio{width:100%;margin:10px 0}.icon{font-size:64px;margin:6px 0 8px}h1{font-size:20px;margin:6px 0 2px;word-break:break-word}"
                + ".muted{color:#5f6470;font-size:14px}.btn{display:block;margin:18px 0 10px;padding:15px;border-radius:999px;font-weight:650;"
                + "color:#2a1800;text-decoration:none;background:linear-gradient(180deg,#ffc24d,#f6a51a);box-shadow:inset 0 1px 0 rgba(255,255,255,.75),0 6px 20px rgba(246,165,26,.35)}"
                + ".from{font-size:13px;color:#a85f00;font-weight:600;letter-spacing:.02em;text-transform:uppercase}"
                + "</style></head><body><div class=\"card\"><div class=\"from\">Éclat · envoyé par NFC</div>"
                + preview + "<h1>" + name + "</h1><div class=\"muted\">" + humanSize(source.size()) + "</div>"
                + "<a class=\"btn\" href=\"" + base + "/file\" download=\"" + name + "\">Télécharger</a>"
                + "<div class=\"muted\">" + tip + "</div></div></body></html>";
    }
}
