package com.eclipse.eclat;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.LruCache;
import android.webkit.WebResourceResponse;

import com.eclipse.eclat.share.UriSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * What the pages of Éclat load from https://eclat.app/ (never the network):
 * <pre>
 * /ui/...            the interface (assets/ui)
 * /thumb?u=&m=&s=    a preview of a photo, video or song
 * /img?u=&m=         a whole photo (HEIC and others as JPEG)
 * </pre>
 */
public final class WebFiles {
    public static final String ORIGIN = "https://eclat.app";

    private static final LruCache<String, byte[]> thumbs = new LruCache<String, byte[]>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    private WebFiles() {
    }

    public static WebResourceResponse serve(Context c, Uri url) {
        if (!"eclat.app".equals(url.getHost())) return null;
        String path = url.getPath();
        if (path == null) return null;
        try {
            if (path.startsWith("/ui/")) {
                String name = path.substring(4);
                if (name.contains("..")) return notFound();
                String mime = name.endsWith(".html") ? "text/html" : name.endsWith(".css") ? "text/css"
                        : name.endsWith(".js") ? "application/javascript" : name.endsWith(".png") ? "image/png"
                        : name.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
                return new WebResourceResponse(mime, "utf-8", c.getAssets().open("ui/" + name));
            }
            String u = url.getQueryParameter("u");
            if (u == null) return notFound();
            Uri media = Uri.parse(u);
            String mime = url.getQueryParameter("m");
            if (mime == null || mime.isEmpty()) mime = UriSource.guessMime(u);
            if (path.equals("/thumb")) {
                int size = 320;
                try {
                    size = Integer.parseInt(url.getQueryParameter("s"));
                } catch (Exception ignored) {
                }
                String key = u + "|" + size;
                byte[] b = thumbs.get(key);
                if (b == null) {
                    b = Thumbs.jpeg(c, media, mime, size);
                    if (b == null) return notFound();
                    thumbs.put(key, b);
                }
                return cached("image/jpeg", new ByteArrayInputStream(b));
            }
            if (path.equals("/img")) {
                // Formats the WebView can't show (HEIC, RAW...) become JPEG.
                boolean webOk = mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/gif")
                        || mime.equals("image/webp") || mime.equals("image/bmp") || mime.equals("image/svg+xml")
                        || mime.equals("image/avif");
                if (!webOk) {
                    Bitmap bm = Thumbs.decodeImage(c, media, 3072);
                    if (bm == null) return notFound();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    bm.recycle();
                    return cached("image/jpeg", new ByteArrayInputStream(out.toByteArray()));
                }
                InputStream in = "file".equals(media.getScheme()) ? new FileInputStream(media.getPath())
                        : c.getContentResolver().openInputStream(media);
                return in == null ? notFound() : cached(mime, in);
            }
        } catch (Exception | OutOfMemoryError e) {
            return notFound();
        }
        return notFound();
    }

    private static WebResourceResponse cached(String mime, InputStream in) {
        WebResourceResponse r = new WebResourceResponse(mime, null, in);
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=86400");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }
}
