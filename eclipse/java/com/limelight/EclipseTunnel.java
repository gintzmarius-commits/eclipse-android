package com.limelight;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * Eclipse's built-in Tailscale (Go library "eclipsenet", see tunnel/):
 * joins the user's private network from inside the app, and relays
 * Moonlight's connections from 127.0.0.1 to the PC's Tailscale address.
 * No Tailscale app and no VPN needed on the phone.
 */
public final class EclipseTunnel {
    /** Moonlight reaches the PC here when the tunnel is used. */
    public static final String LOCAL = "127.0.0.1";

    private static volatile Boolean available;
    private static volatile String forwarded; // "ip|port" currently relayed

    private EclipseTunnel() {
    }

    /** False on phones the library was not built for. */
    public static boolean available() {
        if (available == null) {
            try {
                eclipsenet.Eclipsenet.status();
                available = true;
            } catch (Throwable t) {
                LimeLog.warning("Eclipse: no built-in Tailscale: " + t);
                available = false;
            }
        }
        return available;
    }

    private static String hostname() {
        String m = (Build.MODEL == null ? "android" : Build.MODEL).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (m.isEmpty()) m = "android";
        if (m.length() > 40) m = m.substring(0, 40);
        return "eclipse-" + m;
    }

    public static synchronized void start(Context c) throws Exception {
        eclipsenet.Eclipsenet.start(new File(c.getFilesDir(), "tailscale").getAbsolutePath(), hostname());
    }

    /** {state, authUrl, ip, account, error}; state: stopped, starting, needslogin, approval, running, error. */
    public static JSONObject status() {
        try {
            return new JSONObject(eclipsenet.Eclipsenet.status());
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
        eclipsenet.Eclipsenet.login();
    }

    public static void logout() throws Exception {
        eclipsenet.Eclipsenet.logout();
    }

    /**
     * Relays Sunshine's ports (base port 47989 by default) on 127.0.0.1 to
     * the PC: HTTPS, HTTP, RTSP over TCP; video, control, audio, mic over UDP.
     */
    public static synchronized void forward(String ip, int base) throws Exception {
        String key = ip + "|" + base;
        if (key.equals(forwarded)) return;
        String tcp = (base - 5) + "," + base + "," + (base + 21);
        String udp = (base + 9) + "," + (base + 10) + "," + (base + 11) + "," + (base + 13);
        eclipsenet.Eclipsenet.forward(ip, tcp, udp);
        forwarded = key;
    }

    public static String lastLog() {
        try {
            return eclipsenet.Eclipsenet.lastLog();
        } catch (Throwable t) {
            return "";
        }
    }
}
