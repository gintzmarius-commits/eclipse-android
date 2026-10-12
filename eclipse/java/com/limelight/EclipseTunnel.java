package com.limelight;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Eclipse's built-in Tailscale: joins the user's private network from
 * inside the app and relays Moonlight's connections from 127.0.0.1 to the
 * PC's Tailscale address (no Tailscale app, no VPN on the phone).
 *
 * The network engine (Go library, see tunnel/) runs in its own process,
 * {@link EclipseTunnelProvider}: this class only sends it requests. If the
 * engine stops unexpectedly, Eclipse stays open and the engine is started
 * again; if it keeps stopping on this phone, it is switched off for this
 * version of Eclipse instead of failing again and again.
 *
 * Every method may wait for the engine: never call them on the UI thread
 * (except {@link #available()} once known, see {@link #init(Context)}).
 */
public final class EclipseTunnel {
    /** Moonlight reaches the PC here when the tunnel is used. */
    public static final String LOCAL = "127.0.0.1";

    /** The engine stopped this many times: switched off for this version. */
    private static final int MAX_CRASHES = 3;

    private static Context app;
    private static volatile Boolean shipped; // the library exists for this phone
    private static volatile boolean disabled;
    private static ContentProviderClient client;
    private static final Object clientLock = new Object();

    private EclipseTunnel() {
    }

    /** Call once at start (any thread). */
    public static void init(Context c) {
        if (app == null) app = c.getApplicationContext();
        if (shipped == null) {
            // Reading the APK takes a moment on a slow phone: not on the
            // screen's thread (the answer is ready long before it is needed).
            new Thread(new Runnable() {
                @Override
                public void run() {
                    check();
                }
            }, "eclipse-tunnel-check").start();
        }
    }

    private static synchronized void check() {
        if (shipped != null || app == null) return;
        disabled = crashes() >= MAX_CRASHES;
        if (disabled) LimeLog.warning("Eclipse: built-in Tailscale switched off after repeated failures");
        shipped = libraryShipped();
    }

    private static boolean shipped() {
        if (shipped == null) check();
        return Boolean.TRUE.equals(shipped);
    }

    /** False on phones the library was not built for, or if it keeps failing here. */
    public static boolean available() {
        return app != null && shipped() && !disabled;
    }

    /** It was switched off because it kept failing on this phone. */
    public static boolean failedHere() {
        return app != null && shipped() && disabled;
    }

    // ---- is the library in the APK, for the ABI this app runs as? ----

    private static boolean libraryShipped() {
        try {
            ApplicationInfo ai = app.getApplicationInfo();
            if (ai.nativeLibraryDir != null && new File(ai.nativeLibraryDir, "libgojni.so").exists()) {
                return true; // extracted at install
            }
            String abi = appAbi(ai);
            if (abi == null) return false;
            try (ZipFile z = new ZipFile(ai.sourceDir)) {
                return z.getEntry("lib/" + abi + "/libgojni.so") != null;
            }
        } catch (Throwable t) {
            LimeLog.warning("Eclipse: tunnel library check: " + t);
            return false;
        }
    }

    /** ".../lib/arm64" → "arm64-v8a": the ABI Android chose for this app. */
    private static String appAbi(ApplicationInfo ai) {
        String dir = ai.nativeLibraryDir == null ? "" : new File(ai.nativeLibraryDir).getName();
        switch (dir) {
            case "arm64":
                return "arm64-v8a";
            case "arm":
                return "armeabi-v7a";
            case "x86_64":
                return "x86_64";
            case "x86":
                return "x86";
            default:
                return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : null;
        }
    }

    // ---- crashes of the engine, counted per version of Eclipse ----

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("eclipse", Context.MODE_PRIVATE);
    }

    private static String versionKey() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return "tunnel_crashes:" + pi.versionName + "/" + pi.lastUpdateTime;
        } catch (Exception e) {
            return "tunnel_crashes:?";
        }
    }

    private static int crashes() {
        try {
            return prefs().getInt(versionKey(), 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void engineDied(String method) {
        int n = crashes() + 1;
        prefs().edit().putInt(versionKey(), n).commit();
        LimeLog.warning("Eclipse: built-in Tailscale stopped during " + method + " (" + n + "/" + MAX_CRASHES + ")");
        if (n >= MAX_CRASHES) disabled = true;
    }

    // ---- requests to the engine's process ----

    private static String authority() {
        return app.getPackageName() + ".tunnel";
    }

    private static ContentProviderClient client() {
        synchronized (clientLock) {
            if (client == null) {
                // "Unstable": if the engine's process dies, we get an error
                // here instead of Android closing Eclipse with it.
                client = app.getContentResolver().acquireUnstableContentProviderClient(authority());
            }
            return client;
        }
    }

    private static void dropClient(ContentProviderClient c) {
        synchronized (clientLock) {
            if (client == c) client = null;
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) c.close();
            else c.release();
        } catch (Throwable ignored) {
        }
    }

    private static Bundle call(String method, String arg, Bundle extras) throws Exception {
        if (!available()) throw new IllegalStateException("Tailscale intégré indisponible");
        for (int attempt = 0; ; attempt++) {
            ContentProviderClient c = client();
            if (c == null) throw new IllegalStateException("Tailscale intégré introuvable");
            try {
                Bundle b = c.call(method, arg, extras);
                return b == null ? new Bundle() : b;
            } catch (DeadObjectException e) {
                dropClient(c);
                engineDied(method);
                if (disabled || attempt >= 1) throw new IllegalStateException("Le Tailscale intégré s'est arrêté");
            }
        }
    }

    private static void check(Bundle b) throws Exception {
        String err = b.getString("error");
        if (err != null) throw new Exception(err);
    }

    public static void start(Context c) throws Exception {
        init(c);
        Bundle x = new Bundle();
        x.putString("dir", new File(app.getFilesDir(), "tailscale").getAbsolutePath());
        x.putString("hostname", hostname());
        check(call("start", null, x));
    }

    private static String hostname() {
        String m = (Build.MODEL == null ? "android" : Build.MODEL).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (m.isEmpty()) m = "android";
        if (m.length() > 40) m = m.substring(0, 40);
        return "eclipse-" + m;
    }

    /** {state, authUrl, ip, account, error}; state: stopped, starting, needslogin, approval, running, error. */
    public static JSONObject status() {
        try {
            Bundle b = call("status", null, null);
            check(b);
            return new JSONObject(b.getString("json", "{\"state\":\"stopped\"}"));
        } catch (Throwable t) {
            JSONObject o = new JSONObject();
            try {
                o.put("state", "error");
                o.put("error", String.valueOf(t.getMessage()));
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    public static String state() {
        return status().optString("state", "stopped");
    }

    public static void login() throws Exception {
        check(call("login", null, null));
    }

    public static void logout() throws Exception {
        check(call("logout", null, null));
    }

    /**
     * Relays Sunshine's ports (base port 47989 by default) on 127.0.0.1 to
     * the PC: HTTPS, HTTP, RTSP over TCP; video, control, audio, mic over UDP;
     * and Eclipse's file sharing (48150).
     * Asking again for the same PC costs nothing.
     */
    public static void forward(String ip, int base) throws Exception {
        Bundle x = new Bundle();
        x.putString("tcp", (base - 5) + "," + base + "," + (base + 21) + "," + EclipsePcFiles.PORT);
        x.putString("udp", (base + 9) + "," + (base + 10) + "," + (base + 11) + "," + (base + 13));
        check(call("forward", ip, x));
    }

    /**
     * Relays one PC on its own local address (127.0.0.2, .3, …: several PCs
     * at once), like {@link #forward(String, int)}.
     */
    public static void forwardAt(String local, String ip, int base) throws Exception {
        Bundle x = new Bundle();
        x.putString("local", local);
        x.putString("tcp", (base - 5) + "," + base + "," + (base + 21) + "," + EclipsePcFiles.PORT);
        x.putString("udp", (base + 9) + "," + (base + 10) + "," + (base + 11) + "," + (base + 13));
        check(call("forwardAt", ip, x));
    }

    public static void stopForward(String local) {
        try {
            call("stopForward", local, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The PCs of the private network running Sunshine:
     * [{ip, port, id, name, peer}], empty if none or not connected.
     */
    public static org.json.JSONArray discover(String ports, int timeoutMs) {
        try {
            Bundle x = new Bundle();
            x.putString("ports", ports);
            x.putInt("timeout", timeoutMs);
            Bundle b = call("discover", null, x);
            check(b);
            return new org.json.JSONArray(b.getString("json", "[]"));
        } catch (Throwable t) {
            return new org.json.JSONArray();
        }
    }

    public static String lastLog() {
        try {
            return call("lastLog", null, null).getString("text", "");
        } catch (Throwable t) {
            return "";
        }
    }
}
