package com.limelight;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Sharing this phone's files with its PCs (Eclipse 2.4). While it is on, a
 * request waits on each PC ("anything for me?"): the PC (or a browser
 * signed in on it) asks through it for a folder, a file, or sends one.
 * The phone never listens on the network; a notification says it is on.
 * Nothing is ever overwritten or deleted.
 */
public class EclipseShare extends Service {
    static final String PREF = "share_files";
    private static final String CHANNEL = "share";
    private static final int NOTIF_ID = 4824;

    private volatile boolean running;
    private final Map<String, Thread> pollers = new HashMap<>();
    private ExecutorService jobs;
    private Thread manager;

    // ---- on / off ----

    static boolean enabled(Context c) {
        return EclipsePcFiles.prefs(c).getBoolean(PREF, false);
    }

    /** The permission to read and write all the phone's files. */
    static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static void set(Context c, boolean on) {
        EclipsePcFiles.prefs(c).edit().putBoolean(PREF, on).apply();
        apply(c);
    }

    /** Starts or stops the service to match the setting (Eclipse opens, phone restarts…). */
    static void apply(Context c) {
        Intent i = new Intent(c, EclipseShare.class);
        if (enabled(c) && hasAccess(c)) {
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } catch (Throwable t) {
                // Android 12+ refuses from the background: next time Eclipse opens.
                LimeLog.warning("Eclipse: share start: " + t);
            }
        } else {
            c.stopService(i);
        }
    }

    /** Started at boot (if it was on). */
    public static final class Boot extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                apply(c);
            }
        }
    }

    // ---- the service ----

    @Override
    public void onCreate() {
        super.onCreate();
        EclipseTunnel.init(this);
        jobs = Executors.newFixedThreadPool(4);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            LimeLog.warning("Eclipse: share foreground: " + t);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!enabled(this) || !hasAccess(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            manager = new Thread(this::manage, "eclipse-share");
            manager.start();
            LimeLog.info("Eclipse: sharing this phone's files");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (manager != null) manager.interrupt();
        synchronized (pollers) {
            for (Thread t : pollers.values()) t.interrupt();
            pollers.clear();
        }
        if (jobs != null) jobs.shutdownNow();
        LimeLog.info("Eclipse: file sharing stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Partage de fichiers", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Indique que tes PC peuvent voir les fichiers de ce téléphone");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, EclipseActivity.class).putExtra("tab", "files")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("Fichiers partagés avec tes PC")
                .setContentText("Tes PC peuvent voir et t'envoyer des fichiers. Touche pour gérer.")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    /** Every 20 seconds: one waiting request per paired PC. */
    private void manage() {
        while (running) {
            try {
                // Away from home: the built-in Tailscale (started here if
                // Eclipse is not open).
                if (EclipseTunnel.available() && "stopped".equals(EclipseTunnel.state()) && usesTunnel()) {
                    try {
                        EclipseTunnel.start(this);
                    } catch (Exception e) {
                        LimeLog.warning("Eclipse: share: tunnel: " + e);
                    }
                }
                EclipsePcFiles.relays(this);
                List<EclipsePcFiles.Pc> pcs = EclipsePcFiles.pcs(this);
                synchronized (pollers) {
                    for (final EclipsePcFiles.Pc p : pcs) {
                        Thread t = pollers.get(p.uuid);
                        if (t != null && t.isAlive()) continue;
                        t = new Thread(() -> poll(p.uuid), "eclipse-share-" + p.name);
                        pollers.put(p.uuid, t);
                        t.start();
                    }
                }
                Thread.sleep(20000);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LimeLog.warning("Eclipse: share: " + t);
                try {
                    Thread.sleep(20000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private boolean usesTunnel() {
        android.content.SharedPreferences p = EclipsePcFiles.prefs(this);
        if (p.getBoolean("tunnel_used", false)) return true;
        for (String k : p.getAll().keySet()) {
            if (k.startsWith("remote:")) return true;
        }
        return false;
    }

    private void poll(String uuid) {
        long backoff = 2000;
        while (running && !Thread.currentThread().isInterrupted()) {
            EclipsePcFiles.Pc p = EclipsePcFiles.find(this, uuid);
            if (p == null) return; // removed
            try {
                String base = EclipsePcFiles.base(p, backoff > 2000);
                OkHttpClient cl = EclipsePcFiles.client(this, p.cert, 45);
                try (Response r = cl.newCall(new Request.Builder().url(base + "/p/poll").build()).execute()) {
                    if (r.code() == 204) {
                        backoff = 2000;
                        continue;
                    }
                    if (r.code() != 200) throw new IOException("PC : " + r.code());
                    final JSONObject job = new JSONObject(r.body().string());
                    final String b = base;
                    final EclipsePcFiles.Pc pc = p;
                    jobs.execute(() -> handle(pc, b, job));
                    backoff = 2000;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                EclipsePcFiles.forget(uuid);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    return;
                }
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    // ---- answering the PC ----

    private void handle(EclipsePcFiles.Pc p, String base, JSONObject job) {
        String id = job.optString("id");
        String op = job.optString("op");
        try {
            OkHttpClient cl = EclipsePcFiles.client(this, p.cert, 120);
            switch (op) {
                case "roots":
                    reply(cl, base, id, 200, null, json(roots(this)));
                    return;
                case "list":
                    reply(cl, base, id, 200, null, json(list(job.optString("path"))));
                    return;
                case "get":
                    sendFile(cl, base, id, job.optString("path"));
                    return;
                case "put":
                    String name = receive(cl, base, id, job.optString("dir"), job.optString("name"), job.optLong("size", -1));
                    JSONObject o = new JSONObject();
                    o.put("name", name);
                    reply(cl, base, id, 200, null, json(o));
                    return;
                default:
                    throw new IOException("demande inconnue");
            }
        } catch (Throwable t) {
            LimeLog.warning("Eclipse: share " + op + ": " + t);
            try {
                JSONObject e = new JSONObject();
                e.put("error", message(t));
                reply(EclipsePcFiles.client(this, p.cert, 30), base, id, 400, null, json(e));
            } catch (Throwable ignored) {
            }
        }
    }

    private static String message(Throwable t) {
        if (t instanceof SecurityException) return "accès refusé par Android";
        String m = t.getMessage();
        return m == null || m.isEmpty() ? "erreur sur le téléphone" : m;
    }

    private static RequestBody json(Object o) {
        return RequestBody.create(o.toString(), MediaType.get("application/json; charset=utf-8"));
    }

    private static void reply(OkHttpClient cl, String base, String id, int status, Map<String, String> headers, RequestBody body) throws IOException {
        Request.Builder rb = new Request.Builder().url(base + "/p/reply/" + id).post(body).header("X-Status", String.valueOf(status));
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        }
        try (Response r = cl.newCall(rb.build()).execute()) {
            r.body().string();
        }
    }

    // ---- this phone's storage ----

    static File checked(String path) throws IOException {
        if (path == null || !path.startsWith("/") || path.contains("\0")) throw new IOException("chemin invalide");
        File f = new File(path).getCanonicalFile();
        if (!f.getPath().startsWith("/storage/") && !f.getPath().startsWith("/sdcard") && !f.getPath().startsWith("/mnt/media_rw/")) {
            throw new IOException("en dehors des fichiers du téléphone");
        }
        return f;
    }

    static JSONObject roots(Context c) throws Exception {
        JSONArray out = new JSONArray();
        List<String> seen = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        addRoot(out, seen, "Stockage interne", internal, "disk");
        if (Build.VERSION.SDK_INT >= 30) {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                File d = v.getDirectory();
                if (d == null || v.isPrimary()) continue;
                addRoot(out, seen, v.getDescription(c), d, "disk");
            }
        } else {
            File[] dirs = c.getExternalFilesDirs(null);
            for (int i = 1; dirs != null && i < dirs.length; i++) {
                if (dirs[i] == null) continue;
                String p = dirs[i].getPath();
                int k = p.indexOf("/Android/data/");
                if (k > 0) addRoot(out, seen, "Carte SD", new File(p.substring(0, k)), "disk");
            }
        }
        String[][] folders = {
                {Environment.DIRECTORY_DCIM, "Appareil photo"},
                {Environment.DIRECTORY_PICTURES, "Images"},
                {Environment.DIRECTORY_DOWNLOADS, "Téléchargements"},
                {Environment.DIRECTORY_DOCUMENTS, "Documents"},
                {Environment.DIRECTORY_MOVIES, "Vidéos"},
                {Environment.DIRECTORY_MUSIC, "Musique"},
        };
        for (String[] f : folders) {
            File d = new File(internal, f[0]);
            if (d.isDirectory()) addRoot(out, seen, f[1], d, "folder");
        }
        JSONObject o = new JSONObject();
        o.put("roots", out);
        return o;
    }

    private static void addRoot(JSONArray out, List<String> seen, String name, File dir, String kind) throws Exception {
        if (dir == null || seen.contains(dir.getPath())) return;
        seen.add(dir.getPath());
        JSONObject r = new JSONObject();
        r.put("name", name);
        r.put("path", dir.getPath());
        r.put("kind", kind);
        out.put(r);
    }

    static JSONObject list(String path) throws Exception {
        File dir = checked(path);
        if (!dir.isDirectory()) throw new IOException("ce dossier n'existe plus");
        File[] fs = dir.listFiles();
        if (fs == null) throw new IOException("accès refusé par Android");
        List<File> files = new ArrayList<>(Arrays.asList(fs));
        Collections.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        JSONArray es = new JSONArray();
        boolean more = false;
        for (File f : files) {
            if (es.length() >= 20000) {
                more = true;
                break;
            }
            if (f.getName().endsWith(".eclipse-part")) continue;
            JSONObject e = new JSONObject();
            e.put("name", f.getName());
            boolean d = f.isDirectory();
            e.put("dir", d);
            e.put("size", d ? 0 : f.length());
            e.put("mtime", f.lastModified());
            es.put(e);
        }
        JSONObject o = new JSONObject();
        o.put("path", dir.getPath());
        File parent = dir.getParentFile();
        o.put("parent", parent == null || dir.getPath().equals("/storage/emulated/0") ? "" : parent.getPath());
        o.put("entries", es);
        if (more) o.put("more", true);
        return o;
    }

    private static void sendFile(OkHttpClient cl, String base, String id, String path) throws Exception {
        final File f = checked(path);
        if (!f.isFile()) throw new IOException("ce fichier n'existe plus");
        if (!f.canRead()) throw new IOException("accès refusé par Android");
        final long size = f.length();
        Map<String, String> h = new HashMap<>();
        h.put("X-Size", String.valueOf(size));
        h.put("X-Name", URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20"));
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
                try (InputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) sink.write(b, 0, n);
                }
            }
        };
        reply(cl, base, id, 200, h, body);
    }

    /** Receives a file sent by the PC into dir; returns the name it got. */
    private static String receive(OkHttpClient cl, String base, String id, String dirPath, String name, long size) throws Exception {
        File dir = checked(dirPath);
        if (!dir.isDirectory()) throw new IOException("ce dossier n'existe plus");
        String clean = safeName(name);
        File part;
        synchronized (EclipseShare.class) {
            File target = freeFile(dir, clean);
            part = new File(dir, target.getName() + ".eclipse-part");
            if (!part.createNewFile()) throw new IOException("impossible d'écrire ici");
        }
        long got = 0;
        try (Response r = cl.newCall(new Request.Builder().url(base + "/p/blob/" + id).build()).execute()) {
            if (r.code() != 200) throw new IOException("le PC n'a pas envoyé le fichier");
            try (InputStream in = r.body().byteStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    got += n;
                }
            }
            if (size >= 0 && got != size) throw new IOException("envoi interrompu");
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        synchronized (EclipseShare.class) {
            String finalName = part.getName().substring(0, part.getName().length() - ".eclipse-part".length());
            File dest = new File(dir, finalName); // its ".eclipse-part" is this file
            if (dest.exists()) dest = freeFile(dir, finalName);
            if (!part.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("impossible d'écrire ici");
            }
            return dest.getName();
        }
    }

    static String safeName(String n) throws IOException {
        if (n == null) n = "";
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch < 0x20 || ch == 0x7f) continue;
            b.append("<>:\"|?*".indexOf(ch) >= 0 ? '_' : ch);
        }
        n = b.toString().trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1).trim();
        if (n.isEmpty() || n.equals("..")) throw new IOException("nom de fichier invalide");
        if (n.length() > 200) n = n.substring(0, 200);
        return n;
    }

    /** name, or "name (2).ext", "name (3).ext"… if taken. */
    static File freeFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists() && !new File(dir, name + ".eclipse-part").exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists() && !new File(dir, f.getName() + ".eclipse-part").exists()) return f;
        }
    }
}
