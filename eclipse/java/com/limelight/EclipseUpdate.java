package com.limelight;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.ConnectivityManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Updates of the Eclipse app itself: each time Eclipse opens, it asks
 * GitHub for its latest version; a newer one is downloaded, checked (same
 * file as published, Eclipse of that version) and installed. Android asks
 * to confirm the first time; after that (Android 12+) updates install by
 * themselves. On mobile data, the download waits for a tap (it is ~45 MB).
 */
public final class EclipseUpdate {
    static final String LATEST = "https://api.github.com/repos/gintzmarius-commits/eclipse-android/releases/latest";

    public interface Listener {
        /** stage: available, download, install, error; json event for the page. */
        void onUpdate(JSONObject ev);
    }

    private static volatile boolean busy;
    private static volatile JSONObject pending; // newer version waiting for a tap (mobile data)

    private EclipseUpdate() {
    }

    /** "2.3.10" newer than "2.3.9"? */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    static String current(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Test hook (emulator): a file only root or the app can write. */
    private static String source(Context c) {
        File f = new File(c.getFilesDir(), "test-update-url");
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[512];
                int n = in.read(b);
                if (n > 0) return new String(b, 0, n, "UTF-8").trim();
            } catch (Exception ignored) {
            }
        }
        return LATEST;
    }

    private static boolean testing(Context c) {
        return new File(c.getFilesDir(), "test-update-url").exists();
    }

    /** Called when Eclipse opens (background thread). */
    public static void check(Context c, Listener l, boolean userAsked) {
        if (busy) return;
        busy = true;
        try {
            JSONObject rel;
            if (userAsked && pending != null) {
                rel = pending;
            } else {
                rel = new JSONObject(get(source(c)));
            }
            String tag = rel.optString("tag_name", "");
            String ver = tag.startsWith("v") ? tag.substring(1) : tag;
            String cur = current(c);
            if (ver.isEmpty() || !newer(ver, cur)) {
                LimeLog.info("Eclipse: up to date (" + cur + ", latest " + ver + ")");
                return;
            }
            JSONObject asset = null;
            JSONArray as = rel.optJSONArray("assets");
            for (int i = 0; as != null && i < as.length(); i++) {
                JSONObject a = as.optJSONObject(i);
                if (a != null && "Eclipse.apk".equals(a.optString("name"))) asset = a;
            }
            if (asset == null) return;
            long size = asset.optLong("size", 0);
            if (!userAsked && metered(c)) {
                pending = rel;
                l.onUpdate(ev("available", ver, 0, null, size));
                return;
            }
            pending = null;
            File apk = new File(new File(c.getCacheDir(), "update"), "Eclipse-" + ver + ".apk");
            download(asset.optString("browser_download_url"), apk, size, ver, l);
            String digest = asset.optString("digest", "");
            if (digest.startsWith("sha256:") && !digest.substring(7).equalsIgnoreCase(sha256(apk))) {
                apk.delete();
                throw new Exception("le fichier téléchargé est abîmé");
            }
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi == null || !c.getPackageName().equals(pi.packageName)
                    || (!testing(c) && !ver.equals(pi.versionName))) {
                apk.delete();
                throw new Exception("ce n'est pas Eclipse " + ver);
            }
            l.onUpdate(ev("install", ver, 100, null, size));
            install(c, apk);
        } catch (Throwable t) {
            LimeLog.warning("Eclipse: update: " + t);
            if (userAsked) l.onUpdate(ev("error", "", 0, "La mise à jour a échoué : " + t.getMessage(), 0));
        } finally {
            busy = false;
        }
    }

    private static JSONObject ev(String stage, String ver, int pct, String err, long size) {
        JSONObject o = new JSONObject();
        try {
            o.put("stage", stage);
            o.put("version", ver);
            o.put("percent", pct);
            o.put("size", size);
            if (err != null) o.put("error", err);
        } catch (Exception ignored) {
        }
        return o;
    }

    @SuppressWarnings("deprecation")
    private static boolean metered(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "Eclipse-Android");
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setInstanceFollowRedirects(true);
        return h;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            if (h.getResponseCode() != 200) throw new Exception("GitHub " + h.getResponseCode());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0 && out.size() < (4 << 20)) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    private static void download(String url, File dest, long size, String ver, Listener l) throws Exception {
        dest.getParentFile().mkdirs();
        File[] old = dest.getParentFile().listFiles();
        if (old != null) for (File f : old) if (!f.equals(dest)) f.delete();
        HttpURLConnection h = open(url);
        long done = 0;
        int last = -1;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            if (h.getResponseCode() != 200) throw new Exception("téléchargement : " + h.getResponseCode());
            long total = h.getContentLength() > 0 ? h.getContentLength() : size;
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct != last) {
                    last = pct;
                    l.onUpdate(ev("download", ver, pct, null, total));
                }
            }
            if (total > 0 && done < total) throw new Exception("téléchargement incomplet");
        } finally {
            h.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    /** Hands the APK to Android's installer (as an update of this app). */
    private static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // After the first confirmed update, the next ones need no tap.
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("Eclipse.apk", 0, apk.length())) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(c, Receiver.class).setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, cb, flags);
            s.commit(pend.getIntentSender());
        }
        LimeLog.info("Eclipse: update handed to the installer");
    }

    /** Result of the installation: asks Android to show its confirmation if needed. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception e) {
                        LimeLog.warning("Eclipse: update confirmation: " + e);
                    }
                }
            } else {
                LimeLog.info("Eclipse: update status " + st + " " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }
}
