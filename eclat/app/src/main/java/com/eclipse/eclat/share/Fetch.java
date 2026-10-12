package com.eclipse.eclat.share;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The receiving side: talks to the sender's {@link ShareServer}.
 * Pure Java (Android passes an {@link Opener} bound to the right network).
 */
public final class Fetch {

    public interface Opener {
        HttpURLConnection open(URL url) throws IOException;
    }

    public interface Progress {
        /** Return false to stop. */
        boolean onProgress(long got, long total);
    }

    public static final Opener DEFAULT = new Opener() {
        @Override
        public HttpURLConnection open(URL url) throws IOException {
            return (HttpURLConnection) url.openConnection();
        }
    };

    /** POSTs a short text to the sender; true if it answered. */
    public static boolean post(Opener opener, String url, String body, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = opener.open(new URL(url));
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            return c.getResponseCode() == 200;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Says hello on each address of the sender; returns the first one that
     * answers, or null when none does (not on the same network).
     */
    public static String findHost(Opener opener, ShareLink link, String myName, int timeoutMs) {
        for (String h : link.hosts()) {
            if (post(opener, link.base(h) + "/hello", myName, timeoutMs)) return h;
        }
        return null;
    }

    public static byte[] get(Opener opener, String url, int timeoutMs, int max) {
        HttpURLConnection c = null;
        try {
            c = opener.open(new URL(url));
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) {
                    b.write(buf, 0, r);
                    if (b.size() > max) return null;
                }
                return b.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads the file into {@code out}; returns the number of bytes. */
    public static long download(Opener opener, ShareLink link, String host, String myName,
                                OutputStream out, Progress progress) throws IOException {
        HttpURLConnection c = opener.open(new URL(link.base(host) + "/file"));
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("X-Eclat", ShareLink.enc(myName)); // headers are ASCII
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = c.getContentLength();
            if (total < 0) total = link.size;
            long got = 0;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    got += r;
                    if (!progress.onProgress(got, total)) throw new IOException("annulé");
                }
            }
            if (total >= 0 && got != total) throw new IOException("transfert interrompu (" + got + "/" + total + ")");
            return got;
        } finally {
            c.disconnect();
        }
    }
}
