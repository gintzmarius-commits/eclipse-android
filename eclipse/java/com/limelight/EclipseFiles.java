package com.limelight;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * The phone's "Fichiers" tab: browsing the PCs' files, downloading them
 * (into Download/Eclipse) and sending files to a PC folder. Results go to
 * the page as events: "files" (answers), "transfer" (progress).
 */
final class EclipseFiles {
    interface Out {
        void emit(String event, String json);
    }

    private final Context c;
    private final Out out;
    private final ExecutorService calls = Executors.newFixedThreadPool(3);
    private final ExecutorService transfers = Executors.newFixedThreadPool(2);

    EclipseFiles(Context c, Out out) {
        this.c = c.getApplicationContext();
        this.out = out;
    }

    void shutdown() {
        calls.shutdownNow();
        transfers.shutdownNow();
    }

    private static String q(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String errorOf(Response r) {
        try {
            JSONObject o = new JSONObject(r.body().string());
            String e = o.optString("error");
            if (!e.isEmpty()) return e;
        } catch (Exception ignored) {
        }
        if (r.code() == 403) return "ce téléphone n'est plus connecté à ce PC : reconnecte-le (QR code)";
        if (r.code() == 404) return "ce PC a une version d'Eclipse trop ancienne : mets-la à jour";
        return "erreur " + r.code();
    }

    private static String friendly(Throwable t) {
        String m = t.getMessage();
        if (t instanceof java.net.SocketTimeoutException) return "le PC ne répond pas";
        if (t instanceof javax.net.ssl.SSLException) return "connexion refusée par le PC : mets Eclipse à jour sur le PC";
        if (m == null || m.isEmpty()) return "erreur de connexion";
        return m;
    }

    /** The paired PCs, for the page: [{id, name, kind, online}]. */
    String devices(java.util.Map<String, Boolean> online) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (EclipsePcFiles.Pc p : EclipsePcFiles.pcs(c)) {
                JSONObject o = new JSONObject();
                o.put("id", p.uuid);
                o.put("name", p.name);
                o.put("kind", "pc");
                Boolean on = online.get(p.uuid);
                o.put("online", on == null || on);
                arr.put(o);
            }
        } catch (Exception e) {
            LimeLog.warning("Eclipse: files devices: " + e);
        }
        return arr.toString();
    }

    private EclipsePcFiles.Pc pc(String uuid) throws IOException {
        EclipsePcFiles.Pc p = EclipsePcFiles.find(c, uuid);
        if (p == null) throw new IOException("ce PC n'est pas connecté à ce téléphone");
        return p;
    }

    /** One request whose answer is a page of JSON (roots, list). */
    void call(final String req, final String uuid, final String op, final String path) {
        calls.execute(() -> {
            JSONObject ev = new JSONObject();
            try {
                ev.put("req", req);
                EclipsePcFiles.Pc p = pc(uuid);
                EclipsePcFiles.relays(c);
                String url = null;
                Exception last = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        String base = EclipsePcFiles.base(p, attempt > 0);
                        url = base + "/x/pc/" + ("roots".equals(op) ? "roots" : "list?path=" + q(path));
                        OkHttpClient cl = EclipsePcFiles.client(c, p.cert, 30);
                        try (Response r = cl.newCall(new Request.Builder().url(url).build()).execute()) {
                            if (r.code() != 200) throw new IOException(errorOf(r));
                            ev.put("ok", true);
                            ev.put("data", new JSONObject(r.body().string()));
                            last = null;
                            break;
                        }
                    } catch (IOException e) {
                        last = e;
                        if (e.getMessage() != null && !(e instanceof java.net.ConnectException) && url != null
                                && !(e instanceof java.net.SocketTimeoutException) && !(e instanceof javax.net.ssl.SSLException)) {
                            break; // the PC answered: no need to try another address
                        }
                        EclipsePcFiles.forget(uuid);
                    }
                }
                if (last != null) throw last;
            } catch (Throwable t) {
                try {
                    ev.put("ok", false);
                    ev.put("error", friendly(t));
                } catch (Exception ignored) {
                }
            }
            out.emit("files", ev.toString());
        });
    }

    private void transfer(String id, String name, String dir, int pct, String state, String error, String uri) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("dir", dir);
            o.put("pct", pct);
            o.put("state", state);
            if (error != null) o.put("error", error);
            if (uri != null) o.put("uri", uri);
        } catch (Exception ignored) {
        }
        out.emit("transfer", o.toString());
    }

    // ---- downloads: PC -> Download/Eclipse ----

    void download(final String id, final String uuid, final String path, final String name, final boolean open) {
        transfer(id, name, "down", 0, "run", null, null);
        transfers.execute(() -> {
            Uri saved = null;
            try {
                EclipsePcFiles.Pc p = pc(uuid);
                EclipsePcFiles.relays(c);
                String base = EclipsePcFiles.base(p, false);
                OkHttpClient cl = EclipsePcFiles.client(c, p.cert, 60);
                try (Response r = cl.newCall(new Request.Builder().url(base + "/x/pc/get?path=" + q(path)).build()).execute()) {
                    if (r.code() != 200) throw new IOException(errorOf(r));
                    long total = r.body().contentLength();
                    Target t = target(name);
                    saved = t.uri;
                    long done = 0;
                    int last = -1;
                    try (InputStream in = r.body().byteStream(); OutputStream os = t.out) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) > 0) {
                            os.write(b, 0, n);
                            done += n;
                            int pct = total > 0 ? (int) (done * 100 / total) : -1;
                            if (pct != last) {
                                last = pct;
                                transfer(id, name, "down", pct, "run", null, null);
                            }
                        }
                    }
                    if (total > 0 && done != total) throw new IOException("téléchargement interrompu");
                    Uri u = t.finish(c, done);
                    saved = null;
                    transfer(id, t.name, "down", 100, "done", null, u.toString());
                    if (open) view(u, t.name);
                }
            } catch (Throwable e) {
                LimeLog.warning("Eclipse: download: " + e);
                if (saved != null) {
                    try {
                        c.getContentResolver().delete(saved, null, null);
                    } catch (Exception ignored) {
                    }
                }
                transfer(id, name, "down", 0, "error", friendly(e), null);
            }
        });
    }

    /** Where a download is written. */
    private static final class Target {
        Uri uri;          // MediaStore entry (Android 10+)
        File file;        // Android 8, 9
        OutputStream out;
        String name;

        Uri finish(Context c, long size) throws IOException {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                c.getContentResolver().update(uri, v, null, null);
                try (Cursor cur = c.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) name = cur.getString(0);
                }
                return uri;
            }
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            @SuppressWarnings("deprecation")
            long did = dm.addCompletedDownload(file.getName(), "Eclipse", true, mime(file.getName()), file.getAbsolutePath(), size, false);
            Uri u = dm.getUriForDownloadedFile(did);
            return u != null ? u : Uri.fromFile(file);
        }
    }

    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String t = dot >= 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
        return t == null ? "application/octet-stream" : t;
    }

    private Target target(String name) throws IOException {
        Target t = new Target();
        String clean = EclipseShare.safeName(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, clean);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime(clean));
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Eclipse");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = c.getContentResolver();
            t.uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (t.uri == null) throw new IOException("impossible d'enregistrer dans Téléchargements");
            t.out = cr.openOutputStream(t.uri);
            if (t.out == null) throw new IOException("impossible d'enregistrer dans Téléchargements");
            t.name = clean;
            return t;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Eclipse");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("autorise Eclipse à enregistrer des fichiers (réglages d'Android)");
        synchronized (EclipseShare.class) {
            t.file = EclipseShare.freeFile(dir, clean);
            t.out = new FileOutputStream(t.file);
        }
        t.name = t.file.getName();
        return t;
    }

    /** Opens a downloaded file with the right app. */
    void view(Uri u, String name) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(Intent.createChooser(i, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            LimeLog.warning("Eclipse: view: " + e);
        }
    }

    // ---- sending: phone -> a PC folder ----

    void upload(final String uuid, final String dir, final List<Uri> uris) {
        for (final Uri u : uris) {
            final String id = "up" + System.nanoTime();
            final String[] meta = nameAndSize(u);
            transfer(id, meta[0], "up", 0, "run", null, null);
            transfers.execute(() -> {
                try {
                    EclipsePcFiles.Pc p = pc(uuid);
                    EclipsePcFiles.relays(c);
                    String base = EclipsePcFiles.base(p, false);
                    OkHttpClient cl = EclipsePcFiles.client(c, p.cert, 120);
                    final long size = Long.parseLong(meta[1]);
                    RequestBody body = new RequestBody() {
                        @Override
                        public MediaType contentType() {
                            return MediaType.get("application/octet-stream");
                        }

                        @Override
                        public long contentLength() {
                            return size;
                        }

                        @Override
                        public void writeTo(BufferedSink sink) throws IOException {
                            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                                if (in == null) throw new IOException("fichier illisible");
                                byte[] b = new byte[65536];
                                long done = 0;
                                int last = -1, n;
                                while ((n = in.read(b)) > 0) {
                                    sink.write(b, 0, n);
                                    done += n;
                                    int pct = size > 0 ? (int) (done * 100 / size) : -1;
                                    if (pct != last) {
                                        last = pct;
                                        transfer(id, meta[0], "up", pct, "run", null, null);
                                    }
                                }
                            }
                        }
                    };
                    String url = base + "/x/pc/put?dir=" + q(dir) + "&name=" + q(meta[0]);
                    try (Response r = cl.newCall(new Request.Builder().url(url).post(body).build()).execute()) {
                        if (r.code() != 200) throw new IOException(errorOf(r));
                        String saved = new JSONObject(r.body().string()).optString("name", meta[0]);
                        transfer(id, saved, "up", 100, "done", null, null);
                    }
                } catch (Throwable e) {
                    LimeLog.warning("Eclipse: upload: " + e);
                    transfer(id, meta[0], "up", 0, "error", friendly(e), null);
                }
                JSONObject o = new JSONObject();
                try {
                    o.put("dev", uuid);
                    o.put("dir", dir);
                } catch (Exception ignored) {
                }
                out.emit("filesSent", o.toString());
            });
        }
    }

    /** {name, size (-1 if unknown)} of a picked file. */
    private String[] nameAndSize(Uri u) {
        String name = null;
        long size = -1;
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                name = cur.getString(0);
                if (!cur.isNull(1)) size = cur.getLong(1);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = u.getLastPathSegment() == null ? "fichier" : u.getLastPathSegment();
        }
        return new String[]{name, String.valueOf(size)};
    }
}
